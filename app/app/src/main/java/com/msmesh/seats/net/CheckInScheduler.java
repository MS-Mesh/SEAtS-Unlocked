package com.msmesh.seats.net;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.msmesh.seats.data.AuthStore;
import com.msmesh.seats.data.Event;
import com.msmesh.seats.data.UserInfo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pending check-ins and their last recorded result. A force-stop or reboot cancels alarms. */
public final class CheckInScheduler {
	private static final String FILE = "seats_pending";
	private static final Gson GSON = new Gson();
	private static final Set<Integer> running = new HashSet<>();

	static class Attempt {
		String attemptId;
		String account;
		Event event;
		String code;
		int timetableId;
	}

	public static class SavedResult {
		public String attemptId;
		public String account;
		public String title;
		public long time;
		public CheckInOutcome outcome;
	}

	private CheckInScheduler() {
	}

	public static SharedPreferences preferences(Context context) {
		return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
	}

	/** One minute after startCutoff, or five seconds from now for an already-open window. */
	public static long triggerAt(Event event) {
		long seconds = event.startCutoff > 0 ? event.startCutoff + 60L : System.currentTimeMillis() / 1000L + 60L;
		return Math.max(seconds * 1000L, System.currentTimeMillis() + 5000L);
	}

	public static synchronized void schedule(Context context, Event event, String code) {
		if (event == null || event.timeTableId <= 0 || code == null) return;
		if (running.contains(event.timeTableId)) throw new IllegalStateException("Check-in is already being sent");
		String account = account(context);
		if (account == null) throw new IllegalStateException("Sign in before scheduling a check-in");
		AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
		if (manager == null) throw new IllegalStateException("Scheduling is unavailable");
		String attemptId = UUID.randomUUID().toString();
		JsonObject payload = new JsonObject();
		payload.add("event", GSON.toJsonTree(event));
		payload.addProperty("code", code);
		payload.addProperty("attemptId", attemptId);
		payload.addProperty("account", account);
		if (!preferences(context).edit().putString("pending_" + event.timeTableId, payload.toString()).commit()) {
			throw new IllegalStateException("Could not save the scheduled check-in");
		}

		Intent intent = new Intent(context, CheckInReceiver.class)
				.putExtra(CheckInReceiver.EXTRA_TIMETABLE_ID, event.timeTableId)
				.putExtra(CheckInReceiver.EXTRA_ATTEMPT_ID, attemptId);
		int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
		PendingIntent pending = PendingIntent.getBroadcast(context, event.timeTableId, intent, flags);
		try {
			try {
				manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt(event), pending);
			} catch (SecurityException ignored) {
				manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt(event), pending);
			}
		} catch (RuntimeException error) {
			clear(context, event.timeTableId);
			manager.cancel(pending);
			pending.cancel();
			throw error;
		}
	}

	public static boolean isScheduled(Context context, int timetableId) {
		return timetableId > 0 && payload(context, timetableId) != null;
	}

	public static synchronized void cancel(Context context, int timetableId) {
		if (timetableId <= 0) return;
		if (running.contains(timetableId) || !isScheduled(context, timetableId)) {
			throw new IllegalStateException("The attempt may already have started. Check its saved result.");
		}
		clear(context, timetableId);
		AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
		if (manager == null) return;
		Intent intent = new Intent(context, CheckInReceiver.class).putExtra(CheckInReceiver.EXTRA_TIMETABLE_ID, timetableId);
		PendingIntent pending = PendingIntent.getBroadcast(context, timetableId, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
		manager.cancel(pending);
		pending.cancel();
	}

	/** Remove the sendable payload and retain an unknown result before making any request. */
	static synchronized Attempt claim(Context context, int timetableId, String deliveryId, String account) {
		String raw = payload(context, timetableId);
		if (raw == null || running.contains(timetableId)) return null;
		Attempt attempt;
		try {
			attempt = GSON.fromJson(raw, Attempt.class);
			if (attempt == null) throw new IllegalArgumentException();
		} catch (RuntimeException error) {
			attempt = new Attempt();
			attempt.attemptId = deliveryId;
		}
		if (attempt.attemptId == null ? deliveryId != null : !attempt.attemptId.equals(deliveryId)) return null;
		if (attempt.attemptId == null) attempt.attemptId = UUID.randomUUID().toString();
		if (attempt.account == null) attempt.account = account;
		attempt.timetableId = timetableId;
		SavedResult saved = new SavedResult();
		saved.attemptId = attempt.attemptId;
		saved.account = attempt.account;
		saved.title = attempt.account == null ? "Older scheduled attempt (account unavailable)"
				: attempt.event == null ? "Scheduled event" : attempt.event.displayTitle();
		saved.time = System.currentTimeMillis();
		saved.outcome = CheckInOutcome.unknown("The scheduled attempt has no confirmed result. Check your attendance before sending again.");
		if (!preferences(context).edit().remove("pending_" + timetableId)
				.putString(resultKey(timetableId, attempt.account), GSON.toJson(saved)).commit()) {
			throw new IllegalStateException("Could not record the attempt; no request was sent");
		}
		running.add(timetableId);
		return attempt;
	}

	/** Do not recreate a result removed by cleanup or overwrite a newer attempt. */
	static synchronized void finish(Context context, Attempt attempt, CheckInOutcome outcome) {
		try {
			SavedResult saved = result(context, attempt.timetableId, attempt.account);
			if (saved == null || !attempt.attemptId.equals(saved.attemptId)) return;
			saved.outcome = outcome;
			preferences(context).edit().putString(resultKey(attempt.timetableId, attempt.account), GSON.toJson(saved)).commit();
		} finally {
			running.remove(attempt.timetableId);
		}
	}

	/** The same event cannot be sent manually while its alarm is pending or running. */
	public static synchronized boolean startManual(Context context, int timetableId) {
		if (running.contains(timetableId) || isScheduled(context, timetableId)) return false;
		running.add(timetableId);
		return true;
	}

	public static synchronized void finishManual(int timetableId) {
		running.remove(timetableId);
	}

	public static synchronized boolean isRunning(int timetableId) {
		return running.contains(timetableId);
	}

	public static SavedResult result(Context context, int timetableId, String account) {
		return readResult(preferences(context).getString(resultKey(timetableId, account), null));
	}

	public static List<SavedResult> results(Context context) {
		String account = account(context);
		List<SavedResult> results = new ArrayList<>();
		if (account == null) return results;
		for (Map.Entry<String, ?> entry : preferences(context).getAll().entrySet()) {
			if (!entry.getKey().startsWith("result_") || !(entry.getValue() instanceof String)) continue;
			SavedResult saved = readResult((String) entry.getValue());
			if (saved != null && (saved.account == null || account.equals(saved.account))) results.add(saved);
		}
		results.sort((first, second) -> Long.compare(second.time, first.time));
		return results;
	}

	private static SavedResult readResult(String raw) {
		try {
			SavedResult saved = GSON.fromJson(raw, SavedResult.class);
			return saved != null && saved.outcome != null && saved.outcome.state != null ? saved : null;
		} catch (RuntimeException error) {
			return null;
		}
	}

	/** A stable tenant/account key; do not copy identity claims or tokens into results. */
	public static String account(Context context) {
		UserInfo info = new AuthStore(context).userInfo();
		if (info == null) return null;
		String user = info.studentId;
		if (user == null || user.isEmpty()) user = info.preferredUsername;
		if (user == null || user.isEmpty()) user = info.email;
		if (user == null || user.isEmpty()) return null;
		try {
			String identity = info.tenantId + "\n" + info.mobileGwEndpoint + "\n" + user;
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
			StringBuilder key = new StringBuilder();
			for (byte value : digest) key.append(String.format(Locale.ROOT, "%02x", value & 0xff));
			return key.toString();
		} catch (Exception error) {
			throw new IllegalStateException("Cannot identify the check-in account", error);
		}
	}

	private static String resultKey(int timetableId, String account) {
		return "result_" + account + "_" + timetableId;
	}

	static String payload(Context context, int timetableId) {
		return preferences(context).getString("pending_" + timetableId, null);
	}

	static synchronized void clear(Context context, int timetableId) {
		if (!preferences(context).edit().remove("pending_" + timetableId).commit()) {
			throw new IllegalStateException("Could not remove the scheduled check-in");
		}
	}
}
