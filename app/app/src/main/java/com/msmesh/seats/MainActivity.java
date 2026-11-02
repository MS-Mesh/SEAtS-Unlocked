package com.msmesh.seats;

import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.msmesh.seats.auth.LoginActivity;
import com.msmesh.seats.data.AuthStore;
import com.msmesh.seats.ui.EventsActivity;

/** Entry point: sends the user to the schedule or to the Microsoft login. */
public class MainActivity extends AppCompatActivity {
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Class<?> next = new AuthStore(this).isLoggedIn() ? EventsActivity.class : LoginActivity.class;
		startActivity(new Intent(this, next));
		finish();
	}
}