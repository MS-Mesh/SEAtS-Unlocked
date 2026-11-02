"""
Interactive OIDC login with Microsoft MFA.

Prints the Authenticator number and waits up to 15 minutes for approval.
On success writes .state/.tokens.json and .state/.pkce.json.
Set SEATS_CLIENT_ID to choose the tenant client id (default: UoN seatsmaui.code.pkce).
"""
import base64
import hashlib
import json
import os
import re
import secrets
import sys
import time
import urllib.parse

import requests
from playwright.sync_api import sync_playwright

from credentials import STATE, credentials

ROOT = STATE.parent
AUTHORITY = "https://idv2.seats.cloud"
CLIENT_ID = os.environ.get("SEATS_CLIENT_ID", "seatsmaui.code.pkce")
REDIRECT_URI = "seatsmobileapp://callback"
SCOPE = "openid profile email SEAtSMobile SEAtS.profile offline_access"
STATUS = STATE / "login_status.txt"


def status(message: str) -> None:
	stamped = f"{time.strftime('%H:%M:%S')} {message}"
	print(stamped, flush=True)
	STATUS.write_text(stamped + "\n", encoding="utf-8")


def main() -> int:
	email, password = credentials()

	verifier = base64.urlsafe_b64encode(secrets.token_bytes(48)).rstrip(b"=").decode()
	challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
	state = secrets.token_urlsafe(16)
	nonce = secrets.token_urlsafe(16)

	params = {
		"client_id": CLIENT_ID,
		"redirect_uri": REDIRECT_URI,
		"response_type": "code",
		"scope": SCOPE,
		"code_challenge": challenge,
		"code_challenge_method": "S256",
		"state": state,
		"nonce": nonce,
		"login_hint": email,
	}
	url = AUTHORITY + "/connect/authorize?" + urllib.parse.urlencode(params)

	captured = []
	with sync_playwright() as playwright:
		browser = playwright.chromium.launch(headless=True)
		page = browser.new_context().new_page()
		page.on("request", lambda request: captured.append(request.url) if request.url.startswith("seatsmobileapp://") else None)
		status("opening authorize page")
		page.goto(url, timeout=60000)
		page.wait_for_timeout(2500)

		if page.query_selector("input[name=loginfmt]"):
			page.fill("input[name=loginfmt]", email)
			page.click("#idSIButton9")
			page.wait_for_timeout(2500)
		if page.query_selector("#i0118"):
			page.fill("#i0118", password)
			page.click("#idSIButton9")
			page.wait_for_timeout(4000)

		deadline = time.time() + 15 * 60
		announced = False
		while time.time() < deadline and not captured:
			body = ""
			try:
				body = page.inner_text("body")
			except Exception:
				pass
			if not announced:
				match = re.search(r"Enter the number if prompted\.?\s*(\d{1,3})", body, re.S)
				if not match:
					match = re.search(r"Approve sign in request.*?(\d{1,3})", body, re.S)
				if match:
					status(f"MFA_NUMBER={match.group(1)}  <-- approve this number in Authenticator")
					announced = True
				elif "Stay signed in" in body:
					status("no MFA prompt seen; handling stay-signed-in")
					announced = True
			if "Stay signed in" in body:
				try:
					page.click("#idSIButton9")
				except Exception:
					pass
			if "seatsmobileapp://" in page.url:
				captured.append(page.url)
				break
			page.wait_for_timeout(2000)

		if not captured:
			status("TIMEOUT waiting for MFA approval")
			browser.close()
			return 2

		redirect = captured[0]
		status("captured redirect")
		query = urllib.parse.parse_qs(urllib.parse.urlparse(redirect).query)
		code = (query.get("code") or [""])[0]
		received_state = (query.get("state") or [""])[0]
		if not code or received_state != state:
			status(f"bad redirect state/code: state_match={received_state == state}")
			browser.close()
			return 3
		browser.close()

	token_response = requests.post(
		AUTHORITY + "/connect/token",
		data={
			"grant_type": "authorization_code",
			"client_id": CLIENT_ID,
			"redirect_uri": REDIRECT_URI,
			"code": code,
			"code_verifier": verifier,
		},
		timeout=30,
	)
	status(f"token endpoint status {token_response.status_code}")
	if token_response.status_code != 200:
		status("token error: " + token_response.text[:300])
		return 4

	tokens = token_response.json()
	(STATE / ".tokens.json").write_text(json.dumps(tokens), encoding="utf-8")
	(STATE / ".pkce.json").write_text(json.dumps({"verifier": verifier, "state": state}), encoding="utf-8")
	status(f"SUCCESS access_token={len(tokens.get('access_token', ''))} refresh_token={len(tokens.get('refresh_token', ''))} expires_in={tokens.get('expires_in')}")
	return 0


if __name__ == "__main__":
	sys.exit(main())