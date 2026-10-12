# Blaze Android status

- **Now:** v0.20.0 (versionCode 62), branch `orient-browser`: orient loop for phone control, background browser with in-chat secure forms + Saved logins, password-free sign-in handling, phone-control drop fix, heads-ups only speak when switched on. See CHANGELOG.md.
- **Before:** v0.18.0 (versionCode 60), PR #20 (branch `one-mind`, squash-merged on green): one mind (one continuous conversation, journal + memories tables in `mind.db`, memory_search/save/update, search_chat over the journal, Archive, after-turn extraction, WorkManager idle review, context ladder). See CHANGELOG.md.
- **Shipped:** v0.15.0 long chats, PR #17, merge 2a86aaf. v0.16.0 student pack, PR #18, merge a499beb. v0.17.0 marketer & seller pack, PR #19, merge 2be6525.
- **Needs Luke (Google Cloud project blaze-511220):** enable the Classroom API and add the scopes `classroom.courses.readonly` + `classroom.coursework.me.readonly` to the OAuth consent screen (Testing mode), and make sure the blaze-auth Worker passes them through.
- **Plan (Luke, 2026-10-12: "build the rest", merge on green, don't ask between steps):** each its own PR, version bump, tests, screenshots.
  1. v0.15.0 long chats: shipped (#17).
  2. v0.16.0 student pack: shipped (#18).
  3. v0.17.0 marketer & seller pack: shipped (#19).
  4. v0.18.0 one mind: PR #20. Spec: spec in Blaze-dekstop `docs/plans/plan-one-mind.md` (shared schema + prompt text).
  5. v0.19.0 visual parity (branch `visual-parity`, another agent) with Desktop: the animated memory graph over the memories table, polish of other screens. Not in v0.18.0 on purpose.
- **One mind notes for the next PR:** memories live in `filesDir/mind.db` (`Mind.Db`: tables journal, memories, meta + FTS4 `journal_fts`, `memories_fts`); the graph should read `Mind.all()` (kind, subject, related). `Memory` is a view of the table and mirrors it to prefs `memory`. Old chats are Archive (`Agent.isLive(id)` false). Idle review = WorkManager unique works `mind_review_power` / `mind_review_wifi`.
- **Screenshots:** CI pushes PNGs + logs to the `shots` branch on every build (`ui/ShotsTest`, BLAZE_SHOTS=1). Review them after every build.
- **Hard rules:** never change applicationId or signing; never touch keystore/ or the CI "Rotate signing key" step; never force-push main; prefs stay backward compatible; free providers only.

- **Needs Luke:** enable the Classroom API in Google Cloud project blaze-511220 and add the scopes classroom.courses.readonly and classroom.coursework.me.readonly to the consent screen; check that the blaze-auth Worker passes them through.

- **Next (v0.21.0, separate PR, Luke 2026-10-12): visible pointer for phone control.** Accessibility overlay pointer (Blaze look) that glides to the target, re-checks the screen (orient) and shows a short ripple before tapping; any touch on the screen or the pointer pauses Blaze into manual mode; drag moves it (never clicks); double-tap while paused clicks there, then Blaze looks and continues; small Resume / Stop controls; long press shown, swipe drawn as a path; light on CPU (A12); Settings toggle on by default; ShotsTest screenshot. Hook points: `BlazeAccessibilityService.tap/tapText/scroll` (gesture coordinates), `Agent.Session.orientFirst` (the look before acting), `ui/Overlay.kt` (existing TYPE_ACCESSIBILITY_OVERLAY orb).
- **Orient/secure-form notes:** secure values live in `SecureInput` slots (memory) and `Vault` (`filesDir/vault.json`, Keystore-sealed); the model only handles `{{secret:name}}`. `BrowserEngine.fillSecret/submitSecret` inject; `READ_JS` hides password and `data-bz-secret` values.
