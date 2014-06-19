package ch.uzh.csg.paymentlib;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Arrays;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import android.app.Activity;
import android.util.Log;
import ch.uzh.csg.mbps.customserialization.Currency;
import ch.uzh.csg.mbps.customserialization.DecoderFactory;
import ch.uzh.csg.mbps.customserialization.InitMessagePayee;
import ch.uzh.csg.mbps.customserialization.PKIAlgorithm;
import ch.uzh.csg.mbps.customserialization.PaymentRequest;
import ch.uzh.csg.mbps.customserialization.PaymentResponse;
import ch.uzh.csg.mbps.customserialization.ServerPaymentRequest;
import ch.uzh.csg.mbps.customserialization.ServerPaymentResponse;
import ch.uzh.csg.mbps.customserialization.ServerResponseStatus;
import ch.uzh.csg.mbps.customserialization.exceptions.UnknownCurrencyException;
import ch.uzh.csg.paymentlib.PaymentRequestHandler.MessageHandler;
import ch.uzh.csg.paymentlib.container.PaymentInfos;
import ch.uzh.csg.paymentlib.container.ServerInfos;
import ch.uzh.csg.paymentlib.container.UserInfos;
import ch.uzh.csg.paymentlib.messages.PaymentError;
import ch.uzh.csg.paymentlib.messages.PaymentMessage;
import ch.uzh.csg.paymentlib.persistency.IPersistencyHandler;
import ch.uzh.csg.paymentlib.persistency.PersistedPaymentRequest;
import ch.uzh.csg.paymentlib.testutils.TestUtils;
import ch.uzh.csg.paymentlib.util.Config;

@RunWith(PowerMockRunner.class)
@PrepareForTest(Log.class)
public class PaymentRequestHandlerTest {
	
	private Activity hostActivity = Mockito.mock(Activity.class);
	
	private boolean paymentError = false;
	private Object paymentErrorObject = null;
	private boolean paymentForwardToServer = false;
	private Object paymentForwardToServerObject = null;
	private boolean paymentNoServerResponse = false;
	private Object paymentNoServerResponseObject = null;
	private boolean paymentSuccess = false;
	private Object paymentSuccessObject = null;
	private boolean paymentOtherEvent = false;
	private Object paymentOtherEventObject = null;
	
	private void reset() {
		paymentError = false;
		paymentErrorObject = null;
		paymentForwardToServer = false;
		paymentForwardToServerObject = null;
		paymentNoServerResponse = false;
		paymentNoServerResponseObject = null;
		paymentSuccess = false;
		paymentSuccessObject = null;
		paymentOtherEvent = false;
		paymentOtherEventObject = null;
	}
	
	@Before
	public void before() {
		PowerMockito.mockStatic(Log.class);
		PowerMockito.when(Log.d(Mockito.anyString(), Mockito.anyString())).then(new Answer<Integer>() {
			@Override
            public Integer answer(InvocationOnMock invocation) throws Throwable {
	            System.err.println(Arrays.toString(invocation.getArguments()));
	            return 0;
            }
		});
	}
	
	private PaymentEventInterface paymentEventHandler = new PaymentEventInterface() {
		
		@Override
		public void handleMessage(PaymentEvent event, Object object) {
			switch (event) {
			case ERROR:
				paymentError = true;
				paymentErrorObject = object;
				break;
			case FORWARD_TO_SERVER:
				paymentForwardToServer = true;
				paymentForwardToServerObject = object;
				break;
			case NO_SERVER_RESPONSE:
				paymentNoServerResponse = true;
				paymentNoServerResponseObject = object;
				break;
			case SUCCESS:
				paymentSuccess = true;
				paymentSuccessObject = object;
				break;
			default:
				paymentOtherEvent = true;
				paymentOtherEventObject = object;
				break;
			
			}
		}
	};
	
	@Test
	public void testPaymentRequestHandler_Payee_ServerRefuses() throws Exception {
		/*
		 * Simulates server refuses the payment for any reason
		 */
		reset();

		KeyPair keyPairPayee = TestUtils.generateKeyPair();
		UserInfos userInfosPayee = new UserInfos("seller", keyPairPayee.getPrivate(), PKIAlgorithm.DEFAULT, 1);
		PaymentInfos paymentInfos = new PaymentInfos(Currency.BTC, 1);
		
		KeyPair keyPairPayer = TestUtils.generateKeyPair();
		KeyPair keyPairServer = TestUtils.generateKeyPair();
		UserInfos userInfosPayer = new UserInfos("buyer", keyPairPayer.getPrivate(), PKIAlgorithm.DEFAULT, 1);
		ServerInfos serverInfos = new ServerInfos(keyPairServer.getPublic());
		
		IUserPromptPaymentRequest userPrompt = new IUserPromptPaymentRequest() {
			@Override
			public boolean getPaymentRequestAnswer(String username, Currency currency, long amount) {
				// accept the payment
				return true;
			}

			@Override
			public boolean isPaymentAccepted() {
				// accept the payment
				return true;
			}
		};
		
		IPersistencyHandler iph = new IPersistencyHandler() {
			@Override
			public PersistedPaymentRequest getPersistedPaymentRequest(String username, Currency currency, long amount) {
				return null;
			}
			@Override
			public void add(PersistedPaymentRequest paymentRequest) {
			}
			@Override
			public void delete(PersistedPaymentRequest paymentRequest) {
			}
		};
		PaymentRequestHandler prh = new PaymentRequestHandler(hostActivity, paymentEventHandler, userInfosPayer, serverInfos, userPrompt, iph);
		MessageHandler messageHandler = prh.getMessageHandler();
		
		// receive payment request
		InitMessagePayee initMessage = new InitMessagePayee(userInfosPayee.getUsername(), paymentInfos.getCurrency(), paymentInfos.getAmount());
		byte[] data = new PaymentMessage(PaymentMessage.DEFAULT, initMessage.encode()).getData();
		
		byte[] handleMessage = messageHandler.handleMessage(data);
		
		PaymentMessage pm = new PaymentMessage((byte[]) handleMessage);
		assertEquals(PaymentMessage.DEFAULT, pm.getStatus());
		PaymentRequest paymentRequestPayer = DecoderFactory.decode(PaymentRequest.class, pm.getPayload());
		
		assertEquals(userInfosPayer.getUsername(), paymentRequestPayer.getUsernamePayer());
		assertEquals(userInfosPayee.getUsername(), paymentRequestPayer.getUsernamePayee());
		assertEquals(paymentInfos.getCurrency().getCode(), paymentRequestPayer.getCurrency().getCode());
		assertEquals(paymentInfos.getAmount(), paymentRequestPayer.getAmount());
		
		// receive payment response
		PaymentRequest paymentRequestPayee = new PaymentRequest(userInfosPayee.getPKIAlgorithm(), userInfosPayee.getKeyNumber(), paymentRequestPayer.getUsernamePayer(), userInfosPayee.getUsername(), paymentInfos.getCurrency(), paymentInfos.getAmount(), paymentRequestPayer.getTimestamp());
		assertTrue(paymentRequestPayer.requestsIdentic(paymentRequestPayee));
		paymentRequestPayee.sign(userInfosPayer.getPrivateKey());
		ServerPaymentRequest spr = new ServerPaymentRequest(paymentRequestPayer, paymentRequestPayee);
		
		ServerPaymentRequest decode = DecoderFactory.decode(ServerPaymentRequest.class, spr.encode());
		PaymentRequest paymentRequestPayer2 = decode.getPaymentRequestPayer();
		
		String reason = "any reason";
		PaymentResponse pr = new PaymentResponse(PKIAlgorithm.DEFAULT, 1, ServerResponseStatus.FAILURE, reason, paymentRequestPayer2.getUsernamePayer(), paymentRequestPayer2.getUsernamePayee(), paymentRequestPayer2.getCurrency(), paymentRequestPayer2.getAmount(), paymentRequestPayer2.getTimestamp());
		pr.sign(keyPairServer.getPrivate());
		ServerPaymentResponse spr2 = new ServerPaymentResponse(pr);
		byte[] encode = spr2.encode();
		
		ServerPaymentResponse serverPaymentResponse = DecoderFactory.decode(ServerPaymentResponse.class, encode);
		byte[] encode2 = serverPaymentResponse.getPaymentResponsePayer().encode();
		
		byte[] handleMessage2 = messageHandler.handleMessage(encode2);
		PaymentMessage pm2 = new PaymentMessage(handleMessage2);
		assertEquals(PaymentMessage.DEFAULT, pm2.getStatus());
		assertEquals(1, pm2.getPayloadLength());
		assertEquals(PaymentRequestHandler.ACK[0], pm2.getPayload()[0]);
		
		
		assertFalse(paymentForwardToServer);
		assertNull(paymentForwardToServerObject);
		assertFalse(paymentNoServerResponse);
		assertNull(paymentNoServerResponseObject);
		assertFalse(paymentSuccess);
		assertNull(paymentSuccessObject);
		assertFalse(paymentOtherEvent);
		assertNull(paymentOtherEventObject);
		
		assertTrue(paymentError);
		assertTrue(paymentErrorObject instanceof PaymentError);
		PaymentError err = (PaymentError) paymentErrorObject;
		assertEquals(PaymentError.SERVER_REFUSED.getCode(), err.getCode());
	}
	
	@Test
	public void testPaymentRequestHandler_Success() throws Exception {
		/*
		 * Simulates a successful payment
		 */
		reset();

		KeyPair keyPairPayee = TestUtils.generateKeyPair();
		UserInfos userInfosPayee = new UserInfos("seller", keyPairPayee.getPrivate(), PKIAlgorithm.DEFAULT, 1);
		PaymentInfos paymentInfos = new PaymentInfos(Currency.BTC, 1);
		
		KeyPair keyPairPayer = TestUtils.generateKeyPair();
		KeyPair keyPairServer = TestUtils.generateKeyPair();
		UserInfos userInfosPayer = new UserInfos("buyer", keyPairPayer.getPrivate(), PKIAlgorithm.DEFAULT, 1);
		ServerInfos serverInfos = new ServerInfos(keyPairServer.getPublic());
		
		IUserPromptPaymentRequest userPrompt = new IUserPromptPaymentRequest() {
			@Override
			public boolean getPaymentRequestAnswer(String username, Currency currency, long amount) {
				// accept the payment
				return true;
			}

			@Override
			public boolean isPaymentAccepted() {
				// accept the payment
				return true;
			}
		};
		
		IPersistencyHandler iph = new IPersistencyHandler() {
			@Override
			public PersistedPaymentRequest getPersistedPaymentRequest(String username, Currency currency, long amount) {
				return null;
			}
			@Override
			public void add(PersistedPaymentRequest paymentRequest) {
			}
			@Override
			public void delete(PersistedPaymentRequest paymentRequest) {
			}
		};
		PaymentRequestHandler prh = new PaymentRequestHandler(hostActivity, paymentEventHandler, userInfosPayer, serverInfos, userPrompt, iph);
		MessageHandler messageHandler = prh.getMessageHandler();
		
		// receive payment request
		InitMessagePayee initMessage = new InitMessagePayee(userInfosPayee.getUsername(), paymentInfos.getCurrency(), paymentInfos.getAmount());
		byte[] data = new PaymentMessage(PaymentMessage.DEFAULT, initMessage.encode()).getData();
		
		byte[] handleMessage = messageHandler.handleMessage(data);
		
		PaymentMessage pm = new PaymentMessage((byte[]) handleMessage);
		assertEquals(PaymentMessage.DEFAULT, pm.getStatus());
		PaymentRequest paymentRequestPayer = DecoderFactory.decode(PaymentRequest.class, pm.getPayload());
		
		assertEquals(userInfosPayer.getUsername(), paymentRequestPayer.getUsernamePayer());
		assertEquals(userInfosPayee.getUsername(), paymentRequestPayer.getUsernamePayee());
		assertEquals(paymentInfos.getCurrency().getCode(), paymentRequestPayer.getCurrency().getCode());
		assertEquals(paymentInfos.getAmount(), paymentRequestPayer.getAmount());
		
		// receive payment response
		PaymentRequest paymentRequestPayee = new PaymentRequest(userInfosPayee.getPKIAlgorithm(), userInfosPayee.getKeyNumber(), paymentRequestPayer.getUsernamePayer(), userInfosPayee.getUsername(), paymentInfos.getCurrency(), paymentInfos.getAmount(), paymentRequestPayer.getTimestamp());
		assertTrue(paymentRequestPayer.requestsIdentic(paymentRequestPayee));
		paymentRequestPayee.sign(userInfosPayer.getPrivateKey());
		ServerPaymentRequest spr = new ServerPaymentRequest(paymentRequestPayer, paymentRequestPayee);
		
		ServerPaymentRequest decode = DecoderFactory.decode(ServerPaymentRequest.class, spr.encode());
		PaymentRequest paymentRequestPayer2 = decode.getPaymentRequestPayer();
		
		PaymentResponse pr = new PaymentResponse(PKIAlgorithm.DEFAULT, 1, ServerResponseStatus.SUCCESS, null, paymentRequestPayer2.getUsernamePayer(), paymentRequestPayer2.getUsernamePayee(), paymentRequestPayer2.getCurrency(), paymentRequestPayer2.getAmount(), paymentRequestPayer2.getTimestamp());
		pr.sign(keyPairServer.getPrivate());
		ServerPaymentResponse spr2 = new ServerPaymentResponse(pr);
		byte[] encode = spr2.encode();
		
		ServerPaymentResponse serverPaymentResponse = DecoderFactory.decode(ServerPaymentResponse.class, encode);
		byte[] encode2 = serverPaymentResponse.getPaymentResponsePayer().encode();
		
		byte[] handleMessage2 = messageHandler.handleMessage(encode2);
		PaymentMessage pm2 = new PaymentMessage(handleMessage2);
		assertEquals(PaymentMessage.DEFAULT, pm2.getStatus());
		assertEquals(1, pm2.getPayloadLength());
		assertEquals(PaymentRequestHandler.ACK[0], pm2.getPayload()[0]);
		
		Thread.sleep(Config.SERVER_RESPONSE_TIMEOUT+500);
		
		assertFalse(paymentError);
		assertNull(paymentErrorObject);
		assertFalse(paymentForwardToServer);
		assertNull(paymentForwardToServerObject);
		assertFalse(paymentNoServerResponse);
		assertNull(paymentNoServerResponseObject);
		assertFalse(paymentOtherEvent);
		assertNull(paymentOtherEventObject);
		
		assertTrue(paymentSuccess);
		assertNotNull(paymentSuccessObject);
		assertTrue(paymentSuccessObject instanceof PaymentResponse);
		PaymentResponse pr1 = (PaymentResponse) paymentSuccessObject;
		assertEquals(userInfosPayer.getUsername(), pr1.getUsernamePayer());
		assertEquals(userInfosPayee.getUsername(), pr1.getUsernamePayee());
	}
	
	@Test
	public void testPaymentRequestHandler_ServerResponseTimeout() throws Exception {
		/*
		 * Simulates a server response timeout
		 */
		reset();

		KeyPair keyPairPayee = TestUtils.generateKeyPair();
		UserInfos userInfosPayee = new UserInfos("seller", keyPairPayee.getPrivate(), PKIAlgorithm.DEFAULT, 1);
		PaymentInfos paymentInfos = new PaymentInfos(Currency.BTC, 1);
		
		KeyPair keyPairPayer = TestUtils.generateKeyPair();
		KeyPair keyPairServer = TestUtils.generateKeyPair();
		UserInfos userInfosPayer = new UserInfos("buyer", keyPairPayer.getPrivate(), PKIAlgorithm.DEFAULT, 1);
		ServerInfos serverInfos = new ServerInfos(keyPairServer.getPublic());
		
		IUserPromptPaymentRequest userPrompt = new IUserPromptPaymentRequest() {
			@Override
			public boolean getPaymentRequestAnswer(String username, Currency currency, long amount) {
				// accept the payment
				return true;
			}

			@Override
			public boolean isPaymentAccepted() {
				// accept the payment
				return true;
			}
		};
		
		IPersistencyHandler iph = new IPersistencyHandler() {
			@Override
			public PersistedPaymentRequest getPersistedPaymentRequest(String username, Currency currency, long amount) {
				return null;
			}
			@Override
			public void add(PersistedPaymentRequest paymentRequest) {
			}
			@Override
			public void delete(PersistedPaymentRequest paymentRequest) {
			}
		};
		PaymentRequestHandler prh = new PaymentRequestHandler(hostActivity, paymentEventHandler, userInfosPayer, serverInfos, userPrompt, iph);
		MessageHandler messageHandler = prh.getMessageHandler();
		
		// receive payment request
		InitMessagePayee initMessage = new InitMessagePayee(userInfosPayee.getUsername(), paymentInfos.getCurrency(), paymentInfos.getAmount());
		byte[] data = new PaymentMessage(PaymentMessage.DEFAULT, initMessage.encode()).getData();
		
		byte[] handleMessage = messageHandler.handleMessage(data);
		
		PaymentMessage pm = new PaymentMessage((byte[]) handleMessage);
		assertEquals(PaymentMessage.DEFAULT, pm.getStatus());
		PaymentRequest paymentRequestPayer = DecoderFactory.decode(PaymentRequest.class, pm.getPayload());
		
		assertEquals(userInfosPayer.getUsername(), paymentRequestPayer.getUsernamePayer());
		assertEquals(userInfosPayee.getUsername(), paymentRequestPayer.getUsernamePayee());
		assertEquals(paymentInfos.getCurrency().getCode(), paymentRequestPayer.getCurrency().getCode());
		assertEquals(paymentInfos.getAmount(), paymentRequestPayer.getAmount());
		
		Thread.sleep(Config.SERVER_RESPONSE_TIMEOUT+500);
		
		
		assertFalse(paymentError);
		assertNull(paymentErrorObject);
		assertFalse(paymentForwardToServer);
		assertNull(paymentForwardToServerObject);
		assertFalse(paymentOtherEvent);
		assertNull(paymentOtherEventObject);
		assertFalse(paymentSuccess);
		assertNull(paymentSuccessObject);
		
		assertTrue(paymentNoServerResponse);
		assertNull(paymentNoServerResponseObject);
	}
	
	@Test
	public void testPaymentRequestHandler_PersistencyHandler() throws Exception {
		/*
		 * Simulates a successful payment
		 */
		reset();

		KeyPair keyPairPayee = TestUtils.generateKeyPair();
		UserInfos userInfosPayee = new UserInfos("seller", keyPairPayee.getPrivate(), PKIAlgorithm.DEFAULT, 1);
		PaymentInfos paymentInfos = new PaymentInfos(Currency.BTC, 1);
		
		KeyPair keyPairPayer = TestUtils.generateKeyPair();
		KeyPair keyPairServer = TestUtils.generateKeyPair();
		UserInfos userInfosPayer = new UserInfos("buyer", keyPairPayer.getPrivate(), PKIAlgorithm.DEFAULT, 1);
		ServerInfos serverInfos = new ServerInfos(keyPairServer.getPublic());
		
		IUserPromptPaymentRequest userPrompt = new IUserPromptPaymentRequest() {
			@Override
			public boolean getPaymentRequestAnswer(String username, Currency currency, long amount) {
				// accept the payment
				return true;
			}

			@Override
			public boolean isPaymentAccepted() {
				// accept the payment
				return true;
			}
		};
		
		PersistencyHandler iph = new PersistencyHandler();
		
		PaymentRequestHandler prh = new PaymentRequestHandler(hostActivity, paymentEventHandler, userInfosPayer, serverInfos, userPrompt, iph);
		MessageHandler messageHandler = prh.getMessageHandler();
		
		// receive payment request
		InitMessagePayee initMessage = new InitMessagePayee(userInfosPayee.getUsername(), paymentInfos.getCurrency(), paymentInfos.getAmount());
		byte[] data = new PaymentMessage(PaymentMessage.DEFAULT, initMessage.encode()).getData();
		
		byte[] handleMessage = messageHandler.handleMessage(data);
		
		PaymentMessage pm = new PaymentMessage((byte[]) handleMessage);
		assertEquals(PaymentMessage.DEFAULT, pm.getStatus());
		PaymentRequest paymentRequestPayer = DecoderFactory.decode(PaymentRequest.class, pm.getPayload());
		
		assertEquals(userInfosPayer.getUsername(), paymentRequestPayer.getUsernamePayer());
		assertEquals(userInfosPayee.getUsername(), paymentRequestPayer.getUsernamePayee());
		assertEquals(paymentInfos.getCurrency().getCode(), paymentRequestPayer.getCurrency().getCode());
		assertEquals(paymentInfos.getAmount(), paymentRequestPayer.getAmount());
		
		assertEquals(1, iph.getList().size());
		
		// receive payment response
		PaymentRequest paymentRequestPayee = new PaymentRequest(userInfosPayee.getPKIAlgorithm(), userInfosPayee.getKeyNumber(), paymentRequestPayer.getUsernamePayer(), userInfosPayee.getUsername(), paymentInfos.getCurrency(), paymentInfos.getAmount(), paymentRequestPayer.getTimestamp());
		assertTrue(paymentRequestPayer.requestsIdentic(paymentRequestPayee));
		paymentRequestPayee.sign(userInfosPayer.getPrivateKey());
		ServerPaymentRequest spr = new ServerPaymentRequest(paymentRequestPayer, paymentRequestPayee);
		
		ServerPaymentRequest decode = DecoderFactory.decode(ServerPaymentRequest.class, spr.encode());
		PaymentRequest paymentRequestPayer2 = decode.getPaymentRequestPayer();
		
		PaymentResponse pr = new PaymentResponse(PKIAlgorithm.DEFAULT, 1, ServerResponseStatus.SUCCESS, null, paymentRequestPayer2.getUsernamePayer(), paymentRequestPayer2.getUsernamePayee(), paymentRequestPayer2.getCurrency(), paymentRequestPayer2.getAmount(), paymentRequestPayer2.getTimestamp());
		pr.sign(keyPairServer.getPrivate());
		ServerPaymentResponse spr2 = new ServerPaymentResponse(pr);
		byte[] encode = spr2.encode();
		
		ServerPaymentResponse serverPaymentResponse = DecoderFactory.decode(ServerPaymentResponse.class, encode);
		byte[] encode2 = serverPaymentResponse.getPaymentResponsePayer().encode();
		
		byte[] handleMessage2 = messageHandler.handleMessage(encode2);
		PaymentMessage pm2 = new PaymentMessage(handleMessage2);
		assertEquals(PaymentMessage.DEFAULT, pm2.getStatus());
		assertEquals(1, pm2.getPayloadLength());
		assertEquals(PaymentRequestHandler.ACK[0], pm2.getPayload()[0]);
		
		assertEquals(0, iph.getList().size());
		
		assertFalse(paymentError);
		assertNull(paymentErrorObject);
		assertFalse(paymentForwardToServer);
		assertNull(paymentForwardToServerObject);
		assertFalse(paymentNoServerResponse);
		assertNull(paymentNoServerResponseObject);
		assertFalse(paymentOtherEvent);
		assertNull(paymentOtherEventObject);
		
		assertTrue(paymentSuccess);
		assertNotNull(paymentSuccessObject);
		assertTrue(paymentSuccessObject instanceof PaymentResponse);
		PaymentResponse pr1 = (PaymentResponse) paymentSuccessObject;
		assertEquals(userInfosPayer.getUsername(), pr1.getUsernamePayer());
		assertEquals(userInfosPayee.getUsername(), pr1.getUsernamePayee());
	}
	
	private class PersistencyHandler implements IPersistencyHandler {
		private ArrayList<PersistedPaymentRequest> list = new ArrayList<PersistedPaymentRequest>();
		
		@Override
		public PersistedPaymentRequest getPersistedPaymentRequest(String username, Currency currency, long amount) {
			try {
				for (PersistedPaymentRequest request : list) {
					if (request.getUsername().equals(username) && request.getCurrency().getCode() == currency.getCode() && request.getAmount() == amount) {
						return request;
					}
				}
			} catch (UnknownCurrencyException e) {
			}
			return null;
		}
		@Override
		public void add(PersistedPaymentRequest paymentRequest) {
			boolean exists = false;
			for (PersistedPaymentRequest request : list) {
				if (request.equals(paymentRequest)) {
					exists = true;
					break;
				}
			}
			if (!exists) {
				list.add(paymentRequest);
			}
		}
		@Override
		public void delete(PersistedPaymentRequest paymentRequest) {
			for (int i=0; i<list.size(); i++) {
				PersistedPaymentRequest request = list.get(i);
				if (request.equals(paymentRequest)) {
					list.remove(i);
					break;
				}
			}
		}
		
		protected ArrayList<PersistedPaymentRequest> getList() {
			return list;
		}
	};
	
}
