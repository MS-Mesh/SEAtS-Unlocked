package com.msmesh.seats.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.msmesh.seats.R;
import com.msmesh.seats.code.QrRenderer;
import com.msmesh.seats.code.SeatsCode;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

/**
 * Developer tool: preview the seed-mode code for an arbitrary seed/start/duration.
 * Useful when adapting the repository to a tenant that exposes checkinCode.
 */
public class PreviewActivity extends AppCompatActivity {
	private final Handler handler = new Handler(Looper.getMainLooper());

	private TextInputEditText seedInput;
	private TextInputEditText startInput;
	private TextInputEditText durationInput;
	private ImageView qrView;
	private TextView codeView;
	private TextView countdownView;
	private TextView errorView;
	private ProgressBar progress;
	private String currentCode;

	private final Runnable ticker = new Runnable() {
		@Override
		public void run() {
			render();
			handler.postDelayed(this, 250);
		}
	};

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_preview);

		ImageButton back = findViewById(R.id.toolbar_back);
		back.setOnClickListener(view -> finish());

		seedInput = findViewById(R.id.preview_seed);
		startInput = findViewById(R.id.preview_start);
		durationInput = findViewById(R.id.preview_duration);
		qrView = findViewById(R.id.preview_qr);
		codeView = findViewById(R.id.preview_code);
		countdownView = findViewById(R.id.preview_countdown);
		errorView = findViewById(R.id.preview_error);
		progress = findViewById(R.id.preview_progress);

		MaterialButton apply = findViewById(R.id.preview_apply);
		apply.setOnClickListener(view -> {
			currentCode = null;
			render();
		});
		findViewById(R.id.preview_copy).setOnClickListener(view -> {
			if (currentCode == null) return;
			ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
			clipboard.setPrimaryClip(ClipData.newPlainText("Seats code", currentCode));
			Toast.makeText(this, "Code copied", Toast.LENGTH_SHORT).show();
		});
		handler.post(ticker);
	}

	/** Recomputes the code and countdown from the three inputs. */
	private void render() {
		String seed = readText(seedInput);
		Long start = parseStart(readText(startInput));
		int duration = parseInt(readText(durationInput));
		if (seed.isEmpty() || !seed.matches("\\d+") || start == null || duration <= 0) {
			errorView.setVisibility(View.VISIBLE);
			errorView.setText("Enter a numeric seed, a UTC ISO-8601 start time and a positive duration.");
			return;
		}
		errorView.setVisibility(View.GONE);
		long now = System.currentTimeMillis() / 1000L;
		String code = SeatsCode.generateCode(seed, start, now, duration);
		if (!code.equals(currentCode)) {
			currentCode = code;
			codeView.setText(code);
			qrView.setImageBitmap(QrRenderer.render(SeatsCode.qrUrl(code), 700));
		}
		long secondsLeft = SeatsCode.secondsUntilNext(seed, start, now, duration);
		progress.setProgress((int) Math.max(0, Math.min(1000, secondsLeft * 1000 / duration)));
		countdownView.setText(String.format(Locale.US, "%02d:%02d", secondsLeft / 60, secondsLeft % 60));
	}

	private static String readText(TextInputEditText input) {
		return input.getText() == null ? "" : input.getText().toString().trim();
	}

	/** Accepts 2026-10-21T13:00:00Z or 2026-10-21T13:00:00 (interpreted as UTC). */
	private static Long parseStart(String value) {
		try {
			if (value.endsWith("Z") || value.endsWith("z")) return Instant.parse(value).getEpochSecond();
			return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC).getEpochSecond();
		} catch (Exception error) {
			return null;
		}
	}

	private static int parseInt(String value) {
		try {
			return Integer.parseInt(value);
		} catch (Exception error) {
			return -1;
		}
	}

	@Override
	protected void onDestroy() {
		handler.removeCallbacksAndMessages(null);
		super.onDestroy();
	}
}