package com.msmesh.seats.net;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * Top-level response of /api/v2/students/myself/checkinwithcode.
 * isValid means the code is correct; it does not mean attendance was applied.
 * The nested checkInResponse is parsed from raw in EventActivity.
 */
public class CheckInResult {
	@SerializedName("success") public boolean success;
	@SerializedName("isValid") public boolean isValid;
	@SerializedName("messages") public List<String> messages;
	@SerializedName("checkInResponse") public Object checkInResponse;
	public int httpCode;
	public String raw;
	public boolean usedWindowTime;

	public String message() {
		if (messages != null && !messages.isEmpty()) return String.join(" ", messages);
		return raw == null || raw.isEmpty() ? ("HTTP " + httpCode) : raw;
	}
}