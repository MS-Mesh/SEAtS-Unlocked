package com.msmesh.seats.data;

import com.google.gson.annotations.SerializedName;

/** Room entry attached to a timetable event. */
public class Room {
	@SerializedName("externalCode") public String externalCode;
	@SerializedName("id") public int id;
	@SerializedName("name") public String name;
}