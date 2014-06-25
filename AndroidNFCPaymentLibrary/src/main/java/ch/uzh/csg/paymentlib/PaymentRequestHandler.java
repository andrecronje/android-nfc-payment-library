package ch.uzh.csg.paymentlib;

import java.nio.charset.Charset;
import java.util.Arrays;

import android.app.Activity;
import android.util.Log;
import ch.uzh.csg.mbps.customserialization.DecoderFactory;
import ch.uzh.csg.mbps.customserialization.InitMessagePayee;
import ch.uzh.csg.mbps.customserialization.PaymentRequest;
import ch.uzh.csg.mbps.customserialization.PaymentResponse;
import ch.uzh.csg.nfclib.CustomHostApduService;
import ch.uzh.csg.nfclib.CustomHostApduService2;
import ch.uzh.csg.nfclib.IMessageHandler;
import ch.uzh.csg.nfclib.NfcEvent;
import ch.uzh.csg.paymentlib.PaymentRequestInitializer.PaymentType;
import ch.uzh.csg.paymentlib.container.ServerInfos;
import ch.uzh.csg.paymentlib.container.UserInfos;
import ch.uzh.csg.paymentlib.exceptions.IllegalArgumentException;
import ch.uzh.csg.paymentlib.messages.PaymentError;
import ch.uzh.csg.paymentlib.messages.PaymentMessage;
import ch.uzh.csg.paymentlib.persistency.IPersistencyHandler;
import ch.uzh.csg.paymentlib.persistency.PersistedPaymentRequest;
import ch.uzh.csg.paymentlib.util.Config;

/**
 * This class is the counterpart of {@link PaymentRequestInitializer} and
 * handles a payment request comming from another NFC device.
 * 
 * This class handles the underlying NFC and the messages which need to be
 * processed and returned.
 * 
 * If the server response is not returned within a given threshold (see
 * {@link Config}) then the {@link PaymentEvent}.NO_SERVER_RESPONSE is fired.
 * All other events from {@link PaymentEvent} are also fired appropriately
 * during the communication.
 * 
 * @author Jeton Memeti
 * 
 */
public class PaymentRequestHandler {
	
	public static final String TAG = "##NFC## PaymentRequestHandler";
	
	/**
	 * The ack message to be returned if the payment finished successfully.
	 */
	public static final byte[] ACK = new byte[] { (byte) 0xAC };
	
	private IPaymentEventHandler paymentEventHandler;
	private UserInfos userInfos;
	private ServerInfos serverInfos;
	private IUserPromptPaymentRequest userPrompt;
	private IPersistencyHandler persistencyHandler;
	private MessageHandler messageHandler;
	
	private int nofMessages = 0;
	private boolean aborted = false;
	
	/**
	 * Instantiates a new payment request handler, which handles incoming
	 * payment requests (irrespective of the {@link PaymentType}).
	 * 
	 * @param activity
	 *            the current application's activity, needed to hook the NFC
	 * @param paymentEventHandler
	 *            the event handler, which will be notified on any
	 *            {@link PaymentEvent}
	 * @param userInfos
	 *            the user information of the current user
	 * @param serverInfos
	 *            the server information
	 * @param userPrompt
	 *            the object responsible for prompting the user if he accepts or
	 *            rejects the payment and returning the answer
	 * @param persistencyHandler
	 *            the object responsible for writing
	 *            {@link PersistedPaymentRequest} to the device's local storage
	 * @throws IllegalArgumentException
	 *             if any paramter is not valid (e.g., null)
	 */
	public PaymentRequestHandler(Activity activity, IPaymentEventHandler paymentEventHandler, UserInfos userInfos, ServerInfos serverInfos, IUserPromptPaymentRequest userPrompt, IPersistencyHandler persistencyHandler) throws IllegalArgumentException {
		checkParameters(activity, paymentEventHandler, userInfos, serverInfos, userPrompt, persistencyHandler);
		
		this.paymentEventHandler = paymentEventHandler;
		this.userInfos = userInfos;
		this.serverInfos = serverInfos;
		this.userPrompt = userPrompt;
		this.persistencyHandler = persistencyHandler;
		this.messageHandler = new MessageHandler();
		
		CustomHostApduService c = new CustomHostApduService(activity, nfcEventHandler, messageHandler);
		CustomHostApduService2.init(c);
	}
	
	private void checkParameters(Activity activity, IPaymentEventHandler paymentEventHandler, UserInfos userInfos, ServerInfos serverInfos, IUserPromptPaymentRequest userPrompt, IPersistencyHandler persistencyHandler) throws IllegalArgumentException {
		if (activity == null)
			throw new IllegalArgumentException("The activity cannot be null.");
		
		if (paymentEventHandler == null)
			throw new IllegalArgumentException("The payment event handler cannot be null.");
		
		if (userInfos == null)
			throw new IllegalArgumentException("The user infos cannot be null.");
		
		if (serverInfos == null)
			throw new IllegalArgumentException("The server infos cannot be null.");
		
		if (userPrompt == null)
			throw new IllegalArgumentException("The user prompt cannot be null.");
		
		if (persistencyHandler == null)
			throw new IllegalArgumentException("The persistency handler cannot be null.");
	}
	
	private NfcEvent nfcEventHandler = new NfcEvent() {
		
		@Override
		public void handleMessage(Type event, Object object) {
			if (aborted)
				return;
			
			switch (event) {
			case INIT_FAILED:
			case FATAL_ERROR:
				aborted = true;
				paymentEventHandler.handleMessage(PaymentEvent.ERROR, null);
				break;
			case CONNECTION_LOST:
				nofMessages = 0;
				break;
			case INITIALIZED: //do nothing
				break;
			case MESSAGE_RECEIVED: //do nothing, handle in IMessageHandler
				break;
			case MESSAGE_SENT_HCE: //do nothing
				break;
			case MESSAGE_SENT:// do nothing, concerns only the NfcTransceiver
				break;
			default:
				break;
			}
		}
		
	};
	
	private byte[] getError(PaymentError err) {
		aborted = true;
		paymentEventHandler.handleMessage(PaymentEvent.ERROR, err);
		return new PaymentMessage().error().payload(new byte[] { err.getCode() }).bytes();
	}
	
	/*
	 * only for test purposes
	 */
	protected MessageHandler getMessageHandler() {
		return new MessageHandler();
	}
	
	/*
	 * only for test purposes
	 */
	protected NfcEvent getNfcEventHandler() {
		return nfcEventHandler;
	}
	
	protected class MessageHandler implements IMessageHandler {
		
		private PersistedPaymentRequest persistedPaymentRequest;
		private volatile boolean serverResponseArrived = false;


		public byte[] handleMessage(byte[] message) {
			Log.d(TAG, "got payment message: "+Arrays.toString(message));
			if (aborted)
				return null;
			
			nofMessages++;
			PaymentMessage pm = new PaymentMessage().bytes(message);
			if (pm.isError()) {
				try {
					PaymentError paymentError = PaymentError.getPaymentError(pm.payload()[0]);
					return getError(paymentError);
				} catch (Exception e) {
					Log.d(TAG, "exception", e);
					return getError(PaymentError.UNEXPECTED_ERROR);
				}
			}
			
			if (pm.isPayer()) {
				switch (nofMessages) {
				case 1:
					byte[] bytes = userInfos.getUsername().getBytes(Charset.forName("UTF-8"));
					
					Thread t = new Thread(new TimeoutHandler());
					t.start();
					
					return new PaymentMessage().payee().payload(bytes).bytes();
				case 2:
					serverResponseArrived = true;
					
					try {
						Log.d(TAG, "DBG1: "+Arrays.toString(pm.payload()));
						PaymentResponse paymentResponse = DecoderFactory.decode(PaymentResponse.class, pm.payload());
						boolean signatureValid = paymentResponse.verify(serverInfos.getPublicKey());
						if (!signatureValid) {
							Log.d(TAG, "exception sig not valid " + serverInfos.getPublicKey());
							return getError(PaymentError.UNEXPECTED_ERROR);
						} else {
							persistencyHandler.delete(persistedPaymentRequest);
							
							switch (paymentResponse.getStatus()) {
							case FAILURE:
								paymentEventHandler.handleMessage(PaymentEvent.ERROR, PaymentError.SERVER_REFUSED);
								break;
							case SUCCESS:
								paymentEventHandler.handleMessage(PaymentEvent.SUCCESS, paymentResponse);
								break;
							case DUPLICATE_REQUEST:
								paymentEventHandler.handleMessage(PaymentEvent.ERROR, PaymentError.DUPLICATE_REQUEST);
								break;
							}
							
							return new PaymentMessage().payload(ACK).bytes();
						}
					} catch (Exception e) {
						Log.d(TAG, "exception", e);
						return getError(PaymentError.UNEXPECTED_ERROR);
					}
				}
			} else {
				switch (nofMessages) {
				case 1:
					try {
						InitMessagePayee initMessage = DecoderFactory.decode(InitMessagePayee.class, pm.payload());
						
						boolean paymentAccepted;
						
						if (persistedPaymentRequest != null
								&& persistedPaymentRequest.getUsername().equals(initMessage.getUsername())
								&& persistedPaymentRequest.getCurrency().getCode() == initMessage.getCurrency().getCode()
								&& persistedPaymentRequest.getAmount() == initMessage.getAmount()) {
							/*
							 * this is a retry because the last try was not
							 * successful (= no server response) or a payment
							 * resume (the user took his device away to
							 * accept/reject the payment)
							 */
							paymentAccepted = userPrompt.isPaymentAccepted();
						} else {
							// this is a new session
							persistedPaymentRequest = persistencyHandler.getPersistedPaymentRequest(initMessage.getUsername(), initMessage.getCurrency(), initMessage.getAmount());
							if (persistedPaymentRequest == null) {
								// this is a new payment request (not a payment request with a lost server response)
								persistedPaymentRequest = new PersistedPaymentRequest(initMessage.getUsername(), initMessage.getCurrency(), initMessage.getAmount(), System.currentTimeMillis());
							}
							
							paymentAccepted = userPrompt.promptUserPaymentRequest(initMessage.getUsername(), initMessage.getCurrency(), initMessage.getAmount());
						}
						
						if (paymentAccepted) {
							//response 1st message
							PaymentRequest pr = new PaymentRequest(userInfos.getPKIAlgorithm(), userInfos.getKeyNumber(), userInfos.getUsername(), initMessage.getUsername(), initMessage.getCurrency(), initMessage.getAmount(), persistedPaymentRequest.getTimestamp());
							pr.sign(userInfos.getPrivateKey());
							byte[] encoded = pr.encode();
							
							Thread t = new Thread(new TimeoutHandler());
							t.start();
							
							persistencyHandler.add(persistedPaymentRequest);
							return new PaymentMessage().payload(encoded).bytes();
						} else {
							return getError(PaymentError.PAYER_REFUSED);
						}
					} catch (Exception e) {
						Log.d(TAG, "exception", e);
						return getError(PaymentError.UNEXPECTED_ERROR);
					}
				case 2:
					serverResponseArrived = true;
					
					try {
						Log.d(TAG, "DBG1: "+Arrays.toString(pm.payload()));
						PaymentResponse paymentResponse = DecoderFactory.decode(PaymentResponse.class, pm.payload());
						boolean signatureValid = paymentResponse.verify(serverInfos.getPublicKey());
						if (!signatureValid) {
							Log.d(TAG, "exception sig not valid " + serverInfos.getPublicKey());
							return getError(PaymentError.UNEXPECTED_ERROR);
						} else {
							persistencyHandler.delete(persistedPaymentRequest);
							
							switch (paymentResponse.getStatus()) {
							case FAILURE:
								paymentEventHandler.handleMessage(PaymentEvent.ERROR, PaymentError.SERVER_REFUSED);
								break;
							case SUCCESS:
								paymentEventHandler.handleMessage(PaymentEvent.SUCCESS, paymentResponse);
								break;
							case DUPLICATE_REQUEST:
								paymentEventHandler.handleMessage(PaymentEvent.ERROR, PaymentError.DUPLICATE_REQUEST);
								break;
							}
							
							return new PaymentMessage().payload(ACK).bytes();
						}
					} catch (Exception e) {
						Log.d(TAG, "exception", e);
						return getError(PaymentError.UNEXPECTED_ERROR);
					}
				}
			}
			Log.d(TAG, "exception generic");
			return getError(PaymentError.UNEXPECTED_ERROR);
		}
		
		private class TimeoutHandler implements Runnable {
			
			public void run() {
				long startTime = System.currentTimeMillis();
				
				while (!serverResponseArrived) {
					long now = System.currentTimeMillis();
					if (now - startTime > Config.SERVER_RESPONSE_TIMEOUT) {
						paymentEventHandler.handleMessage(PaymentEvent.NO_SERVER_RESPONSE, null);
						break;
					}
					try {
						Thread.sleep(50);
					} catch (InterruptedException e) {
						break;
					}
				}
			}
		}
	}
	
}