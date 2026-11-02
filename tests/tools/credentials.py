"""
Credential loading for the research scripts.

Never commit credentials. Use either environment variables:

	SEATS_EMAIL=you@university.ac.uk
	SEATS_PASSWORD=...

or a private file at tests/tools/.state/credentials.txt with two lines (email, password).
That directory is gitignored.
"""

from __future__ import annotations

import os
import pathlib

STATE = pathlib.Path(__file__).resolve().parent / ".state"
STATE.mkdir(exist_ok=True)


def credentials() -> tuple[str, str]:
	email, password = os.environ.get("SEATS_EMAIL"), os.environ.get("SEATS_PASSWORD")
	if email and password:
		return email, password
	credential_path = STATE / "credentials.txt"
	if credential_path.exists():
		lines = [line.strip() for line in credential_path.read_text(encoding="utf-8").splitlines() if line.strip()]
		if len(lines) >= 2:
			return lines[0], lines[1]
	raise SystemExit("Set SEATS_EMAIL / SEATS_PASSWORD or create tests/tools/.state/credentials.txt")