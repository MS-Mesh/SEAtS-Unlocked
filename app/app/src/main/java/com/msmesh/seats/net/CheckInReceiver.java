package com.msmesh.seats.net;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.msmesh.seats.data.Event;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Manifest-registered receiver that delivers a pending check-in when the alarm fires. */
public class CheckInReceiver extends BroadcastReceiver {
	public static final String EXTRA_TIMETABLE_ID = "timetable_id";

	@Override
	public void onReceive(Context context, Intent intent) {
		int timetableId = intent.getIntExtra(EXTRA_TIMETABLE_ID, 0);
		String raw = CheckInScheduler.payload(context, timetableId);
		if (raw == null) return;

		PendingResult pendingResult = goAsync();
		ExecutorService executor = Executors.newSingleThreadExecutor();
		Context app = context.getApplicationContext();
		executor.execute(() -> {
			try {
				JsonObject payload = JsonParser.parseString(raw).getAsJsonObject();
				Event event = new Gson().fromJson(payload.get("event"), Event.class);
				new SeatsApi(app).checkIn(event, payload.get("code").getAsString());
				CheckInScheduler.clear(app, timetableId);
			} catch (Exception ignored) {
			} finally {
				pendingResult.finish();
				executor.shutdown();
			}
		});
	}
}