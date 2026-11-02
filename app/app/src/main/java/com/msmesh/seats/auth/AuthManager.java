package com.msmesh.seats.auth;

import android.content.Context;
import android.net.Uri;

import com.google.gson.Gson;
import com.msmesh.seats.data.AuthStore;
import com.msmesh.seats.data.UserInfo;
import com.msmesh.seats.net.Http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OIDC Authorization Code + PKCE login against the SEAtS identity provider.
 * The browser (Custom Tabs) returns seatsmobileapp://callback, CallbackActivity exchanges the code.
 */
public final class AuthManager {
	public static final String AUTHORITY = "https://idv2.seats.cloud";
	public static final String DEFAULT_CLIENT_ID = "seatsmaui.code.pkce.v2";
	public static final String NOTTINGHAM_CLIENT_ID = "seatsmaui.code.pkce";
	public static final String REDIRECT_URI = "seatsmobileapp://callback";
	public static final String SCOPE = "openid profile email SEAtSMobile SEAtS.profile offline_access";

	private static final Gson GSON = new Gson();
	private static final SecureRandom RANDOM = new SecureRandom();

	private AuthManager() {
	}

	/** Login/refresh failure with a message suitable for the UI. */
	public static final class AuthException extends Exception {
		public AuthException(String message) {
			super(message);
		}

		public AuthException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	private static final class TokenResponse {
		String access_token;
		String refresh_token;
		long expires_in;
		String error;
		String error_description;
	}

	/** Builds the authorize URL and stores the PKCE verifier, state and selected client id. */
	public static String createAuthorizeUrl(Context context, String email) {
		AuthStore store = new AuthStore(context);
		String verifier = randomUrlSafe(48);
		String challenge = base64Url(sha256(verifier));
		String state = randomUrlSafe(24);
		String nonce = randomUrlSafe(24);
		String clientId = clientIdFor(email);
		store.savePkce(verifier, state, clientId);

		Uri.Builder builder = Uri.parse(AUTHORITY + "/connect/authorize").buildUpon()
				.appendQueryParameter("client_id", clientId)
				.appendQueryParameter("redirect_uri", REDIRECT_URI)
				.appendQueryParameter("response_type", "code")
				.appendQueryParameter("scope", SCOPE)
				.appendQueryParameter("code_challenge", challenge)
				.appendQueryParameter("code_challenge_method", "S256")
				.appendQueryParameter("state", state)
				.appendQueryParameter("nonce", nonce);
		if (email != null && !email.trim().isEmpty()) builder.appendQueryParameter("login_hint", email.trim().toLowerCase());
		return builder.build().toString();
	}

	/** Exchanges the authorization code and loads the user's claims (tenant, student id, gateway). */
	public static UserInfo completeLogin(Context context, String code, String state) throws AuthException {
		AuthStore store = new AuthStore(context);
		String expectedState = store.oauthState();
		if (expectedState == null || !expectedState.equals(state)) throw new AuthException("OAuth state mismatch");
		String verifier = store.verifier();
		if (verifier == null) throw new AuthException("Missing PKCE verifier");

		Map<String, String> form = new LinkedHashMap<>();
		form.put("grant_type", "authorization_code");
		form.put("client_id", store.clientId());
		form.put("redirect_uri", REDIRECT_URI);
		form.put("code", code);
		form.put("code_verifier", verifier);

		try {
			Http.Result result = Http.postForm(AUTHORITY + "/connect/token", form);
			TokenResponse token = GSON.fromJson(result.body, TokenResponse.class);
			if (!result.ok() || token == null || token.access_token == null) {
				String reason = token != null && token.error != null ? token.error : ("HTTP " + result.code);
				throw new AuthException("Token exchange failed: " + reason);
			}
			store.saveTokens(token.access_token, token.refresh_token, token.expires_in);
			UserInfo info = fetchUserInfo(token.access_token);
			store.saveUserInfo(info);
			return info;
		} catch (IOException error) {
			throw new AuthException("Network error during login", error);
		}
	}

	/** Reads /connect/userinfo; MobileGwEndpoint is required for every subsequent API call. */
	public static UserInfo fetchUserInfo(String accessToken) throws AuthException {
		try {
			Http.Result result = Http.get(AUTHORITY + "/connect/userinfo", accessToken);
			if (!result.ok()) throw new AuthException("Userinfo failed: HTTP " + result.code);
			UserInfo info = GSON.fromJson(result.body, UserInfo.class);
			boolean missingGateway = info == null || info.mobileGwEndpoint == null || info.mobileGwEndpoint.isEmpty();
			if (missingGateway) throw new AuthException("Login succeeded, but MobileGwEndpoint is missing");
			return info;
		} catch (IOException error) {
			throw new AuthException("Network error during userinfo", error);
		}
	}

	/** Returns a valid access token, refreshing it when the stored one is expired. */
	public static synchronized String ensureToken(Context context) throws AuthException {
		AuthStore store = new AuthStore(context);
		if (store.accessToken() != null && !store.isExpired()) return store.accessToken();
		return refresh(context);
	}

	/** Refreshes the access token using the stored offline_access refresh token. */
	public static synchronized String refresh(Context context) throws AuthException {
		AuthStore store = new AuthStore(context);
		String refreshToken = store.refreshToken();
		if (refreshToken == null || refreshToken.isEmpty()) throw new AuthException("Session expired, sign in again");
		Map<String, String> form = new LinkedHashMap<>();
		form.put("grant_type", "refresh_token");
		form.put("client_id", store.clientId());
		form.put("refresh_token", refreshToken);
		try {
			Http.Result result = Http.postForm(AUTHORITY + "/connect/token", form);
			TokenResponse token = GSON.fromJson(result.body, TokenResponse.class);
			if (!result.ok() || token == null || token.access_token == null) throw new AuthException("Token refresh failed: HTTP " + result.code);
			String nextRefreshToken = token.refresh_token != null ? token.refresh_token : refreshToken;
			store.saveTokens(token.access_token, nextRefreshToken, token.expires_in);
			return token.access_token;
		} catch (IOException error) {
			throw new AuthException("Network error during token refresh", error);
		}
	}

	public static void logout(Context context) {
		new AuthStore(context).clear();
	}

	/** University of Nottingham uses a dedicated client id; other tenants use the default one. */
	private static String clientIdFor(String email) {
		if (email != null && email.toLowerCase().endsWith("@nottingham.ac.uk")) return NOTTINGHAM_CLIENT_ID;
		return DEFAULT_CLIENT_ID;
	}

	private static String randomUrlSafe(int bytes) {
		byte[] buffer = new byte[bytes];
		RANDOM.nextBytes(buffer);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
	}

	private static byte[] sha256(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
		} catch (Exception error) {
			throw new IllegalStateException(error);
		}
	}

	private static String base64Url(byte[] value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
	}
}