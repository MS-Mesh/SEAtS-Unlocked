package com.msmesh.seats.net;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.msmesh.seats.auth.AuthManager;
import com.msmesh.seats.code.Fingerprint;
import com.msmesh.seats.code.SettingCrypto;
import com.msmesh.seats.data.AppSetting;
import com.msmesh.seats.data.AuthStore;
import com.msmesh.seats.data.Event;
import com.msmesh.seats.data.Prefs;
import com.msmesh.seats.data.Room;
import com.msmesh.seats.data.UserInfo;
import com.msmesh.seats.util.TimeSync;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Thin client for the SEAtS mobile gateway.
 * Endpoints used: /api/v3/students/myself/events, /api/v1/app/settingsextended and
 * /api/v2/students/myself/checkinwithcode. The gateway host comes from the MobileGwEndpoint claim.
 */
public class SeatsApi {
	private static final Gson GSON = new Gson();
	private static final String APP_VERSION = "4.21.0";

	private final Context context;
	private final AuthStore auth;
	private final Prefs prefs;
	private Map<String, String> settings;

	public static class ApiException extends Exception {
		public ApiException(String message) {
			super(message);
		}

		public ApiException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	public SeatsApi(Context context) {
		this.context = context.getApplicationContext();
		this.auth = new AuthStore(this.context);
		this.prefs = new Prefs(this.context);
	}

	/** Normalizes the gateway claim into an https base URL. */
	public String baseUrl() throws ApiException {
		UserInfo info = auth.userInfo();
		boolean missingGateway = info == null || info.mobileGwEndpoint == null || info.mobileGwEndpoint.trim().isEmpty();
		if (missingGateway) throw new ApiException("Not signed in");
		String gateway = info.mobileGwEndpoint.trim();
		if (gateway.startsWith("http://") || gateway.startsWith("https://")) return trimSlash(gateway);
		if (gateway.contains(".")) return "https://" + trimSlash(gateway);
		return "https://" + trimSlash(gateway) + ".seats.cloud";
	}

	/** GET /api/v3/students/myself/events?startDate=&endDate= (unix seconds). */
	public List<Event> getEvents(long start, long end) throws ApiException {
		String body = authorizedGet(baseUrl() + "/api/v3/students/myself/events?startDate=" + start + "&endDate=" + end);
		try {
			Event[] events = GSON.fromJson(body, Event[].class);
			List<Event> result = new ArrayList<>();
			if (events != null) {
				for (Event event : events) if (event != null) result.add(event);
			}
			return result;
		} catch (Exception error) {
			throw new ApiException("Unexpected events response", error);
		}
	}

	/** GET /api/v3/students/myself/events/{eventType}/{eventId}. */
	public Event getEvent(String eventType, int eventId) throws ApiException {
		try {
			return GSON.fromJson(authorizedGet(baseUrl() + "/api/v3/students/myself/events/" + eventType + "/" + eventId), Event.class);
		} catch (ApiException error) {
			throw error;
		} catch (Exception error) {
			throw new ApiException("Unexpected event response", error);
		}
	}

	/** GET /api/v1/app/settingsextended; cached for the lifetime of this instance. */
	public Map<String, String> getSettings() throws ApiException {
		if (settings != null) return settings;
		String body = authorizedGet(baseUrl() + "/api/v1/app/settingsextended?appVersion=" + APP_VERSION);
		Map<String, String> settingsMap = new HashMap<>();
		try {
			AppSetting[] items = GSON.fromJson(body, AppSetting[].class);
			if (items != null) {
				for (AppSetting item : items) if (item != null && item.key != null) settingsMap.put(item.key, item.value);
			}
		} catch (Exception error) {
			throw new ApiException("Unexpected settings response", error);
		}
		settings = settingsMap;
		int duration = parseInt(settingsMap.get("AutorefreshingQrDurationSecs"), 0);
		if (duration > 0) prefs.setDuration(duration);
		return settingsMap;
	}

	/** AutorefreshingQrDurationSecs with the locally cached value. */
	public int durationSeconds() {
		try {
			int value = parseInt(getSettings().get("AutorefreshingQrDurationSecs"), 0);
			if (value > 0) return value;
		} catch (ApiException ignored) {
		}
		return prefs.duration();
	}

	/** MobilePhone setting, AES-decrypted; it is the key material for the fingerprint. */
	public String mobilePhone() {
		try {
			return SettingCrypto.decrypt(getSettings().getOrDefault("MobilePhone", ""));
		} catch (ApiException ignored) {
			return "";
		}
	}

	/**
	 * POST /api/v2/students/myself/checkinwithcode.
	 * Past lectures are sent with a timestamp inside their window because the server applies the
	 * clocking by Timestamp; future lectures are scheduled by CheckInScheduler at the window.
	 */
	public CheckInResult checkIn(Event event, String code) throws ApiException {
		if (event == null) throw new ApiException("No event selected");
		if (event.timeTableId <= 0) throw new ApiException("Event has no timetable id");

		long now = TimeSync.nowSeconds();
		long stamp = now;
		boolean usedWindowTime = false;
		if (event.startCutoff > 0 && event.endCutoff > 0 && now > event.endCutoff) {
			long inside = event.start > 0 ? event.start + 300L : event.startCutoff + 300L;
			if (inside <= event.startCutoff || inside >= event.endCutoff) inside = event.startCutoff + 60L;
			stamp = inside;
			usedWindowTime = true;
		}

		String timestamp = formatUtc(stamp);
		String reason = reasonFor(event, code);
		String mobileId = prefs.deviceId();
		UserInfo info = auth.userInfo();
		String studentId = info == null ? null : info.studentId;
		String fingerprint = Fingerprint.compute(timestamp, event.timeTableId, event.studentScheduleId, reason, code, mobileId, studentId, mobilePhone());

		JsonObject payload = new JsonObject();
		payload.addProperty("IsCheckOut", false);
		payload.addProperty("Timestamp", timestamp);
		payload.addProperty("TimetableId", event.timeTableId);
		payload.add("RoomsIds", roomIds(event));
		payload.addProperty("StudentScheduleId", event.studentScheduleId);
		payload.addProperty("CheckInReason", reason);
		payload.addProperty("StartCutoffTime", formatUtc(event.startCutoff));
		payload.addProperty("CheckInInput", code);
		payload.addProperty("MobileId", mobileId);

		Http.Result response = authorizedPost(baseUrl() + "/api/v2/students/myself/checkinwithcode?fp=" + fingerprint, payload.toString());
		CheckInResult result = new CheckInResult();
		result.httpCode = response.code;
		result.raw = response.body;
		try {
			CheckInResult parsed = GSON.fromJson(response.body, CheckInResult.class);
			if (parsed != null) {
				parsed.httpCode = response.code;
				parsed.raw = response.body;
				result = parsed;
			}
		} catch (Exception ignored) {
		}
		result.usedWindowTime = usedWindowTime;
		if (!response.ok()) throw new ApiException("Check-in failed (HTTP " + response.code + "): " + result.message());
		return result;
	}

	/** UTC timestamp without a timezone suffix, matching the official client. */
	public static String formatUtc(long unixSeconds) {
		SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
		formatter.setTimeZone(TimeZone.getTimeZone("UTC"));
		return formatter.format(new Date(unixSeconds * 1000L));
	}

	private static String reasonFor(Event event, String code) {
		if (event.checkinCodesRooms != null && event.checkinCodesRooms.contains(code)) return "QrRoom";
		return "Qr";
	}

	private static JsonArray roomIds(Event event) {
		JsonArray ids = new JsonArray();
		if (event.rooms != null) {
			for (Room room : event.rooms) if (room != null) ids.add(room.id);
		}
		return ids;
	}

	private String authorizedGet(String url) throws ApiException {
		try {
			Http.Result result = Http.get(url, AuthManager.ensureToken(context));
			if (result.code == 401) result = Http.get(url, AuthManager.refresh(context));
			TimeSync.update(result.serverTimeMillis);
			if (!result.ok()) throw new ApiException("HTTP " + result.code + ": " + result.body);
			return result.body;
		} catch (IOException error) {
			throw new ApiException("Network error", error);
		} catch (AuthManager.AuthException error) {
			throw new ApiException(error.getMessage(), error);
		}
	}

	private Http.Result authorizedPost(String url, String json) throws ApiException {
		try {
			Http.Result result = Http.postJson(url, AuthManager.ensureToken(context), json);
			if (result.code == 401) result = Http.postJson(url, AuthManager.refresh(context), json);
			TimeSync.update(result.serverTimeMillis);
			return result;
		} catch (IOException error) {
			throw new ApiException("Network error", error);
		} catch (AuthManager.AuthException error) {
			throw new ApiException(error.getMessage(), error);
		}
	}

	private static String trimSlash(String value) {
		while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
		return value;
	}

	private static int parseInt(String value, int fallback) {
		try {
			return Integer.parseInt(value);
		} catch (Exception error) {
			return fallback;
		}
	}
}