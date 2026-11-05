package com.msmesh.seats.net;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Synthetic requests stay on loopback; no university credentials are used. */
public class HttpTest {
	private static final String BODY = "{\"TimetableId\":123,\"CheckInInput\":\"synthetic-code\"}";

	@Test(timeout = 10000)
	public void returnsResponseAndPreservesJsonRequest() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.start();
			server.enqueue(new MockResponse().setBody("{\"isValid\":true}"));
			Http.Result result = send(server);
			assertTrue(result.ok());
			assertEquals("{\"isValid\":true}", result.body);
			RecordedRequest request = onlyRequest(server);
			assertEquals("Bearer synthetic-token", request.getHeader("Authorization"));
			assertEquals("application/json", request.getHeader("Accept"));
			assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"));
		}
	}

	@Test(timeout = 10000)
	public void doesNotRetryHttpErrorsOrFollowRedirects() throws Exception {
		for (int code : new int[]{401, 408, 500, 503, 302, 307, 308}) {
			try (MockWebServer server = new MockWebServer()) {
				server.start();
				MockResponse response = new MockResponse().setResponseCode(code).setBody("Synthetic failure");
				if (code == 503) response.setHeader("Retry-After", "0");
				if (code >= 300 && code < 400) response.setHeader("Location", server.url("/redirected"));
				server.enqueue(response);
				server.enqueue(new MockResponse().setBody("Unexpected retry"));
				Http.Result result = send(server);
				assertEquals(code, result.code);
				assertFalse(result.ok());
				assertEquals("Synthetic failure", result.body);
				onlyRequest(server);
			}
		}
	}

	@Test(timeout = 10000)
	public void lostReplyDoesNotRepeatThePost() throws Exception {
		try (MockWebServer server = new MockWebServer()) {
			server.start();
			server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
			server.enqueue(new MockResponse().setBody("Unexpected retry"));
			assertThrows(IOException.class, () -> send(server));
			onlyRequest(server);
		}
	}

	private Http.Result send(MockWebServer server) throws IOException {
		return Http.postJson(server.url("/checkin").toString(), "synthetic-token", BODY);
	}

	private RecordedRequest onlyRequest(MockWebServer server) throws InterruptedException {
		assertEquals(1, server.getRequestCount());
		RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
		assertNotNull(request);
		assertEquals("POST", request.getMethod());
		assertEquals("/checkin", request.getPath());
		assertEquals(BODY, request.getBody().readUtf8());
		return request;
	}
}
