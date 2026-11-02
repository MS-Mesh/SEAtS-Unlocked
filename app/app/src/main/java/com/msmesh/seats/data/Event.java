package com.msmesh.seats.data;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * One student timetable event from /api/v3/students/myself/events.
 * attendanceStatusId: 1 Absent, 2 Late, 3 Attended, 4 Scheduled, 7 MarkedAsAttended.
 */
public class Event {
	@SerializedName("eventId") public int eventId;
	@SerializedName("studentScheduleId") public long studentScheduleId;
	@SerializedName("attendanceStatusId") public Integer attendanceStatusId;
	@SerializedName("start") public int start;
	@SerializedName("end") public int end;
	@SerializedName("startCutoff") public int startCutoff;
	@SerializedName("lateCutoff") public int lateCutoff;
	@SerializedName("checkoutCutoff") public int checkoutCutoff;
	@SerializedName("endCutoff") public int endCutoff;
	@SerializedName("swipeInDateTime") public int swipeInDateTime;
	@SerializedName("swipeOutDateTime") public int swipeOutDateTime;
	@SerializedName("title") public String title;
	@SerializedName("lessonDescription") public String lessonDescription;
	@SerializedName("eventType") public String eventType;
	@SerializedName("classStatus") public int classStatus;
	@SerializedName(value = "timeTableId", alternate = {"timetableId"}) public int timeTableId;
	@SerializedName("moduleName") public String moduleName;
	@SerializedName("moduleCode") public String moduleCode;
	@SerializedName("courseCode") public String courseCode;
	@SerializedName("lecturer") public String lecturer;
	@SerializedName("iBeaconData") public List<Object> iBeaconData;
	@SerializedName("rooms") public List<Room> rooms;
	@SerializedName("geofenceAreas") public List<Object> geofenceAreas;
	@SerializedName("roomId") public int roomId;
	@SerializedName("roomName") public String roomName;
	@SerializedName("lessonTypeDescription") public String lessonTypeDescription;
	@SerializedName("isCheckOutAllowed") public boolean isCheckOutAllowed;
	@SerializedName("isCheckOutAllowedNullable") public Boolean isCheckOutAllowedNullable;
	@SerializedName("isMandatory") public boolean isMandatory;
	@SerializedName("isGpsEnabled") public boolean isGpsEnabled;
	@SerializedName("checkinOptions") public String checkinOptions;
	@SerializedName("checkinCode") public String checkinCode;
	@SerializedName("checkinCodeRoom") public String checkinCodeRoom;
	@SerializedName("checkinCodesRooms") public List<String> checkinCodesRooms;
	@SerializedName("virtualAttendance") public boolean virtualAttendance;
	@SerializedName("actAsAttendanceOptions") public String actAsAttendanceOptions;

	/** True when the tenant exposes a seed in the event payload (rotating-code mode). */
	public boolean hasLiveCode() {
		return checkinCode != null && !checkinCode.trim().isEmpty();
	}

	/** True when the event carries static room codes. */
	public boolean hasRoomCodes() {
		return checkinCodesRooms != null && !checkinCodesRooms.isEmpty();
	}

	public String displayTitle() {
		if (title != null && !title.isEmpty()) return title;
		if (moduleName != null && !moduleName.isEmpty()) return moduleName;
		if (lessonDescription != null && !lessonDescription.isEmpty()) return lessonDescription;
		return "Event";
	}
}