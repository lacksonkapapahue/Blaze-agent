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
v1.15.0 (versionCode 45) is the current build on `main`. CI (GitHub Actions) builds the signed release APK on every push and
publishes it as `Blaze-v<version>.apk` on the `apk` branch.

## v1.15.0: Blaze account (shared with Blaze Desktop)
- New Settings › Blaze account: sign in or create an account with email + password, or Continue with Google / GitHub, using the same blaze-auth service as Blaze Desktop (`https://blaze-auth.blaze-app.workers.dev`), so one account works on both.
- Google/GitHub sign-in runs in the browser with PKCE; the redirect comes back to a random `127.0.0.1` port and the one-time code is redeemed with the verifier. No client secret lives in the app.
- The session token is Keystore-encrypted (`conn_tok_blaze_session`); the user's name and email are cached for the screen. The session is re-checked at start and cleared if it was signed out or deleted elsewhere.
- Sign out (ends the session at the service too) and Delete account. Connected accounts, keys and chats on the phone stay.
- Code: `connect/BlazeAccount.kt`, `ui/AccountPage.kt`. Set `blaze_cloud_url` to point at a local Worker for testing.

## v1.14.4: Notification replies keep the conversation
- Replying from a Blaze notification now continues the chat that sent it, even if a different chat is open, instead of landing in whichever chat was on screen.
- Replying to a heads-up (proactive, routine or job alert) opens a new chat that starts with Blaze's message, and the model is told what it said, so the answer makes sense.
- If that chat is still working, the reply waits for it instead of being dropped.

## v1.14.3: First build on the rotated key
- The first build signed with the new key (v3 lineage from the old key). Install it once and from then on the phone only accepts Blaze builds signed with the new key.

## v1.14.2: Signing key rotation
- The original signing key is public (it was committed when the repo was private), so CI now signs with a new key kept only in GitHub Actions secrets (`BLAZE_SIGNING_P12`, `BLAZE_SIGNING_PASS`), using APK Signature Scheme v3 key rotation.
- Phones on older builds update normally. Once a phone is on a rotated build, Android refuses any APK signed only with the old key (the old key keeps installed-data but loses rollback, permission, shared-uid and auth).
- Android 8 (API 26–27) can't rotate keys, so it still verifies the old key.
- If the secrets are missing, CI stays on the old key and dry-runs the rotation with a throwaway key.

## v1.14.1: Hardening for the public repo
- Text and files shared into Blaze from other apps now wait in the message box for your Send instead of running on their own (any app can share, so it could carry instructions you didn't write).
- `/update` works without a GitHub connection (public repo; connecting only raises GitHub's rate limit). Before installing, the download is checked: it must be Blaze, newer than the installed build, and signed by the key the installed Blaze trusts (or a newer key in its lineage).
- CI: read-only token by default; publishing to the `apk` branch, releases and the failure issue only run on pushes to this repo's `main`, never on pull requests or forks; checkout doesn't keep the token.

## v1.14.0: Modes and background agents
- Composer modes (`agent/Modes.kt`, port of desktop modes.go). **Plan** = read and look only: the tool list is filtered and every other call is refused at dispatch (`Session.runTool`), the fast lane is skipped, and git is read-only (`planGit`). **Build** = full access (default). **Auto** = confirm cards (default). **Autopilot** = ordinary confirms pass, destructive ones (delete, wipe, uninstall, money, messages and calls in the user's name) keep the card; `ask_user` shows its card for 60 s, then Blaze decides itself; a reply that only asks a question gets "decide yourself" (max 8); runs capped at `autopilot_steps` (150) and `autopilot_minutes` (45). Prefs `work_mode`, `approval`. Switches: chips above the message box (`ModeBar`, pref `mode_bar`), `/plan [goal]`, `/build`, `/autopilot`, `/auto`.
- Background agents (`agent/BgAgents.kt`, port of desktop internal/agents + bgagents.go). Tools `start_agent`, `list_agents`, `check_agent`, `message_agent`, `cancel_agent` (core) and `/agent <task>`, `/agents`. Each agent runs in its own chat (`⚙ B1 · title`, IDs from Processes kind `agent` = B), 1 at a time (2 with 6 GB+ RAM), the rest queue. Live card in the chat (`ui/AgentCard.kt`): current step, time, steps, Steer / Stop / Open, Follow up when done. Confirms and questions from an agent are labelled with its ID. The result wakes the chat that started it (queued behind a turn in progress). State in `filesDir/bg_agents.json`; running agents resume after a restart. Agents can't start agents; an agent started in Plan stays Plan. Pref `bg_agents`.
- Also in this build (from the unmerged v1.12.1 branch): `call_control` (speaker, mute, end, answer, decline, hold, keypad, call volume for Blaze calls and phone calls), "let's get on a call" starts a Blaze call, clipboard image paste fix, background clipboard access (`phone/ClipAccess.kt`) with get_image / set_image / history.

## v1.13.0: Calm chat
- Plain steps: while working, one shimmering plain-English line ("Searching the web for …", `agent/Friendly.kt`); when done, "Worked for 12s · 5 steps", tap for each step and its exact call (`ui/CalmSteps.kt`). Setting "Show every step" (`show_steps`, off) brings back the full list.
- Answer cards: long tables, lists, code and reports fold into a clipped card with a fade; tap to expand, Copy gives the Markdown. Tables now render as grids. Setting "Fold long answers" (`fold_answers`, on). `ui/AnswerCards.kt`.
- Sun/moon button in the top bar: tap flips light/dark, long-press follows the phone again (`theme_mode`).
- `/update` and the `update_blaze` tool: newest `Blaze-v*.apk` from the `apk` branch (GitHub connector token), Checking → Downloading N% → Installing card, PackageInstaller install, plain errors with Retry (`update/Updater.kt`, `ui/UpdateCard.kt`).

## v1.12.0: Blaze Desktop pairing
- Settings › Blaze Desktop › Scan QR code (from Blaze Desktop › Phone), or paste the pairing link. Code in `link/DesktopLink.kt`, screen in `ui/DesktopPage.kt`.
- `ask_pc`: runs a Blaze turn on the paired computer (files, code, programs, installs); the PC's approval requests show as confirm cards here. Tool group `pc`.
- The other way: while Blaze runs, it long-polls the PC; Blaze Desktop's `phone_ask` requests run in a "From <PC>" chat and the answer goes back. Toggle: `pc_inbox`.
- Direct HTTPS to the PC (port 7377), pinned to the certificate fingerprint in the QR code; the token is stored encrypted (`conn_tok_pc`). Same Wi-Fi, or anywhere over Tailscale.

## v1.9.15: Batch 1, dev toolkit hardening
- `self_test` tool + Settings → Device & diagnostics → Run self-check: API key, internet, AI, terminal, Linux, git/GitHub, phone control, notifications, permissions, exact alarms, storage, voice, browser.
- `schedule_command`: cron for shell commands (time + days, or every N min ≥ 15), no AI involved, survives reboots, notifies always / on failure / never. Listed with routines.
- Terminal screen: session tabs (+ / ×), a read-only **Blaze** tab showing every command Blaze runs live, extra-keys row (ESC, CTRL, TAB completion, ↑↓ history, ←→, | ~ / - > & ; $ * "), Ctrl-C/D/L/Z/U.
- `preview_server`: opens http://127.0.0.1:PORT in the built-in browser; background jobs that print a port suggest it.
- In-app permission prompts (`device/Perms.kt` + transparent `PermissionActivity`) for location, contacts, calendar, SMS, call log.
- `add_contact`, `edit_contact`, `move_event`, `delete_event` (each asks for approval).
- Cleanup: `job_log` merged into `terminal_jobs id=N`; `agent/ToolGuide.kt` appends a "not for X, use Y" line to confusable tools; every tool call is guarded (crash → "Error in tool + Fix:", empty → explicit note).

## Tool map (v1.9.12–1.9.14)
- Terminal: `run_shell` (keeps cwd, `~/.blaze_env`, hints), `run_code` (python/node/sh, auto-installs), `file_read/write/edit/list/search/delete`, `job_input`, `terminal_jobs`, `terminal_stop`, `http_request`, `download_file`, `export_file`, `terminal_status`, `git` (connected GitHub), `termux_run`.
- Phone: `get_location`, Quick Settings tile toggles for Wi-Fi/Bluetooth/airplane/mobile data/DND.

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
