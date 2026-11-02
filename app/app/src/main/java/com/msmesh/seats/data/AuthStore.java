package com.msmesh.seats.data;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import com.google.gson.Gson;

/** Encrypted storage for OIDC tokens, PKCE state and the user's claims. */
public class AuthStore {
	private static final String FILE = "seats_auth";
	private final SharedPreferences prefs;
	private final Gson gson = new Gson();

	public AuthStore(Context context) {
		try {
			MasterKey key = new MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build();
			prefs = EncryptedSharedPreferences.create(context, FILE, key,
					EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
					EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
		} catch (Exception error) {
			throw new RuntimeException("Cannot open secure storage", error);
		}
	}

	/** Stores the access/refresh tokens; expires_at is kept 60 seconds early to avoid edge races. */
	public void saveTokens(String accessToken, String refreshToken, long expiresInSeconds) {
		long expiresAt = System.currentTimeMillis() / 1000L + Math.max(60, expiresInSeconds - 60);
		prefs.edit().putString("access_token", accessToken).putString("refresh_token", refreshToken).putLong("expires_at", expiresAt).apply();
	}

	public void saveUserInfo(UserInfo info) {
		prefs.edit().putString("userinfo", gson.toJson(info)).apply();
	}

	public UserInfo userInfo() {
		String json = prefs.getString("userinfo", null);
		return json == null ? null : gson.fromJson(json, UserInfo.class);
	}

	/** Saves the PKCE verifier, OAuth state and the client id selected for this login. */
	public void savePkce(String verifier, String state, String clientId) {
		prefs.edit().putString("pkce_verifier", verifier).putString("oauth_state", state).putString("oauth_client_id", clientId).apply();
	}

	public String clientId() {
		return prefs.getString("oauth_client_id", "seatsmaui.code.pkce.v2");
	}

	public String verifier() {
		return prefs.getString("pkce_verifier", null);
	}

	public String oauthState() {
		return prefs.getString("oauth_state", null);
	}

	public String accessToken() {
		return prefs.getString("access_token", null);
	}

	public String refreshToken() {
		return prefs.getString("refresh_token", null);
	}

	public boolean isExpired() {
		return System.currentTimeMillis() / 1000L >= prefs.getLong("expires_at", 0);
	}

	public boolean isLoggedIn() {
		return accessToken() != null && refreshToken() != null && userInfo() != null;
	}

	public void clear() {
		prefs.edit().clear().apply();
	}
}