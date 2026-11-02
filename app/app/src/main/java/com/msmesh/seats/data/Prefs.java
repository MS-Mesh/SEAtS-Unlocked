package com.msmesh.seats.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

/** Plain (non-secret) app preferences: email, theme, QR interval and cached portal class codes. */
public class Prefs {
	private static final String FILE = "seats_prefs";
	private final SharedPreferences prefs;

	public Prefs(Context context) {
		prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
	}

	/** Stable per-install id sent as MobileId in check-in requests. */
	public String deviceId() {
		String identifier = prefs.getString("device_id", null);
		if (identifier == null) {
			identifier = UUID.randomUUID().toString();
			prefs.edit().putString("device_id", identifier).apply();
		}
		return identifier;
	}

	/** AutorefreshingQrDurationSecs, cached from the tenant settings. */
	public int duration() {
		return prefs.getInt("duration", 30);
	}

	public void setDuration(int seconds) {
		prefs.edit().putInt("duration", seconds > 0 ? seconds : 30).apply();
	}

	public String email() {
		return prefs.getString("email", "");
	}

	public void setEmail(String email) {
		prefs.edit().putString("email", email == null ? "" : email).apply();
	}

	public int themeMode() {
		return prefs.getInt("theme_mode", 0);
	}

	public void setThemeMode(int mode) {
		prefs.edit().putInt("theme_mode", mode).apply();
	}

	/** Class code fetched from the portal for one timetable id. */
	public String portalCode(int timetableId) {
		String value = prefs.getString("portal_code_" + timetableId, "");
		return value == null ? "" : value;
	}

	public void setPortalCode(int timetableId, String code) {
		SharedPreferences.Editor editor = prefs.edit();
		editor.putString("portal_code_" + timetableId, code == null ? "" : code);
		if (code != null && code.matches("\\d{6}") && prefs.getLong("portal_last_sync", 0) == 0) editor.putLong("portal_last_sync", System.currentTimeMillis());
		editor.apply();
	}

	/** Tenant web portal; override this when adapting the app for another university. */
	public String portalBase() {
		return prefs.getString("portal_base", "https://uon.seats.cloud");
	}

	public void setPortalBase(String value) {
		String base = value == null ? "" : value.trim();
		while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
		if (!base.isEmpty()) prefs.edit().putString("portal_base", base).apply();
	}

	public boolean portalAutoPrompted() {
		return prefs.getBoolean("portal_auto_prompted", false);
	}

	public void setPortalAutoPrompted(boolean value) {
		prefs.edit().putBoolean("portal_auto_prompted", value).apply();
	}

	/** Clears cached codes and the one-time auto-sync flag, used on sign-out. */
	public void clearPortalData() {
		SharedPreferences.Editor editor = prefs.edit();
		for (String key : prefs.getAll().keySet()) if (key.startsWith("portal_code_")) editor.remove(key);
		editor.remove("portal_auto_prompted");
		editor.remove("portal_last_sync");
		editor.apply();
	}

	public int portalCodeCount() {
		int count = 0;
		for (String key : prefs.getAll().keySet()) if (key.startsWith("portal_code_")) count++;
		return count;
	}

	public long portalLastSync() {
		return prefs.getLong("portal_last_sync", 0);
	}

	public void setPortalLastSync(long millis) {
		prefs.edit().putLong("portal_last_sync", millis).apply();
	}
}