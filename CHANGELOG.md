# Changelog

## Unreleased

- Reframed the app as an Android TV / Google TV AirPlay receiver.
- Bumped the app to `compileSdk 34` and `targetSdk 34`.
- Kept `arm64-v8a` and `armeabi-v7a` native output so Play bundles include 64-bit libraries while current 32-bit Android TV devices remain installable.
- Added Android App Bundle split configuration for Play release output.
- Added 16 KB native page-size linker alignment.
- Required Leanback support and forced landscape orientation for app activities.
- Replaced the placeholder TV launcher banner with an intentional vector banner.
- Added receiver quality profiles, screen-fit modes, applied audio sync controls, security modes, guest mode, idle clock, idle dimming, reduce motion, frame-rate matching, and visualizer preferences.
- Added `FIRST_RUN` and `PAIRING` receiver states plus last disconnect reason in session stats.
- Removed touch-only playback gestures from the activity control path.
- Added D-pad playback overlay actions for Stop, Screen Fit, Audio Sync, Settings, Diagnostics, and Traffic.
- Hid network details from the default ready screen.
- Added a direct one-time first-run setup launch before returning to the ready screen.
- Migrated first-run onboarding to a Compose D-pad flow using a conservative AndroidX TV foundation dependency.
- Migrated the default ready screen card to Compose while keeping playback and stream controls on the existing `SurfaceView`/View path.
- Migrated the active video playback overlay to a Compose D-pad surface with Stop, Screen Fit, Audio Sync, Settings, Diagnostics, and Traffic actions.
- Tightened video startup/render watchdogs so audio or control traffic does not trigger false decoder restarts while mirroring is static.
- Added a burn-in-conscious ready-screen clock with optional motion reduction.
- Added persisted trusted/blocked sender list storage, native remote-address sender gating, takeover rejection, and Trusted Only rejection where sender address is available.
- Added PIN-screen compatibility flow and explicit native-pairing limitations while keeping DNS-SD on the working no-PIN path.
- Added a dedicated audio-only screen with route display, forwarded AirPlay metadata, cover art, and optional low-cost visualizer.
- Added forwarded MediaSession metadata and album art for audio-only sessions.
- Added API 30+ surface frame-rate hint support with conservative sender cadence detection.
- Added Audio Stable buffer tuning.
- Added audio-route UI refresh callbacks.
- Added quick settings from the ready screen.
- Added diagnostics suggestions, network warnings, file export, verbose logging preference, and a restart discovery action.
- Updated README and docs for Android TV release posture, compatibility limits, and native PIN pairing limitations.
- Reworked the README around current Chromecast screenshots, added live portrait and landscape AirPlay captures, and removed the old standalone `docs/icon-256.png` image.
