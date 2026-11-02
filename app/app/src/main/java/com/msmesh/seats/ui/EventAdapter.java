package com.msmesh.seats.ui;

import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.msmesh.seats.R;
import com.msmesh.seats.code.SeatsCode;
import com.msmesh.seats.data.Event;
import com.msmesh.seats.data.Prefs;
import com.msmesh.seats.util.TimeFormat;
import com.msmesh.seats.util.TimeSync;

import java.util.ArrayList;
import java.util.List;

/** Schedule card: status chip, cached class code and the event's time/room. */
public class EventAdapter extends RecyclerView.Adapter<EventAdapter.Holder> {
	public interface OnEventClick {
		void onEvent(Event event);
	}

	private final List<Event> events = new ArrayList<>();
	private final OnEventClick listener;
	private final Prefs prefs;

	public EventAdapter(Prefs prefs, OnEventClick listener) {
		this.prefs = prefs;
		this.listener = listener;
	}

	public void submit(List<Event> items) {
		events.clear();
		if (items != null) events.addAll(items);
		notifyDataSetChanged();
	}

	@NonNull
	@Override
	public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_event, parent, false));
	}

	@Override
	public void onBindViewHolder(@NonNull Holder holder, int position) {
		Event event = events.get(position);
		holder.title.setText(event.displayTitle());
		String type = event.lessonTypeDescription == null ? "" : event.lessonTypeDescription;
		String room = event.roomName == null ? "" : event.roomName;
		holder.subtitle.setText((type + (room.isEmpty() ? "" : " · " + room)).trim());
		holder.time.setText(TimeFormat.dayAndRange(event.start, event.end));

		long now = TimeSync.nowSeconds();
		int attendance = event.attendanceStatusId == null ? 0 : event.attendanceStatusId;
		int barColor;
		int pillBackground;
		int pillForeground;
		String status;
		if (attendance == 3 || attendance == 7 || (attendance == 0 && event.swipeInDateTime > 0)) {
			status = "ATTENDED";
			barColor = R.color.green_text;
			pillBackground = R.color.green_bg;
			pillForeground = R.color.green_text;
		} else if (attendance == 2) {
			status = "LATE";
			barColor = R.color.orange_text;
			pillBackground = R.color.orange_bg;
			pillForeground = R.color.orange_text;
		} else if (attendance == 1) {
			status = "ABSENT";
			barColor = R.color.red_text;
			pillBackground = R.color.red_bg;
			pillForeground = R.color.red_text;
		} else if (now < event.startCutoff) {
			status = "SCHEDULED";
			barColor = R.color.divider;
			pillBackground = R.color.gray_bg;
			pillForeground = R.color.gray_text;
		} else if (now <= event.endCutoff) {
			status = "OPEN";
			barColor = R.color.accent_blue;
			pillBackground = R.color.blue_bg;
			pillForeground = R.color.blue_text;
		} else {
			status = "CLOSED";
			barColor = R.color.divider;
			pillBackground = R.color.gray_bg;
			pillForeground = R.color.text_tertiary;
		}
		holder.bar.setBackgroundColor(ContextCompat.getColor(holder.itemView.getContext(), barColor));
		chip(holder.status, status, ContextCompat.getColor(holder.itemView.getContext(), pillBackground),
				ContextCompat.getColor(holder.itemView.getContext(), pillForeground));

		boolean open = now >= event.startCutoff && now <= event.endCutoff;
		String portalCode = prefs.portalCode(event.timeTableId);
		if (open && event.hasLiveCode()) {
			holder.code.setVisibility(View.VISIBLE);
			holder.code.setText("LIVE  " + SeatsCode.generateCode(event.checkinCode, event.startCutoff, now, prefs.duration()));
			holder.code.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.accent_blue));
		} else if (portalCode.matches("\\d{6}")) {
			holder.code.setVisibility(View.VISIBLE);
			holder.code.setText("CODE  " + portalCode);
			holder.code.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.accent_blue));
		} else if (open) {
			holder.code.setVisibility(View.VISIBLE);
			holder.code.setText("Waiting for the lecturer…");
			holder.code.setTextColor(ContextCompat.getColor(holder.itemView.getContext(), R.color.text_tertiary));
		} else {
			holder.code.setVisibility(View.GONE);
		}
		holder.itemView.setOnClickListener(view -> listener.onEvent(event));
	}

	private void chip(TextView view, String text, int background, int foreground) {
		view.setText(text);
		GradientDrawable drawable = new GradientDrawable();
		drawable.setCornerRadius(view.getResources().getDisplayMetrics().density * 20f);
		drawable.setColor(background);
		view.setBackground(drawable);
		view.setTextColor(foreground);
	}

	@Override
	public int getItemCount() {
		return events.size();
	}

	static class Holder extends RecyclerView.ViewHolder {
		final View bar;
		final TextView title;
		final TextView subtitle;
		final TextView time;
		final TextView status;
		final TextView code;

		Holder(@NonNull View itemView) {
			super(itemView);
			bar = itemView.findViewById(R.id.event_bar);
			title = itemView.findViewById(R.id.event_title);
			subtitle = itemView.findViewById(R.id.event_subtitle);
			time = itemView.findViewById(R.id.event_time);
			status = itemView.findViewById(R.id.event_status);
			code = itemView.findViewById(R.id.event_code);
		}
	}
}