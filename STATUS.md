# Blaze Android status

- **Now:** v0.22.0 (versionCode 64): notification replies + life logs, PR #23 (branch `life-logs`), squash-merged on green. Before that v0.21.0 visible pointer (PR #24, merge 0245eff), v0.20.0 orient loop + background browser (PR #22, merge d15e6e2), v0.19.0 visual parity (PR #21, merge b963d74). See CHANGELOG.md.
- **Shipped:** v0.15.0 long chats, PR #17, merge 2a86aaf. v0.16.0 student pack, PR #18, merge a499beb. v0.17.0 marketer & seller pack, PR #19, merge 2be6525. v0.18.0 one mind, PR #20, merge fc5f3f3. v0.19.0 visual parity, PR #21, merge b963d74.
- **Needs Luke (Google Cloud project blaze-511220):** enable the Classroom API and add the scopes `classroom.courses.readonly` + `classroom.coursework.me.readonly` to the OAuth consent screen (Testing mode), and make sure the blaze-auth Worker passes them through.
- **Plan (Luke, 2026-10-12: "build the rest", merge on green, don't ask between steps):** each its own PR, version bump, tests, screenshots.
  1. v0.15.0 long chats: shipped (#17).
  2. v0.16.0 student pack: shipped (#18).
  3. v0.17.0 marketer & seller pack: shipped (#19).
  4. v0.18.0 one mind: shipped (#20). Spec: spec in Blaze-dekstop `docs/plans/plan-one-mind.md` (shared schema + prompt text).
  5. v0.19.0 visual parity: shipped (#21).
  6. v0.22.0 notification replies + life logs: PR #23.
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

## orient-browser (v0.20.0, versionCode 62) — done
- **Merged:** PR #22, branch `orient-browser` head ee8d373, squash merge d15e6e2 on main; release https://github.com/lacksonkapapahue/Blaze-agent/releases/tag/v0.20.0 (Blaze-v0.20.0.apk). CI green, screenshots 30–33 reviewed.
- **What:** orient loop for phone control (`agent/Orient.kt`), background browser with in-chat secure forms (`browser_secure_input`, `browser/SecureInput.kt`, `ui/SecureFormCard.kt`), Saved logins (saved by default, Keystore-sealed `filesDir/vault.json`, Settings › Saved logins / `/logins`), sites that refuse passwords (`SignInHints`), "Blaze lost phone control" fix (renderer-gone handling, state-aware one-tap banner `phone/PhoneControl.kt`), heads-ups speak only with `proactive_speak` on.
- **Notes:** secure values live in `SecureInput` slots (memory only) and `Vault`; the model only handles `{{secret:name}}`. `BrowserEngine.fillSecret/submitSecret` inject; `READ_JS` hides password and `data-bz-secret` values; tool output is scrubbed.
- **Left (needs a real phone):** confirm on the Galaxy A12 that offscreen tabs lay out and read correctly, and that the banner no longer appears after switching browser ↔ chat (check Settings › Device › crash report for "Phone control: … ->" and "Browser renderer" lines if it does).

## pointer (v0.21.0, versionCode 63) — done
- **Merged:** PR #24, branch `pointer` head 09198cb, squash merge 0245eff on main; release https://github.com/lacksonkapapahue/Blaze-agent/releases/tag/v0.21.0 (Blaze-v0.21.0.apk). CI green, screenshot `34-pointer` reviewed.
- **What:** visible pointer for phone control (`phone/Pointer.kt`, hooks in `BlazeAccessibilityService.aimAt/gestureTap/swipe` and `Agent.Session.pointerGate`), Settings › Phone control › Show the pointer (`show_pointer`, on by default), `ui/PointerPreview.kt` for screenshots, `PointerTest`.
- **Left (needs a real phone, in order):** 1) on the Galaxy A12, check that touching elsewhere on screen pauses Blaze (ACTION_OUTSIDE reaching the accessibility overlay on One UI); touching the pointer itself always pauses; 2) check the pointer size/visibility (44dp window, ~25dp arrow) and enlarge in `Pointer.Dot` if it's hard to see; 3) optional: say "Paused" in the orb panel too.
- **Next step:** none required; open an issue or a small PR from main if the real-phone checks above show a problem.
- **Version note:** PR #23 (`life-logs`) took v0.22.0 / versionCode 64.

## life-logs (v0.22.0, versionCode 64)
- **Merged:** PR #23 (https://github.com/lacksonkapapahue/Blaze-agent/pull/23), branch `life-logs`, squash-merged to main on green. CI green on the branch; screenshots 26-life-logs, 27-life-logs-paused, 28-replies reviewed.
- **What it added:** `notify/Conversations.kt` (MessagingStyle reading, per-conversation rolling log in the journal as kind `notif`, people-memory links, RemoteInput replies behind a confirm card unless auto-reply is on, tools `notif_conversations` / `notif_read` / `notif_reply`), `notify/Sensitive.kt` (OTP / bank / mobile-money filter), `agent/MindLogs.kt` (journal `kind` column added with ALTER TABLE, no DB version bump; append / recent / delete by kind), `life/` (`LifeLogService` mic foreground service with Pause/Stop, Vosk small model shared with the wake word + SpeechRecognizer fallback, sound gate, auto-pause on call / other recorder / Blaze voice / battery < 15%; `LogChunker`; `LifeLogs` controller, `life_logs` tool, brainstorm summary), `ui/LifeLogsPage.kt` (Settings › Life logs and Settings › Notification replies), quick rules ("start logs", "stop logs", "brainstorm with me"), `/logs`, `/brainstorm`. Idle review now batches chat / notif / life-log rows separately and marks life-log memories "(inferred)".
- **Not verified on a device (needs Luke on the A12):** Vosk CPU and battery with the sound gate over an hour; that `isClientSilenced` really pauses when WhatsApp records a voice note; that WhatsApp's RemoteInput reply sends from the notification; Samsung's beep on every SpeechRecognizer restart (fallback only, until the model is downloaded).
- **Left (optional, in order):** 1) restyle Life logs / Notification replies pages with `ui/Kit.kt` like the visual-parity pages; 2) "Hey Blaze" during logs (the wake word sleeps while logs hold the mic; only "stop logs" / "pause logs" are heard from the transcript); 3) Desktop parity for the journal `kind` column and kinds `notif` / `life_log`.
- **Next step:** install v0.22.0 on the A12, turn on notification access, try "start logs" and "reply to <name>"; file anything that misbehaves.

## views-skills (v0.23.0, versionCode 65) — done
- **Merged:** PR #25 (https://github.com/lacksonkapapahue/Blaze-agent/pull/25), branch `views-skills`, squash-merged to main on green. CI green on the branch (run 38311637802); screenshot `35-views-skills` reviewed (shots.log sha = the PR merge commit, ref views-skills).
- **What:** always-present VIEWS block in the system prompt + per-turn required-view hint and tool group (`agent/ViewRouter.kt`, keyword classifier, no model call); Learn safety net (flashcard/quiz text with no `flashcards` call → Learn deck + Learn opens, or one re-prompt for "make flashcards"); per-turn skill lookup across bundled / user (~/skills) / learned (skills.json) skills, disabled ones excluded (`agent/SkillMatch.kt`: name ×3, tags/keywords ×2, description ×1; top 1-3 in full within ~6,000 chars, others by name; "Skills: …" as the first work-card step; the turn skips the tool-less chat lane when a view or skill matched); bundled skill `study-flashcards`; Projects in `/views` / `open_view`. Tests `ViewsSkillsTest`.
- **Left (optional):** 1) on the A12, ask "make flashcards on <topic>" and check the deck appears and Learn opens; 2) tune `ViewRouter` cues / `SkillMatch.MIN_SCORE` (3) if a view or skill is picked when it shouldn't be; 3) Desktop parity for the VIEWS block and per-turn skill lookup.
- **Next step:** none required.
