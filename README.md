# Blaze for Android

AI assistant app for Android (Kotlin + Jetpack Compose), package `com.blaze.assistant`.
Chat, voice ("Hey Blaze"), floating orb, and phone control through an AccessibilityService.

## Build
1. Install Android Studio (or JDK 17 + Android SDK 34).
2. Open this folder, let Gradle sync.
3. Release APK: `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
   Debug APK: `./gradlew assembleDebug`

Local builds are signed with `keystore/blaze.keystore`. That key is public (it was committed while the repo was
private), so it is only good for local testing. Official builds are re-signed in CI with a rotated key held in GitHub
Actions secrets (`BLAZE_SIGNING_P12`, `BLAZE_SIGNING_PASS`); see v1.14.2 in [CHANGELOG.md](CHANGELOG.md).
Phones already on a rotated build won't accept an APK signed only with the local key.

## Layout
- `agent/` – Agent loop, LlmClient (OpenAI-compatible), Providers, Settings, Memory, ChatStore, Secrets (Keystore encryption)
- `phone/` – BlazeAccessibilityService (read/tap/type), PhoneTools, Skills (SMS, calls, calendar, contacts)
- `ui/` – Compose screens (ChatScreen, SettingsSheet, Overlay orb, Theme)
- `connect/` – Blaze account, OAuth connectors, key sync, MCP client
- `browser/` – built-in browser and its agent tools
- `terminal/` – shell, Alpine Linux (proot), git, background jobs
- `link/` – Blaze Desktop pairing
- `voice/`, `routine/`, `widget/`, `notify/`, `assist/`, `proactive/`, `update/`

## Status
v0.9.0 (versionCode 49) is the current build on `main`. CI (GitHub Actions) builds the signed release APK on every push and
publishes it as `Blaze-v<version>.apk` on the `apk` branch.

**Version numbers.** From v0.8.0 Blaze for Android uses the same 0.x numbering as Blaze Desktop and Blaze CLI. The builds
before it were numbered 1.0–1.17.0 (CHANGELOG.md keeps those numbers); `versionCode` keeps counting up, so phones
update normally. Because the name went down, CI also publishes the same APK as `Blaze-v1.999.<versionCode>.apk`:
1.x builds compare names and see it as newer, and v0.8.0+ reads the build number from it and compares `versionCode`.

## Tests
`./gradlew testReleaseUnitTest` runs the JVM unit tests (`app/src/test`). CI runs them on every push and pull request.

## Changelog
What changed in each build: [CHANGELOG.md](CHANGELOG.md).
