package com.msmesh.seats.code;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Request fingerprint used as ?fp= on student check-in calls.
 * C# reference: Seats.Gateway.Mobile.Client.StudentV2ApiClient.
 * The DTO properties are joined in a fixed order, HMAC-SHA256 is applied with a key derived from
 * the tenant MobilePhone, and the first 8 digest bytes are read big-endian.
 */
public final class Fingerprint {
	/** Fixed salt used by the official client. */
	private static final byte[] SALT = {0x0D, (byte) 0x92, (byte) 0xEC, 0x24, (byte) 0xCE, (byte) 0xDD, (byte) 0xE5, 0x05};

	private Fingerprint() {
	}

	/** CheckInWithCode field order: Timestamp|TimetableId|StudentScheduleId|CheckInReason|CheckInInput|MobileId|StudentId. */
	public static String compute(String timestamp, int timetableId, long studentScheduleId, String reason,
			String input, String mobileId, String studentId, String mobilePhone) {
		StringBuilder message = new StringBuilder();
		append(message, timestamp);
		append(message, Integer.toString(timetableId));
		append(message, Long.toString(studentScheduleId));
		append(message, reason);
		append(message, input);
		append(message, mobileId);
		append(message, studentId);
		return fingerprint(message.toString(), mobilePhone);
	}

	/** HMAC-SHA256(key, message), key = SHA256(hex(MobilePhone) || salt)[0..8], fp = big-endian uint64. */
	private static String fingerprint(String message, String mobilePhone) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(keyFor(mobilePhone), "HmacSHA256"));
			byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
			long value = 0L;
			for (int index = 0; index < 8; index++) value = (value << 8) | (digest[index] & 0xFFL);
			return Long.toUnsignedString(value);
		} catch (Exception error) {
			throw new IllegalStateException("Cannot compute fingerprint", error);
		}
	}

	private static void append(StringBuilder builder, String value) {
		if (value == null || value.isEmpty()) return;
		if (builder.length() > 0) builder.append('|');
		builder.append(value);
	}

	private static byte[] keyFor(String mobilePhone) {
		if (mobilePhone == null || mobilePhone.isEmpty()) return new byte[8];
		byte[] phone = hexDecode(mobilePhone);
		if (phone == null) return new byte[8];
		try {
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			sha.update(phone);
			sha.update(SALT);
			return Arrays.copyOfRange(sha.digest(), 0, 8);
		} catch (Exception error) {
			return new byte[8];
		}
	}

	private static byte[] hexDecode(String value) {
		if (value.length() % 2 != 0) return null;
		byte[] decoded = new byte[value.length() / 2];
		for (int index = 0; index < decoded.length; index++) {
			int high = Character.digit(value.charAt(index * 2), 16);
			int low = Character.digit(value.charAt(index * 2 + 1), 16);
			if (high < 0 || low < 0) return null;
			decoded[index] = (byte) ((high << 4) | low);
		}
		return decoded;
	}
}