package com.msmesh.seats.auth;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.browser.customtabs.CustomTabsIntent;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.msmesh.seats.R;
import com.msmesh.seats.data.AuthStore;
import com.msmesh.seats.data.Prefs;
import com.msmesh.seats.ui.EventsActivity;

/**
 * Collects the university email and opens the Microsoft login in Custom Tabs.
 * The actual code exchange happens in CallbackActivity when seatsmobileapp://callback is received.
 */
public class LoginActivity extends AppCompatActivity {
	private TextInputEditText emailInput;
	private MaterialButton signInButton;
	private ProgressBar progress;
	private TextView error;

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (new AuthStore(this).isLoggedIn()) {
			startActivity(new Intent(this, EventsActivity.class));
			finish();
			return;
		}
		setContentView(R.layout.activity_login);
		emailInput = findViewById(R.id.email);
		signInButton = findViewById(R.id.sign_in);
		progress = findViewById(R.id.login_progress);
		error = findViewById(R.id.login_error);
		emailInput.setText(new Prefs(this).email());
		signInButton.setOnClickListener(view -> beginLogin());
	}

	/** Validates the email, remembers it, and launches the OIDC authorize URL. */
	private void beginLogin() {
		String email = emailInput.getText() == null ? "" : emailInput.getText().toString().trim();
		if (email.isEmpty() || !email.contains("@")) {
			error.setText("Enter your university email address");
			error.setVisibility(View.VISIBLE);
			return;
		}
		new Prefs(this).setEmail(email);
		error.setVisibility(View.GONE);
		String url = AuthManager.createAuthorizeUrl(this, email);
		CustomTabsIntent tabs = new CustomTabsIntent.Builder().setShowTitle(true).build();
		tabs.launchUrl(this, Uri.parse(url));
	}
}