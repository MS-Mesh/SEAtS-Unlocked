package com.msmesh.seats.data;

import com.google.gson.annotations.SerializedName;

/** One key/value row from /api/v1/app/settingsextended. */
public class AppSetting {
	@SerializedName("key") public String key;
	@SerializedName("value") public String value;
}