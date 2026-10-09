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
v1.9.13 is the current build (watchers, screen diffs, autonomy, SKILL.md packs, hands-free mode, detached jobs, background sweeps, wider Android control) on `main`. CI (GitHub Actions) builds the signed release APK on every push and
publishes it as `Blaze-v<version>.apk` on the `apk` branch.

## v1.9.6 (versionCode 17): process registry + steadier offline commands
- Process registry (`Processes.kt`): every alarm, timer, routine, trigger, calendar event (phone + Google) and background job gets a short ID (A3, R4, G2, E1, C1, J1). Tools: `processes` (one line per item) and `process(id, delete|pause|resume|rename|reschedule|details)`. Details are only read when asked. Alarms carry "(Blaze A3)" in their label so they can be found and deleted.
- Offline commands: politeness is stripped ("torch on thanks bro"), a looser one-setting rule catches "can u put the torch on" / "kill the wifi", and the fast lane's Needle 3 tools were redesigned per Cactus's guide (one tool per setting, on/off enum defaulting to on, regex triggers).
- "speed stats" and "what's running" work instantly and offline.

## v1.9.5 (versionCode 16): speed batch 1
- Tool diet: core tools + only the tool groups a request needs (`load_tools` pulls in more). Toggle: `tool_diet`.
- Cache-friendly system prompt: fixed text first, clock/memory/device last.
- Sliding history window (24 msgs, moves in blocks so the cached prefix stays stable). Setting: `history_window`.
- Free router: fastest healthy model first, Groq first on short turns, 8 s connect / 20 s first-chunk timeout.
- Connection pre-warm when a message is sent or the panel opens.
- Parallel lookups: several read-only tools in one step (search, fetch, mail, calendar…) run at once.
- Streaming voice: spoken replies start on the first sentence instead of after the whole answer. Toggle: `speak_streaming`.
- Speed readout: route chip shows "first word X s"; `blaze_self speed_stats` gives the median of the last 20 turns.

## v1.7 (versionCode 8)
- Auto (free routing): ordered chain of free models with cooldowns/health, keyless Pollinations chat fallback
- Fast lane: on-device Cactus Needle 3 (runner bundled as libneedle.so, weights downloaded to filesDir/needle) for direct commands, works offline
- Faster phone control: screen auto-returned after each action, settle-wait on accessibility events, do_actions batching, old screens pruned, optional steps model
- Connections: Google (Gmail/Calendar/Drive/Contacts, PKCE loopback), GitHub (device flow), Spotify (PKCE), Notion & Slack (token), remote MCP servers, share_to_app
- Learning: chat summaries + search_past_chats, auto memory, learned skills with task replay, approval mode, weekly curator
- Notification brain (read/reply via notification actions, memory-only) and opt-in screen memory
- Secrets encrypted with Android Keystore (AES-GCM)
