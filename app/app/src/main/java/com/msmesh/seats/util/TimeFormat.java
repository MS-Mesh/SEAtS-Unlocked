package com.msmesh.seats.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Local-time formatting shared by the schedule and the event screen. */
public final class TimeFormat {
	private TimeFormat() {
	}

	/** Example: "Thu 22 Oct · 13:00 – 14:00". */
	public static String dayAndRange(int start, int end) {
		SimpleDateFormat day = new SimpleDateFormat("EEE d MMM", Locale.getDefault());
		SimpleDateFormat clock = new SimpleDateFormat("HH:mm", Locale.getDefault());
		return day.format(new Date(start * 1000L)) + " · " + clock.format(new Date(start * 1000L)) + " – " + clock.format(new Date(end * 1000L));
	}

	/** Example: "Thu 22 Oct, 12:30". */
	public static String local(int unixSeconds) {
		SimpleDateFormat format = new SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault());
		return format.format(new Date(unixSeconds * 1000L));
	}
}