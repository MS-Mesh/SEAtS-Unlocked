package com.msmesh.seats.ui;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.msmesh.seats.R;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Horizontal 15-day date strip. */
public class DateAdapter extends RecyclerView.Adapter<DateAdapter.Holder> {
	public interface OnDateClick {
		void onDate(LocalDate date);
	}

	private static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("EEE", Locale.getDefault());
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d", Locale.getDefault());

	private final List<LocalDate> dates = new ArrayList<>();
	private final OnDateClick listener;
	private int selected = -1;

	public DateAdapter(OnDateClick listener) {
		this.listener = listener;
	}

	public void submit(List<LocalDate> values, LocalDate selectedDate) {
		dates.clear();
		dates.addAll(values);
		selected = dates.indexOf(selectedDate);
		notifyDataSetChanged();
	}

	@NonNull
	@Override
	public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
		return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_date, parent, false));
	}

	@Override
	public void onBindViewHolder(@NonNull Holder holder, int position) {
		LocalDate date = dates.get(position);
		boolean isSelected = position == selected;
		boolean isToday = date.equals(LocalDate.now());
		holder.weekday.setText(date.format(WEEKDAY));
		holder.day.setText(date.format(DAY));

		int accent = ContextCompat.getColor(holder.itemView.getContext(), R.color.accent);
		int primary = ContextCompat.getColor(holder.itemView.getContext(), R.color.text_primary);
		int secondary = ContextCompat.getColor(holder.itemView.getContext(), R.color.text_secondary);
		int onAccent = ContextCompat.getColor(holder.itemView.getContext(), R.color.bg);

		GradientDrawable background = new GradientDrawable();
		background.setCornerRadius(holder.itemView.getResources().getDisplayMetrics().density * 16f);
		background.setColor(isSelected ? accent : Color.TRANSPARENT);
		holder.itemView.setBackground(background);
		holder.weekday.setTextColor(isSelected ? onAccent : secondary);
		holder.day.setTextColor(isSelected ? onAccent : (isToday ? accent : primary));

		holder.itemView.setOnClickListener(view -> {
			int current = holder.getBindingAdapterPosition();
			if (current == RecyclerView.NO_POSITION) return;
			selected = current;
			notifyDataSetChanged();
			listener.onDate(dates.get(current));
		});
	}

	@Override
	public int getItemCount() {
		return dates.size();
	}

	static class Holder extends RecyclerView.ViewHolder {
		final TextView weekday;
		final TextView day;

		Holder(@NonNull View itemView) {
			super(itemView);
			weekday = itemView.findViewById(R.id.date_weekday);
			day = itemView.findViewById(R.id.date_day);
		}
	}
}