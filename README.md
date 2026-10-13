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
- `agent/` – Agent loop, LlmClient (OpenAI-compatible), Providers, Settings, Memory, ChatStore, Secrets (Keystore encryption), LongChat (rolling summary, FTS chat search, artifacts), Mind + MindReview (one mind: journal, memories, context ladder, idle review)
- `phone/` – BlazeAccessibilityService (read/tap/type), PhoneTools, Skills (SMS, calls, calendar, contacts)
- `ui/` – Compose screens (ChatScreen, SettingsSheet, Overlay orb, Theme, Kit: Desktop's page/rows/empty-state pieces, MemoryPage + MemGraph: the memory graph)
- `connect/` – Blaze account, OAuth connectors (incl. Google Classroom), key sync, MCP client
- `browser/` – built-in browser and its agent tools
- `terminal/` – shell, Alpine Linux (proot), git, background jobs
- `link/` – Blaze Desktop pairing
- `life/` – life logs: LifeLogService (on-device transcription, foreground, auto-pause), LogChunker, brainstorm summaries
- `voice/`, `routine/`, `widget/`, `notify/`, `assist/`, `proactive/`, `update/`

## Status
v0.24.1 (versionCode 73) is the current build on `main`. CI (GitHub Actions) builds the signed release APK on every push and
publishes it as `Blaze-v<version>.apk` on the `apk` branch.

**Version numbers.** From v0.8.0 Blaze for Android uses the same 0.x numbering as Blaze Desktop and Blaze CLI. The builds
before it were numbered 1.0–1.17.0 (CHANGELOG.md keeps those numbers); `versionCode` keeps counting up, so phones
update normally. Because the name went down, CI also publishes the same APK as `Blaze-v1.999.<versionCode>.apk`:
1.x builds compare names and see it as newer, and v0.8.0+ reads the build number from it and compares `versionCode`.

## One mind
One continuous conversation instead of separate chats (projects keep their own threads; **Fresh start** clears the context, nothing is deleted). `filesDir/mind.db` holds the **journal** (every turn, old chats imported once) and the **memories** table (profile, preference, person, project, decision, fact), both searchable. The prompt carries only a pinned core and a subject index; Blaze looks the rest up with `memory_search` / `search_chat`. Old chats stay on disk and in the journal (Blaze finds them with `search_chat`); since v0.24.0 there is no chat list or Archive screen, and `/clear` empties the screen without forgetting anything. Settings › Memory & learning › *Review past conversations when idle*. Shared schema and prompt text: `docs/plans/plan-one-mind.md` in Blaze-dekstop.

## Messaging-style chat (v0.24.0)
The chat reads like a messaging app in Blaze's classic theme: your messages are ink bubbles on the right, Blaze's are soft bubbles on the left, runs from one side group tightly with asymmetric corners and one quiet timestamp, and the composer is a rounded pill (+, mic that turns into Send) whose placeholder rotates through on-device suggestions (your accounts, apps, birthdays, what's coming up). The top **Chat | Projects** toggle only switches tabs. `/clear` (or + › Clear screen) empties the screen, with Undo for a few seconds.

## Background browser and phone control
Blaze's browser works in the background (offscreen tabs). When a page needs something only you have, an **Action required** card appears in the chat (and a notification if you're away); what you type goes straight into the page, the AI only sees `{{secret:…}}` placeholders, and it's saved per site in **Settings › Saved logins** (Keystore-encrypted; one-time codes never). Sites that refuse passwords get Google/passkey options instead. Phone control follows an orient loop: a compact look at the screen before acting and a check after each step. A visible pointer shows where Blaze is about to tap; touch the screen to pause, drag the pointer, double-tap it to click there (Settings › Phone control › Show the pointer).

## Memory graph
Settings › Memory & learning (or `/memory`): an animated graph of everything Blaze remembers, after Blaze Desktop's Memory view. Tap a dot to view, edit or forget a memory; Graph | List and search. It draws only while you look at it (≤ 20 fps, stops after 12 s idle or when the app is in the background).

## Notification replies
Settings › Notification replies: with notification access on, Blaze reads chat notifications (WhatsApp, SMS, Telegram, Messenger…) with their MessagingStyle history, keeps a rolling log per conversation in the journal (kind `notif`), links senders to people memories, and replies through the notification's own reply box. Every send shows a confirm card unless you switch on auto-reply for that conversation. Codes, PINs and bank / mobile-money messages are never stored. Tools: `notif_conversations`, `notif_read`, `notif_reply`.

## Life logs
Say "start logs" / "live logs" (or `/logs`, Settings › Life logs): a foreground service with a persistent notification (Pause, Stop) transcribes what the mic hears on the phone for free (Vosk small English model, shared with the wake word and downloaded once; Android's SpeechRecognizer until then). Only text is saved, as journal chunks (kind `life_log`) split by silence or topic; the idle review turns them into memories marked "(inferred)". "Brainstorm with me" (`/brainstorm`) writes ideas, decisions and next steps to the chat and memory when it stops. Pauses during calls, while another app records, while Blaze listens or speaks, and below 15% battery unless charging.

## Views + skills
Blaze always uses its views: flashcards and quizzes go into a **Learn** deck, pages into **Design**, reminders into **Schedule**, pictures and beats into the **Gallery**, methods into **Skills**, facts into **Memory**; the view opens and the chat gets a short answer. Each request is matched to its view on the phone (no extra model call), and to the skills that fit it (bundled, your own and learned; switched-off skills are skipped); the best 1-3 are read in full and shown as "Skills: …" on the work card. Code: `agent/ViewRouter.kt`, `agent/SkillMatch.kt`. Blaze never claims it made, changed or opened something in a view unless that view's tool actually worked this turn (`agent/ClaimGuard.kt`), and leaked reasoning tokens from some free models are stripped from replies (`agent/ReplyClean.kt`).

## Design
Design is for anything visual or interactive, like Blaze Desktop's Design tab: 3D scenes and models (three.js with orbit controls, built from a parts list so you can say "show the engine" or "make it taller"), 2D art, sites, UI mockups, diagrams, charts, posters, slides, floor plans and product mockups. The `design` tool (`agent/Designs.kt`) writes `~/designs/<name>/index.html`, keeps the current design and its last 10 versions (undo), and opens the Design view; short follow-on edits change the same design. three.js is bundled in the APK, so 3D works offline. Designs load from a private https origin inside the app (`ui/DesignServe.kt`), so the page and three.js are same-origin; on phones whose WebView has WebGL 2 turned off (like the Galaxy A12's PowerVR GPU) Blaze switches to three.js r162 on WebGL 1, and if WebGL is off altogether it draws the parts natively (drag to turn, pinch to zoom) with the real error and Retry, so a 3D design always shows something. The last render error is kept in `~/designs/.last_error.txt` for Blaze to read.

## Auto (free routing)
Settings › Brains › Auto spreads turns across every free model your providers offer, not one default per provider. Each provider's model list (OpenAI-compatible `/models`, Gemini's models API, OpenRouter's `:free` / zero-price models, Pollinations' public list) is cached in `filesDir/model_catalog.json` for 24 h and refreshed in the background; only chat-capable text models are kept, tool support is read from the API or a known list, and the curated defaults lead each provider. Providers take turns, each offering its next untried model; a model that failed or hit a limit is remembered in `filesDir/model_state.json` and isn't tried again until its cooldown passes (24 h for a retired model), and tool turns use tool-capable models only. When everything is cooling Auto waits once (≤ 25 s), then says when the next model frees up. The page shows per provider how many models are available and cooling, with **Refresh models**; the chain (provider order and starting model) is still yours to edit. Code: `agent/FreeRouter.kt`, `agent/ProviderCatalog.kt`, `agent/ModelMemory.kt`.

## Student pack
Settings › Learn (or `/learn`): flashcards with spaced repetition, Google Classroom (switch on, then reconnect Google once) and low-data mode. Skills: study-planner, tutor, lecture-notes, essay-coach, group-project, classroom-sync.

## Marketer & seller pack
Settings › Seller (or `/seller`): brand kit, the weekly store digest job, and WhatsApp replies drafted on Blaze Desktop and sent from the phone with your tap ([docs/blaze-link-messages.md](docs/blaze-link-messages.md)). Skills: content-calendar, product-listing, ad-lab, research-brief, whatsapp-replies, weekly-digest.

## Tests
`./gradlew testReleaseUnitTest` runs the JVM unit tests (`app/src/test`). CI runs them on every push and pull request.

## Changelog
What changed in each build: [CHANGELOG.md](CHANGELOG.md).

## Credits
BlazeRouter is a Go port of [OmniRoute](https://github.com/diegosouzapw/OmniRoute) (MIT); see THIRD_PARTY_NOTICES.md.
