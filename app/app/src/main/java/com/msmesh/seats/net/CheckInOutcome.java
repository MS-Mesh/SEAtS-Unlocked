package com.msmesh.seats.net;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.Strictness;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/** Shared response handling for manual and scheduled check-ins; HTTP success is not attendance. */
public final class CheckInOutcome {
	public enum State { CONFIRMED, PENDING, REJECTED, UNKNOWN }
	private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
	private static final String UNCONFIRMED = "Attendance is not confirmed. Check the event before submitting again.";

	public State state;
	public String message;
	public int httpCode;
	public int statusId;
	public long swipeIn;
	public boolean usedWindowTime;

	public static CheckInOutcome read(CheckInResult result) {
		CheckInOutcome outcome = unknown(UNCONFIRMED);
		if (result == null) return outcome;
		outcome.httpCode = result.httpCode;
		outcome.usedWindowTime = result.usedWindowTime;
		if (result.httpCode < 200 || result.httpCode >= 300) {
			boolean rejected = result.httpCode >= 400 && result.httpCode < 500 && result.httpCode != 408;
			outcome.state = rejected ? State.REJECTED : State.UNKNOWN;
			outcome.message = "HTTP " + result.httpCode + ": " + (rejected ? "The server rejected the check-in." : UNCONFIRMED);
			return outcome;
		}
		try {
			JsonElement document = JSON.fromJson(result.raw, JsonElement.class);
			if (document == null || !document.isJsonObject()) return outcome;
			JsonObject root = document.getAsJsonObject();
			JsonObject response = object(root, "checkInResponse");
			JsonObject schedule = object(response, "studentSchedule");
			Boolean success = flag(root, "success");
			Boolean valid = flag(root, "isValid");
			Boolean accepted = flag(response, "success");
			int status = status(schedule == null ? null : schedule.get("attendanceStatusId"));
			long swipe = swipe(schedule == null ? null : schedule.get("swipeInDateTime"));
			boolean applied = swipe > 0 || status == 2 || status == 3 || status == 7;
			boolean rejected = Boolean.FALSE.equals(success) || Boolean.FALSE.equals(valid) || Boolean.FALSE.equals(accepted);
			if (rejected && (applied || Boolean.TRUE.equals(accepted))) return outcome;
			if (applied) {
				outcome.state = State.CONFIRMED;
				outcome.message = "Attendance confirmed by the server.";
				outcome.statusId = status;
				outcome.swipeIn = swipe;
			} else if (rejected) {
				outcome.state = State.REJECTED;
				outcome.message = Boolean.FALSE.equals(valid) ? "The server rejected the check-in code." : "The server rejected the check-in.";
			} else if (Boolean.TRUE.equals(accepted)) {
				outcome.state = State.PENDING;
				outcome.message = "The server accepted the check-in, but attendance is not confirmed yet. Refresh the event before submitting again.";
			}
			outcome.message = text(outcome.message + messages(response) + messages(root));
		} catch (RuntimeException ignored) {
			// Missing or malformed evidence never authorizes success or an automatic resend.
		}
		return outcome;
	}

	public static CheckInOutcome failed(Exception error) {
		Throwable cause = error;
		for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
			if (cause instanceof IOException) return unknown("Network error: the request may have reached the server. " + UNCONFIRMED);
		}
		return unknown("The check-in could not be completed. " + UNCONFIRMED);
	}

	public static CheckInOutcome unknown(String message) {
		CheckInOutcome outcome = new CheckInOutcome();
		outcome.state = State.UNKNOWN;
		outcome.message = text(message);
		if (outcome.message.isEmpty()) outcome.message = UNCONFIRMED;
		return outcome;
	}

	private static JsonObject object(JsonObject parent, String name) {
		JsonElement value = parent == null ? null : parent.get(name);
		return value == null || value.isJsonNull() ? null : value.getAsJsonObject();
	}

	private static Boolean flag(JsonObject parent, String name) {
		JsonElement value = parent == null ? null : parent.get(name);
		if (value == null || value.isJsonNull()) return null;
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("Invalid flag");
		return value.getAsBoolean();
	}

	private static int status(JsonElement value) {
		if (value == null || value.isJsonNull()) return 0;
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Invalid status");
		int status = value.getAsBigDecimal().intValueExact();
		if (status < 0) throw new IllegalArgumentException("Invalid status");
		return status;
	}

	/** Preserve actual server time, including UTC date-times without a suffix; never invent now. */
	private static long swipe(JsonElement value) {
		if (value == null || value.isJsonNull()) return 0;
		if (!value.isJsonPrimitive()) throw new IllegalArgumentException("Invalid timestamp");
		long seconds;
		if (value.getAsJsonPrimitive().isNumber()) {
			seconds = value.getAsBigDecimal().longValueExact();
			Instant.ofEpochSecond(seconds);
		} else if (value.getAsJsonPrimitive().isString()) {
			String timestamp = value.getAsString().trim();
			try {
				seconds = OffsetDateTime.parse(timestamp).toEpochSecond();
			} catch (DateTimeParseException ignored) {
				seconds = LocalDateTime.parse(timestamp).toEpochSecond(ZoneOffset.UTC);
			}
		} else throw new IllegalArgumentException("Invalid timestamp");
		if (seconds < 0) throw new IllegalArgumentException("Invalid timestamp");
		return seconds;
	}

	/** Only bounded text from message arrays is shown; never fall back to the raw response. */
	private static String messages(JsonObject object) {
		JsonElement values = object == null ? null : object.get("messages");
		if (values == null || !values.isJsonArray()) return "";
		StringBuilder messages = new StringBuilder();
		for (JsonElement value : values.getAsJsonArray()) {
			if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) messages.append(' ').append(text(value.getAsString()));
			if (messages.length() >= 400) break;
		}
		return messages.toString();
	}

	private static String text(String value) {
		if (value == null) return "";
		value = value.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").replaceAll("\\s+", " ").trim();
		return value.substring(0, Math.min(600, value.length()));
	}
}
