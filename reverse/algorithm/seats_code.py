"""
SEAtS rotating-code reference port: hash a little-endian 64-bit seed/counter sum with MurmurHash2,
reduce it to five decimal digits and append a Damm check digit. The interval comes from AutorefreshingQrDurationSecs.
This is not the portal Class Code mode. References: SeatsMobile.dll and Austin Appleby's public-domain MurmurHash2 algorithm.
"""

from __future__ import annotations

import struct
import time

MASK32 = 0xFFFFFFFF
MURMUR_SEED = 0xC58F1A7A
DEFAULT_DURATION = 30

DAMM = (
	(0, 3, 1, 7, 5, 9, 8, 6, 4, 2),
	(7, 0, 9, 2, 1, 5, 4, 8, 6, 3),
	(4, 2, 0, 6, 8, 7, 1, 3, 5, 9),
	(1, 7, 5, 0, 9, 8, 3, 4, 2, 6),
	(6, 1, 2, 3, 0, 4, 5, 9, 7, 8),
	(3, 6, 7, 4, 2, 0, 9, 5, 8, 1),
	(5, 8, 6, 9, 7, 2, 0, 1, 3, 4),
	(8, 9, 4, 5, 3, 6, 2, 0, 1, 7),
	(9, 4, 3, 8, 6, 1, 7, 2, 0, 5),
	(2, 5, 8, 1, 4, 3, 6, 7, 9, 0),
)


def murmurhash2(data: bytes, seed: int = MURMUR_SEED) -> int:
	"""MurmurHash2 x86_32, matching the official .NET implementation."""
	multiplier, shift, length = 0x5BD1E995, 24, len(data)
	hash_value, offset = (seed ^ length) & MASK32, 0
	while length - offset >= 4:
		mixed = int.from_bytes(data[offset:offset + 4], "little")
		mixed = (mixed * multiplier) & MASK32
		mixed ^= mixed >> shift
		mixed = (mixed * multiplier) & MASK32
		hash_value = (hash_value * multiplier) & MASK32
		hash_value ^= mixed
		offset += 4
	tail = length - offset
	if tail == 3:
		hash_value ^= data[offset + 2] << 16
	if tail >= 2:
		hash_value ^= data[offset + 1] << 8
	if tail >= 1:
		hash_value ^= data[offset]
		hash_value = (hash_value * multiplier) & MASK32
	hash_value ^= hash_value >> 13
	hash_value = (hash_value * multiplier) & MASK32
	hash_value ^= hash_value >> 15
	return hash_value & MASK32


def damm_digit(digits: str) -> int:
	"""Returns the Damm check digit for a string of decimal digits."""
	interim = 0
	for digit in digits:
		interim = DAMM[interim][int(digit)]
	return interim


def damm_valid(code: str) -> bool:
	"""A code is valid when the Damm interim digit over the whole code is 0."""
	return len(code) > 0 and code.isdigit() and damm_digit(code) == 0


def get_totp(seed: str, counter: int) -> str:
	"""Computes the 6-digit code for one counter value."""
	value = (int(seed) + counter) & 0xFFFFFFFFFFFFFFFF
	hashed = murmurhash2(struct.pack("<q", value if value < 2**63 else value - 2**64))
	head = f"{hashed % 100000:05d}"
	return head + str(damm_digit(head))


def generate_code(seed: str, start_cutoff: int, now: int | None = None, duration: int = DEFAULT_DURATION) -> str:
	"""Computes the code for the current (or supplied) unix time."""
	if now is None:
		now = int(time.time())
	return get_totp(seed, (now - start_cutoff) // duration)


def qr_url(code: str) -> str:
	"""The official QrParser accepts any host with the path /qr/<6 digits>."""
	return f"https://seatssoftware.com/qr/{code}"


if __name__ == "__main__":
	# Test vectors recovered from the official client. Any port must match these exactly.
	assert get_totp("832105", 0) == "980580"
	assert get_totp("832105", 1) == "719256"
	assert get_totp("832105", 12345) == "183613"
	assert get_totp("832105", 999999) == "165845"
	assert damm_valid("855824") and damm_digit("85582") == 4
	print("all test vectors passed")
	print("example:", generate_code("832105", 1759921200, 1759921200 + 1800, 20))