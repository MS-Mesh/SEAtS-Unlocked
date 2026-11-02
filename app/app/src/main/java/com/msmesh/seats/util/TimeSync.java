package com.msmesh.seats.util;

/**
 * Keeps the device clock aligned with the SEAtS server clock.
 * The offset is refreshed from /api/v1/app/clock before codes and check-ins are used.
 */
public final class TimeSync {
	private static volatile long offsetMillis = 0L;

	private TimeSync() {
	}

	public static void update(long serverTimeMillis) {
		if (serverTimeMillis > 0) offsetMillis = serverTimeMillis - System.currentTimeMillis();
	}

	public static long nowMillis() {
		return System.currentTimeMillis() + offsetMillis;
	}

	public static long nowSeconds() {
		return nowMillis() / 1000L;
	}

	public static long offsetMillis() {
		return offsetMillis;
	}
}