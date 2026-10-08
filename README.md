# Blaze for Android

AI assistant app for Android (Kotlin + Jetpack Compose), package `com.blaze.assistant`.
Chat, voice ("Hey Blaze"), floating orb, and phone control through an AccessibilityService.

## Build
1. Install Android Studio (or JDK 17 + Android SDK 34).
2. Open this folder, let Gradle sync.
3. Release APK: `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
   Debug APK: `./gradlew assembleDebug`

The release build is signed with `keystore/blaze.keystore` (config in `app/build.gradle.kts`).
**Always use this keystore**, otherwise updates won't install over the existing app.
Keep this repository private because it holds the signing key.

## Layout
- `agent/` – Agent loop, LlmClient (OpenAI-compatible), Providers, Settings, Memory, ChatStore, Secrets (Keystore encryption)
- `phone/` – BlazeAccessibilityService (read/tap/type), PhoneTools, Skills (SMS, calls, calendar, contacts)
- `ui/` – Compose screens (ChatScreen, SettingsSheet, Overlay orb, Theme)
- `voice/`, `routine/`, `widget/`, `notify/`, `assist/`

## Status
v1.6 is the last released build. v1.7 (free model routing, Needle on-device fast lane, faster screen loop,
connectors, self-learning) is in progress on `main` and may not compile until it's finished.
