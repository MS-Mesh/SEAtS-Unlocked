# Contributing

Thank you for helping improve SEAtS Unlocked.

## Before you start

- Open an issue to discuss substantial changes before implementing them.
- Use your own account and follow your institution's policies. Do not submit credentials, session data, signing keys, personal information, proprietary binaries, or screenshots containing private information.
- Keep changes focused and describe how they were tested.

## Development

1. Install JDK 21 and Android SDK 36.
2. From `app/`, run `./gradlew assembleDebug` (or `gradlew.bat assembleDebug` on Windows). Release signing requires a local `keystore.properties`; never commit signing credentials.
3. Run `python reverse/algorithm/seats_code.py` and `python reverse/algorithm/fingerprint.py` from the repository root when changing the reference algorithms.
4. Explain relevant device, emulator, or tenant-specific checks in your pull request.

## Pull requests

Describe the problem, the change, and the checks performed. Include screenshots for visible UI changes after removing private details. Do not commit generated build output.
