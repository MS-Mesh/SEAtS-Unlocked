"""
SEAtS check-in fingerprint reference implementation.

The official client (Seats.Gateway.Mobile.Client.StudentV2ApiClient) builds the ?fp=
query parameter from the request DTO and the tenant MobilePhone setting:

	key    = SHA256(hex(MobilePhone) || 0D92EC24CEDDE505)[0:8]
	digest = HMAC-SHA256(key, message)
	fp     = big-endian uint64 of digest[0:8]

CheckInWithCode message order (empty values are skipped):
	Timestamp|TimetableId|StudentScheduleId|CheckInReason|CheckInInput|MobileId|StudentId

UnexpectedCheckIn order:
	Timestamp|CheckInReason|CheckInInput|RoomsIds|Devices|StudentId

Important: this is HMAC-SHA256, big-endian. A plain SHA256 / little-endian implementation
returns 400 Inconsistent parameters from the server.
"""

from __future__ import annotations

import hashlib
import hmac

SALT = bytes.fromhex("0D92EC24CEDDE505")


def fingerprint(message: str, mobile_phone: str) -> int:
	'''Returns the unsigned 64-bit fingerprint for one message and tenant MobilePhone.'''
	key = hashlib.sha256(bytes.fromhex(mobile_phone) + SALT).digest()[:8]
	digest = hmac.new(key, message.encode("utf-8"), hashlib.sha256).digest()
	return int.from_bytes(digest[:8], "big")


def checkin_message(timestamp: str, timetable_id: int, student_schedule_id: int, reason: str,
		check_in_input: str, mobile_id: str, student_id: str | None) -> str:
	"""Joins the CheckInWithCode fields in the order expected by the server."""
	parts = (timestamp, str(timetable_id), str(student_schedule_id), reason, check_in_input, mobile_id, student_id or "")
	return "|".join(part for part in parts if part)


if __name__ == "__main__":
	message = "2026-10-22T12:00:00|1234567|7654321|Qr|302711|device-id|12345"
	assert fingerprint(message, "0011223344556677") == 10686439190112768140
	print("fingerprint test vector passed")