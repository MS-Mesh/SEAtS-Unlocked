package com.msmesh.seats.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.msmesh.seats.BuildConfig;
import com.msmesh.seats.R;
import com.msmesh.seats.SeatsApp;
import com.msmesh.seats.auth.AuthManager;
import com.msmesh.seats.auth.LoginActivity;
import com.msmesh.seats.data.AuthStore;
import com.msmesh.seats.data.Prefs;
import com.msmesh.seats.data.UserInfo;
import com.msmesh.seats.net.SeatsApi;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Theme picker, tenant diagnostics, cached code count and sign-out. */
public class SettingsActivity extends AppCompatActivity {
	private final ExecutorService executor = Executors.newSingleThreadExecutor();
	private TextView details;
	private TextView email;
	private SeatsApi api;
	private Prefs prefs;

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_settings);
		api = new SeatsApi(this);
		prefs = new Prefs(this);

		ImageButton back = findViewById(R.id.toolbar_back);
		back.setOnClickListener(view -> finish());

		email = findViewById(R.id.settings_email);
		details = findViewById(R.id.settings_details);

		MaterialButtonToggleGroup themeGroup = findViewById(R.id.theme_group);
		int mode = prefs.themeMode();
		themeGroup.check(mode == 1 ? R.id.theme_light : mode == 2 ? R.id.theme_dark : R.id.theme_system);
		themeGroup.addOnButtonCheckedListener((buttonGroup, checkedId, isChecked) -> {
			if (!isChecked) return;
			int next = checkedId == R.id.theme_light ? 1 : checkedId == R.id.theme_dark ? 2 : 0;
			if (next == prefs.themeMode()) return;
			prefs.setThemeMode(next);
			SeatsApp.applyTheme(next);
		});

		findViewById(R.id.preview_button).setOnClickListener(view -> startActivity(new Intent(this, PreviewActivity.class)));
		findViewById(R.id.settings_refresh).setOnClickListener(view -> reload());
		findViewById(R.id.settings_logout).setOnClickListener(view -> {
			prefs.clearPortalData();
			AuthManager.logout(this);
			startActivity(new Intent(this, LoginActivity.class));
			finishAffinity();
		});
		reload();
	}

	/** Loads the user identity immediately and tenant settings in the background. */
	private void reload() {
		UserInfo info = new AuthStore(this).userInfo();
		String displayEmail = info != null && info.email != null ? info.email : prefs.email();
		if ((displayEmail == null || displayEmail.isEmpty()) && info != null) displayEmail = info.preferredUsername;
		email.setText(displayEmail == null || displayEmail.isEmpty() ? "Not signed in" : displayEmail);
		render(null, null);
		executor.execute(() -> {
			Map<String, String> settings = null;
			String error = null;
			try {
				settings = api.getSettings();
			} catch (Exception exception) {
				error = exception.getMessage();
			}
			Map<String, String> result = settings;
			String failure = error;
			runOnUiThread(() -> render(result, failure));
		});
	}

	/** Diagnostics that are useful across tenants: identity, gateway, code cache and version. */
	private void render(Map<String, String> settings, String error) {
		UserInfo info = new AuthStore(this).userInfo();
		StringBuilder summary = new StringBuilder();
		if (info != null) {
			summary.append("Tenant  ·  ").append(nullSafe(info.tenantId)).append('\n');
			summary.append("Student  ·  ").append(nullSafe(info.studentId)).append('\n');
			summary.append("Gateway  ·  ").append(nullSafe(info.mobileGwEndpoint)).append('\n');
		}
		int cached = prefs.portalCodeCount();
		summary.append("Codes  ·  ").append(cached).append(" cached\n");
		long lastSync = prefs.portalLastSync();
		String lastSyncText;
		if (lastSync == 0) lastSyncText = cached > 0 ? "earlier" : "never";
		else lastSyncText = new SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(new Date(lastSync));
		summary.append("Last sync  ·  ").append(lastSyncText).append('\n');
		if (settings != null) {
			summary.append("Cloud verification  ·  ").append(nullSafe(settings.get("QrCloudVerificationEnabled"))).append('\n');
		} else if (error != null) {
			summary.append("Settings  ·  ").append(error).append('\n');
		}
		summary.append("Inject  ·  lecture window time\n");
		summary.append("Version  ·  ").append(BuildConfig.VERSION_NAME);
		details.setText(summary.toString());
		if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show();
	}

	private static String nullSafe(String value) {
		return value == null || value.isEmpty() ? "—" : value;
	}

	@Override
	protected void onDestroy() {
		executor.shutdownNow();
		super.onDestroy();
	}
}