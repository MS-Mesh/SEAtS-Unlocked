package com.msmesh.seats.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.msmesh.seats.R;
import com.msmesh.seats.code.QrRenderer;
import com.msmesh.seats.code.SeatsCode;
import com.msmesh.seats.data.Event;
import com.msmesh.seats.data.Prefs;
import com.msmesh.seats.net.CheckInResult;
import com.msmesh.seats.net.CheckInScheduler;
import com.msmesh.seats.net.SeatsApi;
import com.msmesh.seats.util.TimeFormat;
import com.msmesh.seats.util.TimeSync;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Event screen. Two code modes:
 * - seed mode: event.checkinCode is a seed and codes rotate every duration seconds;
 * - portal mode: the static Class Code is fetched from the QrProxy endpoint and cached by timetableId.
 * Inject is immediate inside the window, backdated for a past lecture, and scheduled for a future one.
 */
public class EventActivity extends AppCompatActivity {
	public static final String EXTRA_EVENT = "event";

	private final Handler handler = new Handler(Looper.getMainLooper());
	private final ExecutorService executor = Executors.newSingleThreadExecutor();

	private Event event;
	private SeatsApi api;
	private Prefs prefs;
	private int duration;
	private String currentCode;
	private String portalCode;
	private boolean injecting;
	private boolean seedCheckRunning;
	private long lastSeedCheck;

	private TextView codeView;
	private TextView codeTitle;
	private TextView countdownView;
	private TextView statusView;
	private TextView resultView;
	private TextView noCodeView;
	private TextView noCodeTitle;
	private TextView autoRefreshLabel;
	private ImageView qrView;
	private ProgressBar countdownProgress;
	private MaterialButton injectButton;
	private MaterialButton portalButton;
	private View codeSection;
	private View noCodeCard;
	private View countdownRow;

	/** Receives a code fetched from the portal for this event's timetable id. */
	private final ActivityResultLauncher<Intent> portalLauncher = registerForActivityResult(
			new ActivityResultContracts.StartActivityForResult(), result -> {
				if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
				String code = result.getData().getStringExtra(PortalActivity.EXTRA_CODE);
				if (code == null || !code.matches("\\d{6}")) return;
				portalCode = code;
				prefs.setPortalCode(event.timeTableId, code);
				update();
			});

	private final Runnable ticker = new Runnable() {
		@Override
		public void run() {
			update();
			handler.postDelayed(this, 250);
		}
	};

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		event = new Gson().fromJson(getIntent().getStringExtra(EXTRA_EVENT), Event.class);
		if (event == null) {
			finish();
			return;
		}
		setContentView(R.layout.activity_event);
		api = new SeatsApi(this);
		prefs = new Prefs(this);
		duration = prefs.duration();
		portalCode = prefs.portalCode(event.timeTableId);
		if (!portalCode.matches("\\d{6}")) portalCode = "";

		ImageButton back = findViewById(R.id.toolbar_back);
		back.setOnClickListener(view -> finish());

		codeView = findViewById(R.id.code);
		codeTitle = findViewById(R.id.code_title);
		countdownView = findViewById(R.id.countdown);
		statusView = findViewById(R.id.event_status);
		resultView = findViewById(R.id.result_message);
		noCodeView = findViewById(R.id.no_code);
		noCodeTitle = findViewById(R.id.no_code_title);
		autoRefreshLabel = findViewById(R.id.auto_refresh_label);
		qrView = findViewById(R.id.qr);
		countdownProgress = findViewById(R.id.countdown_progress);
		countdownRow = findViewById(R.id.countdown_row);
		injectButton = findViewById(R.id.inject_button);
		portalButton = findViewById(R.id.portal_button);
		codeSection = findViewById(R.id.code_section);
		noCodeCard = findViewById(R.id.no_code_card);

		updateHeader();
		findViewById(R.id.copy_button).setOnClickListener(view -> copyCode());
		injectButton.setOnClickListener(view -> inject());
		portalButton.setOnClickListener(view -> openPortal());

		executor.execute(() -> {
			int serverDuration = api.durationSeconds();
			runOnUiThread(() -> duration = serverDuration);
		});
		refreshDetail();
		handler.post(ticker);
	}

	/** Title, lecture type, room, lecturer and local time range. */
	private void updateHeader() {
		((TextView) findViewById(R.id.event_title)).setText(event.displayTitle());
		String type = event.lessonTypeDescription == null ? "" : event.lessonTypeDescription;
		String room = event.roomName == null ? "" : event.roomName;
		String lecturer = event.lecturer == null ? "" : event.lecturer;
		StringBuilder subtitle = new StringBuilder();
		if (!type.isEmpty()) subtitle.append(type);
		if (!room.isEmpty()) subtitle.append(subtitle.length() > 0 ? " · " : "").append(room);
		if (!lecturer.isEmpty()) subtitle.append(subtitle.length() > 0 ? " · " : "").append(lecturer);
		((TextView) findViewById(R.id.event_subtitle)).setText(subtitle.toString());
		((TextView) findViewById(R.id.event_time)).setText(TimeFormat.dayAndRange(event.start, event.end));
	}

	/** Pulls the fresh event detail; used on open and by the seed poller. */
	private void refreshDetail() {
		String eventType = event.eventType == null ? "ScheduledClass" : event.eventType;
		executor.execute(() -> {
			try {
				Event fresh = api.getEvent(eventType, event.eventId);
				if (fresh != null) {
					runOnUiThread(() -> {
						merge(fresh);
						updateHeader();
						update();
					});
				}
			} catch (Exception ignored) {
			}
		});
	}

	/** Copies non-empty fields from the detail response into the current event object. */
	private void merge(Event fresh) {
		if (fresh == null) return;
		if (fresh.checkinCode != null && !fresh.checkinCode.isEmpty()) event.checkinCode = fresh.checkinCode;
		if (fresh.checkinOptions != null && !fresh.checkinOptions.isEmpty()) event.checkinOptions = fresh.checkinOptions;
		if (fresh.moduleName != null && !fresh.moduleName.isEmpty()) event.moduleName = fresh.moduleName;
		if (fresh.moduleCode != null && !fresh.moduleCode.isEmpty()) event.moduleCode = fresh.moduleCode;
		if (fresh.courseCode != null && !fresh.courseCode.isEmpty()) event.courseCode = fresh.courseCode;
		if (fresh.lecturer != null && !fresh.lecturer.isEmpty()) event.lecturer = fresh.lecturer;
		if (fresh.title != null && !fresh.title.isEmpty()) event.title = fresh.title;
		if (fresh.roomName != null && !fresh.roomName.isEmpty()) event.roomName = fresh.roomName;
		if (fresh.timeTableId > 0) event.timeTableId = fresh.timeTableId;
		if (fresh.studentScheduleId > 0) event.studentScheduleId = fresh.studentScheduleId;
		if (fresh.swipeInDateTime > 0) event.swipeInDateTime = fresh.swipeInDateTime;
		if (fresh.attendanceStatusId != null) event.attendanceStatusId = fresh.attendanceStatusId;
		if (fresh.start > 0) event.start = fresh.start;
		if (fresh.end > 0) event.end = fresh.end;
		if (fresh.startCutoff > 0) event.startCutoff = fresh.startCutoff;
		if (fresh.endCutoff > 0) event.endCutoff = fresh.endCutoff;
		if (fresh.rooms != null && !fresh.rooms.isEmpty()) event.rooms = fresh.rooms;
	}

	private boolean openForCheckIn(long now) {
		return now >= event.startCutoff && now <= event.endCutoff;
	}

	/** Re-renders the code card, status chip and inject button. Called four times per second. */
	private void update() {
		long now = TimeSync.nowSeconds();
		updateStatus(now);
		boolean hasSeed = event.hasLiveCode();
		boolean hasPortalCode = portalCode.matches("\\d{6}");
		boolean open = openForCheckIn(now);

		if (!hasSeed && !hasPortalCode) {
			codeSection.setVisibility(View.GONE);
			noCodeCard.setVisibility(View.VISIBLE);
			portalButton.setVisibility(View.VISIBLE);
			noCodeTitle.setText("No live code yet");
			if (now < event.startCutoff) {
				noCodeView.setText("Check-in opens at " + TimeFormat.local(event.startCutoff) + ". You can already read the class code from your portal.");
			} else if (now > event.endCutoff) {
				noCodeView.setText("Check-in closed at " + TimeFormat.local(event.endCutoff) + ". You can still read the class code from your portal.");
			} else {
				noCodeView.setText("Read the class code from your own SEAtS portal session — no screen scanning needed.");
			}
			pollSeed(now);
			return;
		}

		if (hasSeed && !open) {
			codeSection.setVisibility(View.GONE);
			noCodeCard.setVisibility(View.VISIBLE);
			portalButton.setVisibility(View.GONE);
			noCodeTitle.setText("No live code yet");
			if (now < event.startCutoff) noCodeView.setText("Check-in opens at " + TimeFormat.local(event.startCutoff) + ".");
			else noCodeView.setText("Check-in closed at " + TimeFormat.local(event.endCutoff) + ".");
			pollSeed(now);
			return;
		}

		codeSection.setVisibility(View.VISIBLE);
		noCodeCard.setVisibility(View.GONE);
		if (hasSeed) {
			codeTitle.setText("Auto-refreshing QR code");
			countdownRow.setVisibility(View.VISIBLE);
			countdownProgress.setVisibility(View.VISIBLE);
			autoRefreshLabel.setVisibility(View.VISIBLE);
			setCode(SeatsCode.generateCode(event.checkinCode, event.startCutoff, now, duration));
			long left = SeatsCode.secondsUntilNext(event.checkinCode, event.startCutoff, now, duration);
			countdownProgress.setProgress((int) Math.max(0, Math.min(1000, left * 1000 / Math.max(1, duration))));
			countdownView.setText(String.format(Locale.US, "%02d:%02d", left / 60, left % 60));
			autoRefreshLabel.setText("Auto-updates every " + duration + "s");
		} else {
			codeTitle.setText("Live check-in code");
			countdownRow.setVisibility(View.GONE);
			countdownProgress.setVisibility(View.GONE);
			autoRefreshLabel.setVisibility(View.VISIBLE);
			autoRefreshLabel.setText("Valid for this class · read from your portal");
			setCode(portalCode);
		}
		injectButton.setEnabled(!injecting);
		injectButton.setText(CheckInScheduler.isScheduled(this, event.timeTableId) ? "Cancel scheduled check-in" : "Inject to Seats");
	}

	/** Updates the QR/code only when the value changed to avoid re-encoding on every tick. */
	private void setCode(String code) {
		if (code == null || code.isEmpty() || code.equals(currentCode)) return;
		currentCode = code;
		codeView.setText(code);
		qrView.setImageBitmap(QrRenderer.render(SeatsCode.qrUrl(code), 700));
	}

	/** Seed tenants may expose checkinCode only closer to the lecture; poll the detail during the window. */
	private void pollSeed(long now) {
		if (seedCheckRunning || now < event.startCutoff - 300 || now > event.endCutoff || now - lastSeedCheck < 5) return;
		lastSeedCheck = now;
		seedCheckRunning = true;
		String eventType = event.eventType == null ? "ScheduledClass" : event.eventType;
		executor.execute(() -> {
			try {
				Event fresh = api.getEvent(eventType, event.eventId);
				if (fresh != null) {
					runOnUiThread(() -> {
						merge(fresh);
						updateHeader();
						update();
					});
				}
			} catch (Exception ignored) {
			} finally {
				seedCheckRunning = false;
			}
		});
	}

	/** Maps attendanceStatusId to the same labels the official app uses. */
	private void updateStatus(long now) {
		int attendance = event.attendanceStatusId == null ? 0 : event.attendanceStatusId;
		if (attendance == 3 || attendance == 7 || (attendance == 0 && event.swipeInDateTime > 0)) {
			chip(statusView, "ATTENDED", R.color.green_bg, R.color.green_text);
		} else if (attendance == 2) {
			chip(statusView, "LATE", R.color.orange_bg, R.color.orange_text);
		} else if (attendance == 1) {
			chip(statusView, "ABSENT", R.color.red_bg, R.color.red_text);
		} else if (now < event.startCutoff) {
			chip(statusView, "SCHEDULED", R.color.gray_bg, R.color.gray_text);
		} else if (now <= event.endCutoff) {
			chip(statusView, "OPEN", R.color.blue_bg, R.color.blue_text);
		} else {
			chip(statusView, "CLOSED", R.color.gray_bg, R.color.text_tertiary);
		}
	}

	private void copyCode() {
		if (currentCode == null) return;
		ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
		clipboard.setPrimaryClip(ClipData.newPlainText("Seats code", currentCode));
		Toast.makeText(this, "Code copied", Toast.LENGTH_SHORT).show();
	}

	private void openPortal() {
		if (event.timeTableId <= 0) {
			Toast.makeText(this, "This event has no timetable id", Toast.LENGTH_SHORT).show();
			return;
		}
		Intent intent = new Intent(this, PortalActivity.class);
		intent.putExtra(PortalActivity.EXTRA_TIMETABLE_ID, event.timeTableId);
		portalLauncher.launch(intent);
	}

	/** Immediate inside the window, scheduled in the future, backdated for a past lecture. */
	private void inject() {
		if (currentCode == null || injecting) return;
		String code = currentCode;
		if (CheckInScheduler.isScheduled(this, event.timeTableId)) {
			CheckInScheduler.cancel(this, event.timeTableId);
			resultView.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
			resultView.setText("Scheduled check-in cancelled.");
			update();
			return;
		}
		long now = TimeSync.nowSeconds();
		if (event.startCutoff > 0 && now < event.startCutoff) {
			CheckInScheduler.schedule(this, event, code);
			resultView.setTextColor(ContextCompat.getColor(this, R.color.green_text));
			resultView.setText("Check-in scheduled for " + TimeFormat.local(event.startCutoff + 60) + ". Keep the phone online.");
			update();
			return;
		}
		injecting = true;
		injectButton.setEnabled(false);
		resultView.setText("Sending check-in…");
		resultView.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
		executor.execute(() -> {
			try {
				CheckInResult result = api.checkIn(event, code);
				runOnUiThread(() -> {
					injecting = false;
					injectButton.setEnabled(true);
					CheckInOutcome outcome = readOutcome(result);
					if (outcome.applied) {
						resultView.setTextColor(ContextCompat.getColor(this, R.color.green_text));
						String windowNote = result.usedWindowTime ? " · sent with the lecture window time" : "";
						resultView.setText("Check-in accepted" + windowNote + suffix(outcome.messages));
						event.swipeInDateTime = outcome.swipeIn;
						if (outcome.statusId > 0) event.attendanceStatusId = outcome.statusId;
						updateStatus(TimeSync.nowSeconds());
					} else if (outcome.queued) {
						resultView.setTextColor(ContextCompat.getColor(this, R.color.orange_text));
						resultView.setText("Code accepted, but attendance is not applied yet. The check-in window is "
								+ TimeFormat.local(event.startCutoff) + " – " + TimeFormat.local(event.endCutoff) + ".");
					} else {
						resultView.setTextColor(ContextCompat.getColor(this, R.color.orange_text));
						String message = outcome.messages.isEmpty() ? result.message() : String.join(" ", outcome.messages);
						resultView.setText("Server: " + message);
					}
				});
			} catch (Exception error) {
				runOnUiThread(() -> {
					injecting = false;
					injectButton.setEnabled(true);
					resultView.setTextColor(ContextCompat.getColor(this, R.color.red_text));
					resultView.setText(error.getMessage());
				});
			}
		});
	}

	private static String suffix(List<String> messages) {
		return messages == null || messages.isEmpty() ? "" : ": " + String.join(" ", messages);
	}

	/** isValid only means the code is correct; applied means the schedule actually got a swipe timestamp. */
	private CheckInOutcome readOutcome(CheckInResult result) {
		CheckInOutcome outcome = new CheckInOutcome();
		outcome.messages = result.messages;
		try {
			JsonObject response = JsonParser.parseString(result.raw).getAsJsonObject().getAsJsonObject("checkInResponse");
			if (response == null) return outcome;
			JsonElement success = response.get("success");
			boolean responseSuccess = success != null && success.isJsonPrimitive() && success.getAsBoolean();
			JsonObject schedule = response.getAsJsonObject("studentSchedule");
			JsonElement swipe = schedule == null ? null : schedule.get("swipeInDateTime");
			boolean hasSwipe = swipe != null && !swipe.isJsonNull();
			int statusId = 0;
			if (schedule != null && schedule.has("attendanceStatusId")) {
				try {
					statusId = schedule.get("attendanceStatusId").getAsInt();
				} catch (Exception ignored) {
				}
			}
			outcome.applied = hasSwipe || statusId == 2 || statusId == 3 || statusId == 7;
			outcome.queued = !outcome.applied && responseSuccess;
			outcome.statusId = statusId;
			if (hasSwipe) outcome.swipeIn = (int) TimeSync.nowSeconds();
		} catch (Exception ignored) {
		}
		return outcome;
	}

	private static final class CheckInOutcome {
		boolean applied;
		boolean queued;
		int swipeIn;
		int statusId;
		List<String> messages;
	}

	private void chip(TextView view, String text, int backgroundRes, int foregroundRes) {
		view.setText(text);
		GradientDrawable drawable = new GradientDrawable();
		drawable.setCornerRadius(getResources().getDisplayMetrics().density * 20f);
		drawable.setColor(ContextCompat.getColor(this, backgroundRes));
		view.setBackground(drawable);
		view.setTextColor(ContextCompat.getColor(this, foregroundRes));
	}

	@Override
	protected void onDestroy() {
		handler.removeCallbacksAndMessages(null);
		executor.shutdownNow();
		super.onDestroy();
	}
}