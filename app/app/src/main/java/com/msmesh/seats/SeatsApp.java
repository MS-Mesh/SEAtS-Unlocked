package com.msmesh.seats;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

import com.msmesh.seats.data.Prefs;

/** Applies the saved light/dark/system theme before any activity is created. */
public class SeatsApp extends Application {
	@Override
	public void onCreate() {
		super.onCreate();
		applyTheme(new Prefs(this).themeMode());
	}

	public static void applyTheme(int mode) {
		if (mode == 1) AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
		else if (mode == 2) AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
		else AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
	}
}