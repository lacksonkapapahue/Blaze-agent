# Changelog

Newest first. Builds before v0.8.0 were numbered 1.0–1.17.0; `versionCode` has always counted up.

## v0.14.0: BlazeRouter on the phone
- **Blaze Desktop's router, on Android.** The same Go code (a port of OmniRoute, MIT; see THIRD_PARTY_NOTICES.md) is built by CI as `libblazerouter.so` and serves an OpenAI-compatible API plus its dashboard on 127.0.0.1:20128. It has every OmniRoute `auto/*` preset (auto/best-free, auto/fast, auto/best-chat…), category:tier ranking, chaos fan-out across providers, and last-known-good.
- **Runs where you choose:** *Auto* starts it inside Blaze's Alpine Linux (proot job), then Termux, then straight from Blaze. Data lives in `~/.blaze` (same layout as Desktop/CLI); your phone's model keys are handed to it at start, so nothing needs re-entering. Outside Alpine it uses public DNS (Android has no resolv.conf).
- **Settings › BlazeRouter** (also `/router`, the top-bar dot, avatar menu, + › Model): status, where to run it, Start/Stop, *Use as Blaze's brain* (auto/best-free), *Open dashboard* (in Blaze's browser, signed in), *Start with Blaze*, and a *Classic editor* link to the old Brain page (`/brain`), which is still there.
- **Blaze can drive it:** `blazerouter` tool (status, start, stop, use, dashboard), and about_blaze reports it.
- **CI:** vets and tests the router before building it.

## v0.13.0: Blaze knows itself
- **ABOUT YOU in every prompt:** what Blaze is (Android, Desktop and CLI, one Blaze account), this app's version, the paired PC and whether you're signed in, plus a rule: questions about Blaze, its apps or connecting phone and PC are answered, never bounced back with "what are you building?".
- **`about_blaze` tool:** a live report of every nook and cranny: the Blaze family and how to pair with Blaze Link, this app (mode, personality, projects), the phone, brains and which providers have keys (never the keys), Blaze account, connected apps, MCP servers, skills, memory and running things, files and folders, Alpine/Termux/dashboard, voice, every view and command, and the setup check. Ask for one section or everything. Allowed in Plan mode.
- **Tests:** `SelfKnowledgeTest`.

## v0.12.0: Calm shell like a messaging app (1.0 redesign, part A)
- **Top bar:** the B logo (back to the plain chat), a `Chat | Projects` switch, Search, the BlazeRouter dot (tap for models and keys) and your avatar. Tap *Chat* again for recent chats.
- **Avatar menu:** Account, Settings, BlazeRouter, All views, Phone control, Theme (tap flips light/dark, long-press follows the phone), Check for updates, Sign out.
- **Bubbles:** your messages on the right in an ink bubble; Blaze answers in plain text with no bubble, cards stay square hairline boxes.
- **One live work card per turn:** `● Working · 4 steps · 1m 12s ›`, tap to see each step; it settles to `Worked · 13 steps · 3m 11s` and the answer follows. Settings › *Show every step* adds the exact calls inside it.
- **Composer:** one bar with `+` and Send. The `+` menu holds Camera, Photos, Files, @ files, Paste image, Voice, Call Blaze, Plan/Build, Auto/Autopilot, Model, Views and New chat. The mode shows as a chip (`PLAN · AUTOPILOT`) only when it isn't the default. Typing `@` lists files in Blaze's home.
- **Nothing deleted: `/views`.** Every page that was in the sidebar (and Desktop's list) opens from one picker with a filter: Chats, Design, Memory, Jobs, Schedule, Agents, Accounts, BlazeRouter, Skills, Gallery, Browser, Doctor, Usage, Voice, Phone control, Proactive, Blaze Desktop, Commands, Dashboard, Blaze account, Settings. Shortcuts: `/design /jobs /schedule /accounts /doctor /router /skills /gallery /browser /search /projects` (plus the old `/memory /routines /settings /chats`). Search lists views too.
- **Blaze switches views itself:** new `open_view` tool. Visual work (a page, poster, design) opens **Design**, a live preview (phone or desktop width, reload, open in browser) with a box to ask for changes; other views ask first. A `DESIGN ✕ Exit` chip always returns to the chat.
- **Projects:** group chats around a goal with instructions Blaze follows in every chat inside it; `Working` and `Needs you` chips. Stored on the phone (`projects_json`), deleting a project keeps its chats.
- **Tests and screenshots:** `ViewsTest`; `ShotsTest` renders the shell on Robolectric (A12-sized) and CI pushes the PNGs to the `shots` branch.

## v0.11.0: Browser VPN by country, private tabs, Chrome-style tab grid
- **Browser VPN.** Import WireGuard configs (a `.conf`, several at once, or the `.zip` Proton/Mullvad download) in the browser's ⋯ › *VPN & privacy*, one per country, and pick a country. Only the browser goes through it: the tunnel runs inside Blaze (`wgproxy/`, user-space WireGuard on gVisor's netstack, shipped as `libwgproxy.so` and built by CI) as an HTTP proxy on 127.0.0.1 that the WebViews use via `ProxyController`. No Android VPN prompt; chats with your model don't use it. DNS goes through the tunnel too. After connecting, Blaze checks the real exit IP and country through the tunnel (Cloudflare trace) and fixes the country label if the file name guessed wrong. The flag shows next to the address bar.
- **Kill switch.** While the VPN is on, the browser never goes direct: if the tunnel fails to start or drops, pages fail to load (address bar shows !) until you reconnect or switch the VPN off. The VPN comes back on after a restart if it was on.
- **Private tabs.** Own WebView profile (separate cookies, storage, no cache), no history, wiped when the last private tab closes (and at start, in case Blaze was killed). Dark address bar. Phones with an old Android System WebView (no multi-profile) get history-free private tabs with a warning that cookies are shared.
- **Location masking** (on by default) in private tabs, and in every tab while the VPN is on: a script that runs before page scripts in every frame removes WebRTC (it would leak the real IP around the proxy), answers geolocation with the VPN country's capital (a few km of jitter) or denies it with no VPN, and reports that country's time zone (`Intl`, `Date` offsets, `toLocale*`) and language (`navigator.language(s)`). Not covered: the `Accept-Language` header, and time zone inside web workers.
- **Chrome-style tab switcher.** Full-screen two-column grid of rounded cards with favicon, title, close button and a page preview; normal / private switch at the top with counts; swipe a card sideways to close it; new tab, close all, Done.
- **Blaze can use it:** `browser_vpn` (status / connect to a country / disconnect) and `browser_open private=true`.
- **Tests:** `BrowserVpnTest` (config parsing, endpoint resolution, country guessing, mask script); Go tests run a real WireGuard handshake between two in-process peers and fetch through the proxy (plain HTTP and CONNECT). CI runs them before the APK build.

## v0.10.0: Builds Android apps, every Desktop skill, skills follow your account
- **Blaze builds Android apps** (skill `android-app-builder`, needs GitHub connected). It creates a private repo (`github_create_repo`), sets up a Kotlin + Compose project from the bundled template (`setup.sh`), writes the code, pushes, and lets GitHub Actions build it: every push builds a signed APK and publishes it as the repo's latest release. `github_ci` waits for the build and, when it fails, returns the failing step and the compiler errors so Blaze fixes and pushes again (up to 5 rounds). `github_install_apk` downloads the APK and opens Android's installer. Code: `connect/GitHubApps.kt`. CI now also builds an app from the template on every push (`app-template` job), so the template can't silently break.
- **GitHub `workflow` permission.** Pushing a new app's Actions file needs it; Blaze asks for it when connecting GitHub through blaze-auth 0.8.0 or newer. Already connected? Disconnect and reconnect GitHub once.
- **All of Desktop's skills.** Added browser-game, diagrams, floor-plan, motion-graphics, music-audio, paper-sketch, pixel-art, print-design, shell-sysadmin and three-d-design, adapted to the phone (previews through `preview_server`, tools installed in Alpine; 3D part picking and shell-sysadmin's host work stay on the PC). skill-creator gains Desktop's "combining skills" section (version 2). 29 bundled skills in all.
- **Skill sync.** Your own skills (learned by Blaze, written or installed by you; not the bundled library) follow your Blaze account to Desktop, CLI and other phones through blaze-auth `/v1/skills` (Worker 0.8.0): encrypted like model keys (`gzip` + AES-256-GCM, aad `blaze-skills-v1`, same per-account key), newest change per skill wins, deletions travel, `~/skills/.sync.json` tracks what was synced. Syncs at start (hourly), after sign-in, a few seconds after a skill is written, installed or deleted, and from Settings › Blaze account › *Sync skills now*. Code: `connect/SkillSync.kt`, `connect/SkillMerge.kt`. Until the Worker is updated it says sync isn't switched on yet.
- **Tests:** `SkillMergeTest`.

## v0.9.2: Plain words while working, no fake tool calls, swipe to reply
- **The status says what Blaze is doing.** "Thinking" and "Working" are gone from the chat, the top bar, the floating panel and calls. In their place is one plain line: "Reading your message", "Searching the web for …", "Going through the search results", "Writing the reply". New `Agent.status` flow, set from each step and between steps (`Friendly.between`).
- **Never a command on screen.** The model's narration is cleaned before it becomes a step (`Friendly.plain`): code blocks, `$ …` lines, inline code, tool names and links are removed, and a sentence that is mostly a command is dropped. Unknown tools read "Working on it" instead of their snake_case name. The folded steps view no longer shows the exact call under each step; that stays only with Settings › *Show every step*.
- **Fake tool calls are sent back.** A final reply that writes a tool call or shell command as text, uses agent syntax ("Action: …"), or claims "I ran / installed / sent …" when no tool ran this turn is returned to the model once with a nudge to call the tool for real or answer in plain language (`agent/FakeCall.kt`). Shell blocks stay allowed when the user asked for code or a command.
- **No flash of raw calls.** A reply that starts streaming as a tool call (`{"name": …`, `run_shell(`, `$ …`) is no longer shown live.
- **Swipe right to reply, like WhatsApp.** Grab any message (yours or Blaze's) and slide it right: a reply arrow fades in, the phone ticks past the threshold, and on release the message is quoted in the composer. Works while Blaze is busy too, so you can quote something to steer it. Tables and code blocks still scroll sideways first (`ui/SwipeToQuote.kt`).
- **Tests:** `NaturalStatusTest`.

## v0.9.1: Keys survive a phone move safely
- **Restored keys no longer fail silently.** API keys and sign-ins are encrypted with a Keystore key that never leaves the phone, so after an Android backup restore or device transfer they decrypted to nothing. At start Blaze now spots secrets from another phone, drops them along with their key-sync stamps (so the Blaze account's copy wins instead of being overwritten with nothing), and posts one heads-up: sign in to the Blaze account to sync model keys back, then reconnect accounts.
- **Encryption fallbacks are visible.** If the Keystore fails, `Secrets.encrypt` still keeps the key, but now logs it, and the self-check reports it under *Saved keys*.
- **Unencrypted links flagged.** Plain HTTP stays allowed for the on-device runner, OAuth loopback and gateways or MCP servers on your own network. The new self-check item *Unencrypted links* (`connect/UrlSafety.kt`) flags a custom model URL or MCP server on `http://` with a public host.
- **Fixes:** `key_rest` (resting marks) was treated as a secret and encrypted at every start, so the marks were lost on restart. `keys_extra_<pid>` (extra keys) wasn't treated as a secret, so Backup › Export wrote them as blobs no other phone could read.
- **Tests:** `UrlSafetyTest`, `SecretsPrefTest`.

## v0.9.0: MCP servers, the CLI/Desktop way
- **Same config as Blaze CLI and Desktop.** MCP servers are kept in the `~/.blaze/mcp.json` shape, `{"mcpServers": {"name": {"url": …, "headers": {…}}}}` (optional `"type": "http" | "sse"`, `"disabled": true`), stored encrypted on the phone. Paste an mcp.json in Settings › MCP servers to import it, or copy the phone's list out. Entries the phone can't run (local `command` / stdio servers) are kept and shown as needing a PC, so a list from your PC survives the round trip.
- **Synced through the Blaze account**: the list rides in the same encrypted `/v1/keys` blob as the model keys, as entry `mcp` (k = [mcp.json text], newest list wins). Older clients pass unknown entries through untouched.
- **Transports**: Streamable HTTP (sessions, SSE or JSON replies, batches, session-expiry retry) and the older HTTP+SSE transport (endpoint event, replies on the stream, server pings answered). Auto tries Streamable HTTP and falls back to SSE; URLs ending in `/sse` go straight to SSE. Headers carry bearer/OAuth access tokens or API keys. Tool lists are paged (`nextCursor`).
- **Tools named `mcp__<server>__<tool>`** (≤ 64 chars; a hash keeps long names unique). Legacy `mcp_…` names are still recognised.
- **Desktop's approval rules**: tools that declare `readOnlyHint` run without asking (and are the only MCP tools allowed in Plan mode); other tools show the confirm card; on their default they pass in Autopilot; `destructiveHint` tools always ask. Each tool can be set to Ask, Always or Off in Settings (tap a server).
- Servers saved by older builds (name + URL + bearer token) move into the new config automatically.
- **Unit tests** now run in CI (`testReleaseUnitTest`): config parsing and round trip, naming, approval, SSE parsing, JSON-RPC reply matching, result flattening and the sync merge.

## v0.8.0: Learns like Desktop, chats named by topic, heads-ups you can reply to
- **Proactive memory, the Desktop v0.7 way** (`agent/Learn.kt`, a port of Desktop's `internal/learn`). The prompt now carries the MEMORY & SKILLS rules: save lasting facts silently with their context, keep the user's certainty, replace a changed fact (`remember(fact, replaces=…)`) instead of piling up duplicates, forget every match on request, and write a skill when a method is taught or a workflow repeats.
- **Cheaper learning.** The background review used to run after nearly every answer. A local check with no model call (`Learn.decide`) now runs first: small talk, turns that already saved, and turns with nothing durable are skipped; a skill is only proposed when the user taught a method or a 5+ step workflow repeats (workflow signatures, last 40 kept). Only related memories go into the review prompt.
- **Safer memories.** Every auto-saved fact passes Desktop's filter (`Learn.accept`): no keys, passwords, PINs, codes or card/account numbers, nothing "only true today", no task logs. `remember` refuses secrets even when asked. Auto-written skills need a kebab name, a real description and at least 3 steps.
- **Chats named by topic** (`agent/Titles.kt`, port of Desktop `titles.go`). After the first exchange and every 4 turns after that, one small background call titles the chat by its dominant theme (≤ 6 words) and writes the one-line summary `search_past_chats` uses. Chats you rename are never retitled. Settings › Memory & learning › *Name chats by topic*.
- **Heads-ups you can reply to.** Tips, morning/evening briefs, routine and job alerts now land in one **Heads-ups** chat as they're posted. Replying from the notification (or tapping its suggestion) continues that chat, and the model is told every heads-up it sent since you last wrote plus the one you're answering, so "yes", "later" or "who's that?" make sense. Before, each reply opened a blank chat and the suggestion button ran in a separate hidden one. Notification replies skip the offline fast lane, which can't see that context.
- **5 more Desktop skills**: landing-page-builder, slide-deck, web-design-blaze-classic, web-design-teamofkeys and image-assets (adapted to the phone's Alpine Linux). They make HTML you can open with `preview_server`. Bundled skills can now ship extra files (templates, starters, design tokens) listed under `files` in `assets/skills/index.json`; read them with `skill_file`. shell-sysadmin stays desktop-only.
- **Version numbers match Desktop and CLI** (0.x); the updater compares build numbers (see Status).

## v1.17.0: Catching up with Blaze Desktop and CLI
- **Connectors are OAuth only** (like Desktop v0.5.0 / CLI v0.4.0): Google, GitHub, Spotify and Notion connect with one tap through blaze-auth. The browser signs in, the Worker swaps the code with its own client secret, and the tokens come back to a random 127.0.0.1 port through a one-time code bound to Blaze's PKCE verifier. Refreshes go through the Worker too (`/oauth/<id>/refresh`). The old manual setup (your own client ID, pasted tokens) is gone from the app; connections made that way keep working until you disconnect them. Providers the Worker hasn't switched on (Slack for now) show "coming soon".
- **Bundled skill library** (Desktop v0.7.0, CLI v0.5.0): 13 of Desktop's skills that fit a phone ship in the app (`assets/skills`) and are seeded into `~/skills`: deep research, email drafting, planning, documents, data analysis, API integrations, skill creator, code review, debugging, git/PR, tests, refactoring and web audits. Seeding is version-aware (`~/skills/.bundled.json`): upgrades only skills you haven't edited, never brings back one you deleted.
- **Settings › Skill library**: switch any skill off, delete it, or restore a bundled one. Switched-off skills leave the prompt and `use_skill` refuses them.
- **Leaner skill prompt** (like Desktop/CLI): with more than 12 skills, only the ones that match the request go in with their descriptions; the rest are listed by name.

## v1.16.0: Model keys follow your Blaze account
- API keys for the model providers (Gemini, Groq, Cerebras, OpenRouter, Mistral, NVIDIA, DeepSeek, Kimi, OpenAI, Claude, Grok), extra keys included, now sync with Blaze Desktop and Blaze CLI through the Blaze account: add a key on the PC and it works here, and the other way round.
- When: right after signing in, a few seconds after a key is added, changed or removed, and at start (at most hourly). Settings › Blaze account › **Sync keys now** shows the outcome.
- The keys are encrypted on the phone (AES-256-GCM, AAD `blaze-keys-v1`) before upload; blaze-auth (`GET/POST /v1/keys`, Worker 0.6.0) stores only the blob. Per provider the newest change wins; keys saved before syncing existed are merged with the account's, never dropped. Not end-to-end: the service hands the blob key to signed-in sessions.
- Connector tokens, the session and PC pairing never sync. Until the Worker with `/v1/keys` is deployed the app reports that sync isn't switched on yet and keeps keys local.
- Code: `connect/KeySync.kt` (same format and merge rules as `internal/keystore/sync.go` in Blaze-dekstop and Blaze-cli); change stamps `keysync_t_<provider>` in settings.

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
