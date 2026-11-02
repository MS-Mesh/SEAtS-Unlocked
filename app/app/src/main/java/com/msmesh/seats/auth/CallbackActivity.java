package com.msmesh.seats.auth;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.msmesh.seats.data.AuthStore;
import com.msmesh.seats.ui.EventsActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Receives seatsmobileapp://callback from the OIDC browser flow and completes the token exchange. */
public class CallbackActivity extends AppCompatActivity {
	private final ExecutorService executor = Executors.newSingleThreadExecutor();

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		TextView view = new TextView(this);
		view.setText("Finishing sign-in…");
		view.setTextSize(18f);
		setContentView(view);

		Uri uri = getIntent() == null ? null : getIntent().getData();
		if (uri == null || !"seatsmobileapp".equalsIgnoreCase(uri.getScheme())) {
			finish();
			return;
		}
		String code = uri.getQueryParameter("code");
		String state = uri.getQueryParameter("state");
		if (code == null || state == null) {
			finish();
			return;
		}
		executor.execute(() -> {
			try {
				AuthManager.completeLogin(this, code, state);
				runOnUiThread(() -> {
					startActivity(new Intent(this, EventsActivity.class));
					finish();
				});
			} catch (Exception error) {
				Log.e("SeatsAuth", "Callback login failed", error);
				runOnUiThread(() -> {
					Toast.makeText(this, "Login failed: " + error.getMessage(), Toast.LENGTH_LONG).show();
					new AuthStore(this).clear();
					finish();
				});
			}
		});
	}

	@Override
	protected void onDestroy() {
		executor.shutdownNow();
		super.onDestroy();
	}
}