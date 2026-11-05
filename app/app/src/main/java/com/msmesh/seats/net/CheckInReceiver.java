package com.msmesh.seats.net;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Manifest-registered receiver that delivers a pending check-in when the alarm fires. */
public class CheckInReceiver extends BroadcastReceiver {
	public static final String EXTRA_TIMETABLE_ID = "timetable_id";
	public static final String EXTRA_ATTEMPT_ID = "attempt_id";

	@Override
	public void onReceive(Context context, Intent intent) {
		if (intent == null) return;
		int timetableId = intent.getIntExtra(EXTRA_TIMETABLE_ID, 0);
		if (timetableId <= 0) return;
		String deliveryId = intent.getStringExtra(EXTRA_ATTEMPT_ID);
		PendingResult pendingResult = goAsync();
		ExecutorService executor = Executors.newSingleThreadExecutor();
		Context app = context.getApplicationContext();
		executor.execute(() -> {
			try {
				String account = null;
				try {
					account = CheckInScheduler.account(app);
				} catch (RuntimeException ignored) {
					// Retain a refusal for the saved owner if the account store cannot be read.
				}
				CheckInScheduler.Attempt attempt = CheckInScheduler.claim(app, timetableId, deliveryId, account);
				if (attempt == null) return;
				CheckInOutcome outcome;
				try {
					if (account == null || !account.equals(attempt.account)) {
						outcome = CheckInOutcome.unknown("The scheduled account is not signed in. No request was sent.");
						outcome.state = CheckInOutcome.State.REJECTED;
					} else {
						if (attempt.event == null || attempt.event.timeTableId != timetableId) throw new IllegalArgumentException();
						outcome = CheckInOutcome.read(new SeatsApi(app).checkIn(attempt.event, attempt.code));
					}
				} catch (Exception error) {
					outcome = CheckInOutcome.failed(error);
				}
				CheckInScheduler.finish(app, attempt, outcome);
			} catch (RuntimeException error) {
				Log.w("CheckInReceiver", "Could not record the scheduled result; no automatic retry");
			} finally {
				pendingResult.finish();
				executor.shutdown();
			}
		});
	}
}
