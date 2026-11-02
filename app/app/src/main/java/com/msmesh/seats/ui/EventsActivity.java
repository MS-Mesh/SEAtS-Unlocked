package com.msmesh.seats.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.msmesh.seats.R;
import com.msmesh.seats.auth.LoginActivity;
import com.msmesh.seats.data.AuthStore;
import com.msmesh.seats.data.Event;
import com.msmesh.seats.data.Prefs;
import com.msmesh.seats.data.UserInfo;
import com.msmesh.seats.net.SeatsApi;
import com.msmesh.seats.util.TimeSync;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Schedule screen: 15-day date strip, personal events, cached class codes and bulk portal sync. */
public class EventsActivity extends AppCompatActivity implements EventAdapter.OnEventClick, DateAdapter.OnDateClick {
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private final List<Event> allEvents = new ArrayList<>();
	private SeatsApi api;
	private Prefs prefs;
	private EventAdapter adapter;
	private DateAdapter dateAdapter;
	private SwipeRefreshLayout refresh;
	private RecyclerView list;
	private TextView empty;
	private TextView subtitle;
	private ProgressBar progress;
	private boolean loading;
	private boolean firstResume = true;
	private LocalDate selectedDate = LocalDate.now();

	/** Receives the map of timetableId -> code from PortalActivity. */
	private final ActivityResultLauncher<Intent> portalLauncher = registerForActivityResult(
			new ActivityResultContracts.StartActivityForResult(), result -> {
				if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
				String json = result.getData().getStringExtra(PortalActivity.EXTRA_CODES_JSON);
				if (json == null) return;
				int count = 0;
				try {
					JsonObject codes = JsonParser.parseString(json).getAsJsonObject();
					for (String key : codes.keySet()) if (codes.get(key).getAsString().matches("\\d{6}")) count++;
				} catch (Exception ignored) {
				}
				applyFilter();
				if (count > 0) Toast.makeText(this, count == 1 ? "Code updated" : count + " codes updated", Toast.LENGTH_SHORT).show();
			});

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_events);
		api = new SeatsApi(this);
		prefs = new Prefs(this);

		ImageButton refreshButton = findViewById(R.id.refresh_button);
		ImageButton settingsButton = findViewById(R.id.settings_button);
		refreshButton.setOnClickListener(view -> load());
		settingsButton.setOnClickListener(view -> startActivity(new Intent(this, SettingsActivity.class)));
		findViewById(R.id.sync_codes).setOnClickListener(view -> syncAllCodes());

		subtitle = findViewById(R.id.schedule_subtitle);
		UserInfo info = new AuthStore(this).userInfo();
		String email = info != null && info.email != null ? info.email : prefs.email();
		if (email != null && email.contains("@")) {
			String domain = email.substring(email.indexOf('@') + 1);
			subtitle.setText(domain.endsWith("nottingham.ac.uk") ? "University of Nottingham" : domain);
		} else {
			subtitle.setText("University schedule");
		}

		RecyclerView dates = findViewById(R.id.dates);
		dates.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
		dateAdapter = new DateAdapter(this);
		dates.setAdapter(dateAdapter);

		refresh = findViewById(R.id.refresh);
		list = findViewById(R.id.list);
		empty = findViewById(R.id.empty);
		progress = findViewById(R.id.progress);
		adapter = new EventAdapter(prefs, this);
		list.setLayoutManager(new LinearLayoutManager(this));
		list.setAdapter(adapter);
		refresh.setOnRefreshListener(this::load);
		dateAdapter.submit(dateStrip(), selectedDate);
		load();
	}

	/** Loads the next 21 days and caches tenant settings (QR interval, MobilePhone). */
	private void load() {
		if (loading) return;
		loading = true;
		if (adapter.getItemCount() == 0) progress.setVisibility(View.VISIBLE);
		long now = TimeSync.nowSeconds();
		executor.execute(() -> {
			try {
				List<Event> events = api.getEvents(now - 86400L, now + 21 * 86400L);
				try {
					api.getSettings();
				} catch (Exception ignored) {
				}
				Collections.sort(events, (first, second) -> Integer.compare(first.start, second.start));
				runOnUiThread(() -> {
					loading = false;
					progress.setVisibility(View.GONE);
					refresh.setRefreshing(false);
					allEvents.clear();
					allEvents.addAll(events);
					dateAdapter.submit(dateStrip(), selectedDate);
					applyFilter();
					maybeAutoSyncCodes();
				});
			} catch (Exception error) {
				runOnUiThread(() -> {
					loading = false;
					progress.setVisibility(View.GONE);
					refresh.setRefreshing(false);
					Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
					if (isAuthFailure(error)) {
						new AuthStore(this).clear();
						startActivity(new Intent(this, LoginActivity.class));
						finish();
					}
				});
			}
		});
	}

	private List<LocalDate> dateStrip() {
		List<LocalDate> dates = new ArrayList<>();
		LocalDate start = LocalDate.now().minusDays(1);
		for (int offset = 0; offset < 15; offset++) dates.add(start.plusDays(offset));
		return dates;
	}

	private void applyFilter() {
		List<Event> filtered = new ArrayList<>();
		for (Event event : allEvents) {
			if (eventDate(event).equals(selectedDate)) filtered.add(event);
		}
		adapter.submit(filtered);
		empty.setText("No events on this day");
		empty.setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
	}

	private LocalDate eventDate(Event event) {
		return Instant.ofEpochSecond(event.start).atZone(ZoneId.systemDefault()).toLocalDate();
	}

	@Override
	public void onDate(LocalDate date) {
		selectedDate = date;
		applyFilter();
	}

	@Override
	public void onEvent(Event event) {
		Intent intent = new Intent(this, EventActivity.class);
		intent.putExtra(EventActivity.EXTRA_EVENT, new Gson().toJson(event));
		startActivity(intent);
	}

	/** First successful load without cached codes opens the portal once to bulk-sync everything. */
	private void maybeAutoSyncCodes() {
		if (prefs.portalAutoPrompted()) return;
		long now = TimeSync.nowSeconds();
		boolean hasUpcoming = false;
		boolean missing = false;
		for (Event event : allEvents) {
			if (event.endCutoff >= now - 3600) {
				hasUpcoming = true;
				if (!prefs.portalCode(event.timeTableId).matches("\\d{6}")) {
					missing = true;
					break;
				}
			}
		}
		if (!hasUpcoming || !missing) return;
		prefs.setPortalAutoPrompted(true);
		syncAllCodes();
	}

	/** Opens PortalActivity once and asks it to read every upcoming timetable code. */
	private void syncAllCodes() {
		long now = TimeSync.nowSeconds();
		LinkedHashSet<Integer> timetableIds = new LinkedHashSet<>();
		for (Event event : allEvents) {
			if (event.timeTableId > 0 && event.endCutoff >= now - 3600) timetableIds.add(event.timeTableId);
		}
		if (timetableIds.isEmpty()) {
			Toast.makeText(this, "No upcoming classes to sync", Toast.LENGTH_SHORT).show();
			return;
		}
		int[] array = new int[timetableIds.size()];
		int index = 0;
		for (int timetableId : timetableIds) array[index++] = timetableId;
		Intent intent = new Intent(this, PortalActivity.class);
		intent.putExtra(PortalActivity.EXTRA_TIMETABLE_IDS, array);
		portalLauncher.launch(intent);
	}

	private boolean isAuthFailure(Exception error) {
		String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase();
		return message.contains("401") || message.contains("sign in") || message.contains("session");
	}

	/** Refresh the schedule when returning from an event so attendance chips update. */
	@Override
	protected void onResume() {
		super.onResume();
		if (firstResume) {
			firstResume = false;
			return;
		}
		if (!loading) load();
	}

	@Override
	protected void onDestroy() {
		executor.shutdownNow();
		super.onDestroy();
	}
}