"""
Read class codes from a saved portal session.

Usage:
	python probe_qrproxy.py --portal https://uon.seats.cloud --timetable 2872293 --timetable 2872294

Requires tests/tools/.state/.portal_state.json produced by portal_login.py.
This is the same same-origin fetch the Android app performs inside its WebView.
"""

from __future__ import annotations

import argparse
import json

from playwright.sync_api import sync_playwright

from credentials import STATE


def main() -> int:
	parser = argparse.ArgumentParser()
	parser.add_argument("--portal", default="https://uon.seats.cloud")
	parser.add_argument("--timetable", type=int, action="append", required=True)
	args = parser.parse_args()
	portal = args.portal.rstrip("/")
	state = STATE / ".portal_state.json"
	if not state.exists():
		raise SystemExit("Run portal_login.py first; .state/.portal_state.json is missing")

	with sync_playwright() as playwright:
		browser = playwright.chromium.launch(headless=True)
		context = browser.new_context(storage_state=str(state))
		page = context.new_page()
		page.goto(portal + "/angular/", wait_until="domcontentloaded", timeout=60000)
		page.wait_for_timeout(2500)
		result = {}
		probe_script = (
			"async (timetableId) => { const response = await fetch('/api/QrProxy/GetCheckInCode?id=' + timetableId, {credentials:'include'}); "
			"return {status:response.status, body:await response.text()}; }"
		)
		for timetable in args.timetable:
			response = page.evaluate(probe_script, timetable)
			result[str(timetable)] = response
			print(timetable, response)
		browser.close()
	(STATE / "qrproxy_codes.json").write_text(json.dumps(result, indent=1), encoding="utf-8")
	return 0


if __name__ == "__main__":
	raise SystemExit(main())