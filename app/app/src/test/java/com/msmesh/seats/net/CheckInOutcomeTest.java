package com.msmesh.seats.net;

import org.junit.Test;

import java.io.IOException;
import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Synthetic responses only; no university credentials or requests. */
public class CheckInOutcomeTest {
	@Test public void confirmedAttendancePreservesServerEvidence() {
		for (int status : new int[]{2, 3, 7}) {
			CheckInOutcome outcome = read(200, "{\"checkInResponse\":{\"studentSchedule\":{\"attendanceStatusId\":" + status + "}}}");
			assertEquals(CheckInOutcome.State.CONFIRMED, outcome.state);
			assertEquals(status, outcome.statusId);
			assertEquals(0, outcome.swipeIn);
		}
		long seconds = Instant.parse("2026-11-05T10:00:00Z").getEpochSecond();
		for (String stamp : new String[]{"\"2026-11-05T10:00:00Z\"", "\"2026-11-05T10:00:00\"", String.valueOf(seconds)}) {
			CheckInOutcome outcome = read(201, "{\"checkInResponse\":{\"studentSchedule\":{\"swipeInDateTime\":" + stamp + "}}}");
			assertEquals(CheckInOutcome.State.CONFIRMED, outcome.state);
			assertEquals(seconds, outcome.swipeIn);
			assertTrue(outcome.usedWindowTime);
		}
	}

	@Test public void acceptedOperationIsPendingUntilAttendanceIsApplied() {
		assertEquals(CheckInOutcome.State.PENDING, read(202, "{\"checkInResponse\":{\"success\":true}}").state);
		for (String body : new String[]{"{\"success\":true}", "{\"isValid\":true}", "{\"success\":true,\"isValid\":true}"}) {
			assertEquals(CheckInOutcome.State.UNKNOWN, read(200, body).state);
		}
	}

	@Test public void explicitRejectionAndInvalidCodeRemainVisible() {
		CheckInOutcome invalid = read(200, "{\"isValid\":false,\"messages\":[\"Synthetic invalid code\"]}");
		assertEquals(CheckInOutcome.State.REJECTED, invalid.state);
		assertTrue(invalid.message.contains("Synthetic invalid code"));
		for (String body : new String[]{"{\"success\":false}", "{\"checkInResponse\":{\"success\":false}}"}) {
			assertEquals(CheckInOutcome.State.REJECTED, read(200, body).state);
		}
	}

	@Test public void httpErrorsCannotConfirmAttendance() {
		String body = "{\"checkInResponse\":{\"studentSchedule\":{\"attendanceStatusId\":3}}}";
		for (int code : new int[]{400, 401, 403, 429, 408, 500, 503}) {
			CheckInOutcome outcome = read(code, body);
			assertEquals(code < 500 && code != 408 ? CheckInOutcome.State.REJECTED : CheckInOutcome.State.UNKNOWN, outcome.state);
			assertEquals(code, outcome.httpCode);
			assertTrue(outcome.message.contains("HTTP " + code));
		}
	}

	@Test public void networkFailureDoesNotClaimTheRequestWasRejected() {
		for (Exception failure : new Exception[]{new IOException("synthetic"), new Exception(new IOException("synthetic"))}) {
			CheckInOutcome outcome = CheckInOutcome.failed(failure);
			assertEquals(CheckInOutcome.State.UNKNOWN, outcome.state);
			assertTrue(outcome.message.contains("may have reached"));
		}
		assertEquals(CheckInOutcome.State.UNKNOWN, CheckInOutcome.failed(new IllegalStateException()).state);
	}

	@Test public void incompleteMalformedOrWronglyTypedResponsesAreUnknown() {
		for (String body : new String[]{null, "", "{", "{}", "null", "[]", "{success:true}", "{} trailing",
				"{\"checkInResponse\":null}", "{\"checkInResponse\":[]}", "{\"checkInResponse\":{\"success\":\"true\"}}",
				"{\"checkInResponse\":{\"studentSchedule\":{\"attendanceStatusId\":\"3\"}}}",
				"{\"checkInResponse\":{\"studentSchedule\":{\"swipeInDateTime\":false}}}"}) {
			assertEquals(String.valueOf(body), CheckInOutcome.State.UNKNOWN, read(200, body).state);
		}
		assertEquals(CheckInOutcome.State.UNKNOWN, read(204, "").state);
		assertEquals(CheckInOutcome.State.UNKNOWN, CheckInOutcome.read(null).state);
	}

	@Test public void conflictingFailureAndConfirmationAreNotSuccess() {
		for (String body : new String[]{"{\"isValid\":false,\"checkInResponse\":{\"studentSchedule\":{\"attendanceStatusId\":3}}}",
				"{\"success\":false,\"checkInResponse\":{\"success\":true}}",
				"{\"checkInResponse\":{\"success\":false,\"studentSchedule\":{\"swipeInDateTime\":1793872800}}}"}) {
			assertEquals(CheckInOutcome.State.UNKNOWN, read(200, body).state);
		}
	}

	@Test public void messagesAreNullSafeBoundedAndNeverRawResponse() {
		for (String messages : new String[]{"null", "[]", "[null,{},1]"}) {
			assertEquals(CheckInOutcome.State.REJECTED, read(200, "{\"isValid\":false,\"messages\":" + messages + "}").state);
		}
		CheckInOutcome outcome = read(200, "{\"isValid\":false,\"messages\":[\"line\\nwith\\tcontrols\",\"" + "x".repeat(2000) + "\"]}");
		assertTrue(outcome.message.length() <= 600);
		assertFalse(outcome.message.contains("\n"));
		assertFalse(outcome.message.contains("\t"));
		assertFalse(read(200, "{\"private\":\"SYNTHETIC_RAW_SECRET\"}").message.contains("SYNTHETIC_RAW_SECRET"));
	}

	private static CheckInOutcome read(int status, String body) {
		CheckInResult result = new CheckInResult();
		result.httpCode = status;
		result.raw = body;
		result.usedWindowTime = true;
		return CheckInOutcome.read(result);
	}
}
