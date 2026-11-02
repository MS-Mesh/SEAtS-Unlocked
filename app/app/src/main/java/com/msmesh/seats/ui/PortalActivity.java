package com.msmesh.seats.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.msmesh.seats.R;
import com.msmesh.seats.data.Prefs;

/**
 * Opens the tenant web portal in a WebView so the student can sign in once.
 * The Angular route guard around the lecturer QR page is client-side only: the QrProxy API accepts
 * any authenticated portal session. After login we run a same-origin fetch inside the page, so the
 * HttpOnly session cookies are sent automatically, and cache every timetable's Class Code.
 */
public class PortalActivity extends AppCompatActivity {
	public static final String EXTRA_TIMETABLE_ID = "timetable_id";
	public static final String EXTRA_TIMETABLE_IDS = "timetable_ids";
	public static final String EXTRA_CODE = "code";
	public static final String EXTRA_CODES_JSON = "codes_json";

	private static final String BRIDGE = "SeatsPortalBridge";

	private WebView web;
	private ProgressBar progress;
	private TextView status;
	private MaterialButton retry;
	private int[] timetableIds = new int[0];
	private Prefs prefs;
	private boolean fetching;
	private boolean finishedWithCode;

	@SuppressLint("SetJavaScriptEnabled")
	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_portal);
		prefs = new Prefs(this);

		int singleId = getIntent().getIntExtra(EXTRA_TIMETABLE_ID, 0);
		int[] requestedIds = getIntent().getIntArrayExtra(EXTRA_TIMETABLE_IDS);
		if (requestedIds == null || requestedIds.length == 0) requestedIds = singleId > 0 ? new int[]{singleId} : new int[0];
		timetableIds = requestedIds;

		web = findViewById(R.id.portal_web);
		progress = findViewById(R.id.portal_progress);
		status = findViewById(R.id.portal_status);
		retry = findViewById(R.id.portal_retry);

		findViewById(R.id.portal_back).setOnClickListener(view -> finish());
		retry.setOnClickListener(view -> {
			retry.setVisibility(View.GONE);
			fetching = false;
			status.setText("Opening the portal…");
			web.loadUrl(prefs.portalBase() + "/");
		});

		CookieManager cookies = CookieManager.getInstance();
		cookies.setAcceptCookie(true);
		cookies.setAcceptThirdPartyCookies(web, true);

		WebSettings settings = web.getSettings();
		settings.setJavaScriptEnabled(true);
		settings.setDomStorageEnabled(true);
		settings.setDatabaseEnabled(true);
		settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

		web.addJavascriptInterface(new Bridge(), BRIDGE);
		web.setWebViewClient(new WebViewClient() {
			@Override
			public void onPageStarted(WebView view, String url, Bitmap favicon) {
				progress.setVisibility(View.VISIBLE);
			}

			@Override
			public void onPageFinished(WebView view, String url) {
				progress.setVisibility(View.GONE);
				String host = Uri.parse(prefs.portalBase()).getHost();
				if (url != null && host != null && url.contains(host) && !url.contains("login.microsoftonline")) fetchCodes();
			}

			@Override
			public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
				String url = request.getUrl().toString();
				if (url.startsWith("http://") || url.startsWith("https://")) return false;
				try {
					startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
				} catch (Exception ignored) {
				}
				return true;
			}
		});

		status.setText("Sign in once with your university account; the app will load every class code from your own portal session.");
		web.loadUrl(prefs.portalBase() + "/");
	}

	/** Sequential same-origin fetch for every requested timetable id; results go through the JS bridge. */
	private void fetchCodes() {
		if (fetching || finishedWithCode || timetableIds.length == 0) return;
		fetching = true;
		status.setText(timetableIds.length == 1 ? "Reading the class code…" : "Reading all class codes…");
		StringBuilder idList = new StringBuilder();
		for (int timetableId : timetableIds) {
			if (idList.length() > 0) idList.append(',');
			idList.append(timetableId);
		}
		String script = "(function(){var ids=[" + idList + "];var out={};var index=0;function next(){"
				+ "if(index>=ids.length){" + BRIDGE + ".bulk(JSON.stringify(out));return;}var id=ids[index++];"
				+ "fetch('/api/QrProxy/GetCheckInCode?id='+id,{credentials:'include'})"
				+ ".then(function(r){return r.text().then(function(t){out[id]={s:r.status,t:t};next();});})"
				+ ".catch(function(e){out[id]={s:-1,t:String(e)};next();});}next();})();";
		web.evaluateJavascript(script, null);
	}

	/** Parses {id: {s, t}} from the bridge, normalizes 6-digit codes and caches them per timetable. */
	private void onBulkResult(String json) {
		fetching = false;
		JsonObject result = new JsonObject();
		int failures = 0;
		try {
			JsonObject root = JsonParser.parseString(json).getAsJsonObject();
			for (String timetableKey : root.keySet()) {
				JsonObject entry = root.getAsJsonObject(timetableKey);
				if (entry == null) continue;
				int httpStatus = entry.has("s") ? entry.get("s").getAsInt() : -1;
				JsonElement body = entry.get("t");
				String code = normalize(body == null ? null : body.getAsString());
				if (code != null) {
					try {
						prefs.setPortalCode(Integer.parseInt(timetableKey), code);
						result.addProperty(timetableKey, code);
					} catch (Exception ignored) {
					}
				} else {
					failures++;
					result.addProperty(timetableKey, "");
					if (httpStatus == 401 || httpStatus == 403 || httpStatus == 500) {
						status.setText("The portal session is not ready yet. Finish signing in, then tap start over.");
					}
				}
			}
		} catch (Exception error) {
			status.setText("Could not read the code: " + error.getMessage());
		}

		if (result.size() > 0 && hasAnyCode(result)) {
			finishedWithCode = true;
			prefs.setPortalLastSync(System.currentTimeMillis());
			Intent data = new Intent();
			data.putExtra(EXTRA_CODES_JSON, result.toString());
			if (timetableIds.length == 1) data.putExtra(EXTRA_CODE, normalizeResult(result, timetableIds[0]));
			setResult(RESULT_OK, data);
			finish();
			return;
		}
		if (failures > 0 || result.size() == 0) status.setText("Could not read the code (HTTP error). Make sure you are signed in to the portal.");
		retry.setText("Start over");
		retry.setVisibility(View.VISIBLE);
	}

	private static boolean hasAnyCode(JsonObject result) {
		for (String key : result.keySet()) {
			if (result.get(key).getAsString().matches("\\d{6}")) return true;
		}
		return false;
	}

	private static String normalizeResult(JsonObject result, int timetableId) {
		JsonElement value = result.get(Integer.toString(timetableId));
		return value == null ? null : value.getAsString();
	}

	/** The endpoint returns a JSON string such as "302711"; accept only exactly six digits. */
	private static String normalize(String body) {
		if (body == null) return null;
		String value = body.trim();
		if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) value = value.substring(1, value.length() - 1).trim();
		return value.matches("\\d{6}") ? value : null;
	}

	private class Bridge {
		@JavascriptInterface
		public void bulk(String json) {
			runOnUiThread(() -> onBulkResult(json));
		}
	}

	@Override
	public void onBackPressed() {
		if (web != null && web.canGoBack()) web.goBack();
		else super.onBackPressed();
	}

	@Override
	protected void onDestroy() {
		if (web != null) {
			web.removeJavascriptInterface(BRIDGE);
			web.destroy();
		}
		super.onDestroy();
	}
}