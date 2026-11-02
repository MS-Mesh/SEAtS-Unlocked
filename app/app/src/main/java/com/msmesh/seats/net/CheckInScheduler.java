package com.msmesh.seats.net;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.msmesh.seats.data.Event;

/**
 * Schedules a check-in for the moment the lecture check-in window opens.
 * The pending event + code live in a private SharedPreferences file and are read by CheckInReceiver,
 * so the app does not need to stay open. A force-stop or reboot cancels the alarm.
 */
public final class CheckInScheduler {
	private static final String FILE = "seats_pending";

	private CheckInScheduler() {
	}

	/** One minute after startCutoff, or five seconds from now for an already-open window. */
	public static long triggerAt(Event event) {
		long seconds = event.startCutoff > 0 ? event.startCutoff + 60L : System.currentTimeMillis() / 1000L + 60L;
		return Math.max(seconds * 1000L, System.currentTimeMillis() + 5000L);
	}

	public static void schedule(Context context, Event event, String code) {
		if (event == null || event.timeTableId <= 0 || code == null) return;
		JsonObject payload = new JsonObject();
		payload.add("event", new Gson().toJsonTree(event));
		payload.addProperty("code", code);
		context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("pending_" + event.timeTableId, payload.toString()).apply();

		AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
		if (manager == null) return;
		Intent intent = new Intent(context, CheckInReceiver.class).putExtra(CheckInReceiver.EXTRA_TIMETABLE_ID, event.timeTableId);
		int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
		PendingIntent pending = PendingIntent.getBroadcast(context, event.timeTableId, intent, flags);
		long trigger = triggerAt(event);
		try {
			if (Build.VERSION.SDK_INT >= 23) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending);
			else manager.setExact(AlarmManager.RTC_WAKEUP, trigger, pending);
		} catch (SecurityException ignored) {
			if (Build.VERSION.SDK_INT >= 23) manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending);
			else manager.set(AlarmManager.RTC_WAKEUP, trigger, pending);
		}
	}

	public static boolean isScheduled(Context context, int timetableId) {
		return timetableId > 0 && payload(context, timetableId) != null;
	}

	public static void cancel(Context context, int timetableId) {
		if (timetableId <= 0) return;
		clear(context, timetableId);
		AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
		if (manager == null) return;
		Intent intent = new Intent(context, CheckInReceiver.class).putExtra(CheckInReceiver.EXTRA_TIMETABLE_ID, timetableId);
		int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
		PendingIntent pending = PendingIntent.getBroadcast(context, timetableId, intent, flags);
		manager.cancel(pending);
		pending.cancel();
	}

	static String payload(Context context, int timetableId) {
		return context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("pending_" + timetableId, null);
	}

	static void clear(Context context, int timetableId) {
		context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove("pending_" + timetableId).apply();
	}
}