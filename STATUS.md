# Blaze Android status

- **Now:** v0.19.0 (versionCode 61): visual parity, PR #21 (branch `visual-parity`, head 9ea7de3), squash-merged as b963d74; release v0.19.0 published by the main Build APK run. Before that v0.18.0 one mind (PR #20, merge fc5f3f3). See CHANGELOG.md.
- **Shipped:** v0.15.0 long chats, PR #17, merge 2a86aaf. v0.16.0 student pack, PR #18, merge a499beb. v0.17.0 marketer & seller pack, PR #19, merge 2be6525. v0.18.0 one mind, PR #20, merge fc5f3f3. v0.19.0 visual parity, PR #21, merge b963d74.
- **Needs Luke (Google Cloud project blaze-511220):** enable the Classroom API and add the scopes `classroom.courses.readonly` + `classroom.coursework.me.readonly` to the OAuth consent screen (Testing mode), and make sure the blaze-auth Worker passes them through.
- **Plan (Luke, 2026-10-12: "build the rest", merge on green, don't ask between steps):** each its own PR, version bump, tests, screenshots.
  1. v0.15.0 long chats: shipped (#17).
  2. v0.16.0 student pack: shipped (#18).
  3. v0.17.0 marketer & seller pack: shipped (#19).
  4. v0.18.0 one mind: shipped (#20). Spec: spec in Blaze-dekstop `docs/plans/plan-one-mind.md` (shared schema + prompt text).
  5. v0.19.0 visual parity: shipped (#21).
- **One mind notes for the next PR:** memories live in `filesDir/mind.db` (`Mind.Db`: tables journal, memories, meta + FTS4 `journal_fts`, `memories_fts`); the graph should read `Mind.all()` (kind, subject, related). `Memory` is a view of the table and mirrors it to prefs `memory`. Old chats are Archive (`Agent.isLive(id)` false). Idle review = WorkManager unique works `mind_review_power` / `mind_review_wifi`.
- **Screenshots:** CI pushes PNGs + logs to the `shots` branch on every build (`ui/ShotsTest`, BLAZE_SHOTS=1). Review them after every build.
- **Hard rules:** never change applicationId or signing; never touch keystore/ or the CI "Rotate signing key" step; never force-push main; prefs stay backward compatible; free providers only.

- **Needs Luke:** enable the Classroom API in Google Cloud project blaze-511220 and add the scopes classroom.courses.readonly and classroom.coursework.me.readonly to the consent screen; check that the blaze-auth Worker passes them through.

## visual-parity (v0.19.0) — done
- **Merged:** PR #21, branch `visual-parity` head 9ea7de3, squash merge b963d74 on main; release https://github.com/lacksonkapapahue/Blaze-agent/releases/tag/v0.19.0. CI green, screenshots 01–25 reviewed (15–25 are new).
- **What it added:** `ui/MemoryPage.kt` + `ui/MemGraph.kt` (animated memory graph over `Mind.all()`: tap a dot to view/edit/forget, Graph | List, search; layout settled once, ≤ 20 fps, stops after 12 s idle or when not resumed; tested in `MemGraphTest`). `ui/Kit.kt` (Desktop's page header, group heads, raised rows, tags, buttons, empty states, search, segmented, switch; colours `raised`, `hair2`, `accentSoft`, `onAccent`) used by Skills (`SkillLibraryPage`), Schedule (`SchedulePage`), Settings list (`SettingsList`), model picker (`ModelField`/`ModelList`), setup/onboarding, Accounts (`ConnectionsPage`), empty states and the work card.
- **Left (optional, in order):** 1) restyle the pages still on the older Section/Note/ToggleRow look with `ui/Kit.kt`: Learning ("Skills Blaze learned"), Proactive, Terminal/Jobs, Specialists, Usage, Voice, Device; 2) add them to `ShotsTest`. `SettingsV19.Onboarding` is unused (the real first run is `Setup.kt` `SetupFlow`).
- **Next step:** none required. For more polish, branch from main, restyle one page group per PR, add its screenshot, bump the version.
- **Note:** the `shots` branch is force-pushed by every build (any branch), so check `shots.log`'s `sha:`/`ref:` line before reviewing.

## orient-browser (v0.20.0, versionCode 62)
- **What:** orient loop for phone control (`agent/Orient.kt`), background browser with in-chat secure forms (`browser_secure_input`, `browser/SecureInput.kt`, `ui/SecureFormCard.kt`), Saved logins (saved by default, Keystore-sealed `filesDir/vault.json`, Settings › Saved logins / `/logins`), sites that refuse passwords (`SignInHints`), "Blaze lost phone control" fix (renderer-gone handling, state-aware one-tap banner `phone/PhoneControl.kt`), heads-ups speak only with `proactive_speak` on. PR #22. See CHANGELOG.md.
- **Notes:** secure values live in `SecureInput` slots (memory only) and `Vault`; the model only handles `{{secret:name}}`. `BrowserEngine.fillSecret/submitSecret` inject; `READ_JS` hides password and `data-bz-secret` values; tool output is scrubbed. Screenshots `30-`–`33-`.
- **Next (v0.21.0, its own PR, Luke 2026-10-12): visible pointer for phone control.** Accessibility-overlay pointer that glides to the target, re-checks the target (orient) and ripples before tapping; any touch pauses Blaze (manual mode); drag only moves it; double-tap while paused clicks there, then Blaze looks and continues; Resume / Stop bar; long press shown, swipes drawn as a path; light on CPU; Settings toggle on by default; screenshot. Hook points: `BlazeAccessibilityService.tap/gestureTap/swipe/dispatch`, `Agent.Session.runTool` (gate before `orientFirst`).
## visual-parity (v0.19.0) — done
- **Merged:** PR #21, branch `visual-parity` head 9ea7de3, squash merge b963d74 on main; release https://github.com/lacksonkapapahue/Blaze-agent/releases/tag/v0.19.0. CI green, screenshots 01–25 reviewed (15–25 are new).
- **What it added:** `ui/MemoryPage.kt` + `ui/MemGraph.kt` (animated memory graph over `Mind.all()`: tap a dot to view/edit/forget, Graph | List, search; layout settled once, ≤ 20 fps, stops after 12 s idle or when not resumed; tested in `MemGraphTest`). `ui/Kit.kt` (Desktop's page header, group heads, raised rows, tags, buttons, empty states, search, segmented, switch; colours `raised`, `hair2`, `accentSoft`, `onAccent`) used by Skills (`SkillLibraryPage`), Schedule (`SchedulePage`), Settings list (`SettingsList`), model picker (`ModelField`/`ModelList`), setup/onboarding, Accounts (`ConnectionsPage`), empty states and the work card.
- **Left (optional, in order):** 1) restyle the pages still on the older Section/Note/ToggleRow look with `ui/Kit.kt`: Learning ("Skills Blaze learned"), Proactive, Terminal/Jobs, Specialists, Usage, Voice, Device; 2) add them to `ShotsTest`. `SettingsV19.Onboarding` is unused (the real first run is `Setup.kt` `SetupFlow`).
- **Next step:** none required. For more polish, branch from main, restyle one page group per PR, add its screenshot, bump the version.
- **Note:** the `shots` branch is force-pushed by every build (any branch), so check `shots.log`'s `sha:`/`ref:` line before reviewing.

