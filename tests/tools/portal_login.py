"""
Log into a SEAtS portal through Microsoft SAML and persist the browser session.

Usage:
	python portal_login.py --portal https://uon.seats.cloud --timetable 2872293 --headed

Credentials are read from SEATS_EMAIL / SEATS_PASSWORD or tests/tools/.state/credentials.txt.
On success the Playwright storage state is written to tests/tools/.state/.portal_state.json.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import time

from playwright.sync_api import sync_playwright

from credentials import STATE, credentials

STATUS = STATE / "portal_login_status.txt"
DEFAULT_PORTAL = "https://uon.seats.cloud"


def status(message: str) -> None:
	line = f"{time.strftime('%H:%M:%S')} {message}"
	print(line, flush=True)
	STATUS.write_text(line + "\n", encoding="utf-8")


def main() -> int:
	parser = argparse.ArgumentParser()
	parser.add_argument("--portal", default=DEFAULT_PORTAL, help="tenant portal base URL")
	parser.add_argument("--timetable", type=int, default=int(os.environ.get("SEATS_TIMETABLE_ID", "0")), help="optional timetable id to probe")
	parser.add_argument("--headed", action="store_true", help="show the browser window")
	args = parser.parse_args()
	portal = args.portal.rstrip("/")
	email, password = credentials()

	with sync_playwright() as playwright:
		browser = playwright.chromium.launch(headless=not args.headed)
		context = browser.new_context()
		page = context.new_page()
		status(f"open {portal}")
		page.goto(portal + "/", timeout=60000, wait_until="domcontentloaded")
		page.wait_for_timeout(3000)

		if page.query_selector("input[name=loginfmt]"):
			page.fill("input[name=loginfmt]", email)
			page.click("#idSIButton9")
			page.wait_for_timeout(3000)
		if page.query_selector("#i0118"):
			page.fill("#i0118", password)
			page.click("#idSIButton9")
			page.wait_for_timeout(4000)

		deadline = time.time() + 10 * 60
		announced = False
		while time.time() < deadline:
			try:
				body = page.inner_text("body")
			except Exception:
				body = ""
			match = re.search(r"(?:Enter the number if prompted|Approve sign in request)[\s\S]{0,120}?(\d{1,3})", body)
			if match and not announced:
				status(f"MFA_NUMBER={match.group(1)}  <-- approve in Authenticator")
				announced = True
			if "Stay signed in" in body:
				try:
					page.click("#idSIButton9")
				except Exception:
					pass
			if "seats.cloud" in page.url and "login.microsoftonline" not in page.url and "Account/Acs" not in page.url:
				status("back on portal")
				break
			page.wait_for_timeout(2000)

		page.wait_for_timeout(5000)
		status(f"final url: {page.url[:140]}")
		context.storage_state(path=str(STATE / ".portal_state.json"))
		status("storage state saved")

		if args.timetable:
			probes = {}
			paths = [
				f"/api/QrProxy/GetCheckInCode?id={args.timetable}",
				"/api/QrProxy/ValidateLiveCodeEnabled",
				f"/api/TimetableApi/GetTimetableDetails/{args.timetable}?isNewUi=true",
			]
			probe_script = (
				"async (requestPath) => { const response = await fetch(requestPath, {credentials:'include'}); "
				"return {s:response.status, t:(await response.text()).slice(0,600)}; }"
			)
			for path in paths:
				try:
					result = page.evaluate(probe_script, path)
					probes[path] = result
					status(f"{result['s']} {path} :: {result['t'][:140]}")
				except Exception as error:
					probes[path] = {"error": str(error)}
			(STATE / "portal_login_result.json").write_text(json.dumps(probes, indent=1), encoding="utf-8")
		browser.close()
	return 0


if __name__ == "__main__":
	raise SystemExit(main())