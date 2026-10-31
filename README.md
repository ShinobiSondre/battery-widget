# Battery Widget 🔋

A home-screen widget for Android that shows the battery of your phone, your AirPods (left, right and case) and any other connected Bluetooth device, like a watch or headphones.

## Features
- **Three styles:** Rings, Bars or one Big number for whatever needs charging first.
- **Background slider** from fully see-through to solid. Text gets a soft shadow when it's see-through, so it stays readable on any wallpaper.
- **AirPods support** without an iPhone: the app listens for the small status message AirPods broadcast (the one iPhones use for their pop-up), in steps of 10%.
- **Other Bluetooth devices** show up when they report their battery to Android.
- **Tap the widget** to refresh it. It also refreshes when a Bluetooth device connects or disconnects, and every 15 minutes.
- **Updates itself** from this repo's Releases, like FaithScroll.

## Install
1. Open **Releases** (right side of the repo page) on your phone and download the latest `BatteryWidget-*.apk`.
2. Open it and allow "install from unknown sources" if asked.
3. Open the app, tap **Allow** for Nearby devices, then **Add widget to home screen**.

Every push to `main` builds a new APK automatically (see the **Actions** tab), and the app offers it the next time it opens.

## Notes
- AirPods only broadcast their battery while they're out of the case or the case lid is open.
- The app's signing key is kept in the repo's GitHub secrets (`KEYSTORE_BASE64` and `KEYSTORE_PASSWORD`), not in the code. Keep a copy somewhere safe: every update must be signed with the same key, or it won't install over the old version.
