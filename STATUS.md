# Blaze Android status

- **Now:** v0.19.0 (versionCode 61), PR #21 (branch `visual-parity`, squash-merged on green): visual parity with Blaze Desktop. The Memory screen is an animated graph of the memories table (`ui/MemoryPage.kt` + `ui/MemGraph.kt`, tap a dot to view/edit/forget, Graph | List, search); Skills, Schedule, Settings, model picker, onboarding (setup), Accounts, empty states and the work card use Desktop's kit (`ui/Kit.kt`). See CHANGELOG.md.
- **Shipped:** v0.15.0 long chats, PR #17, merge 2a86aaf. v0.16.0 student pack, PR #18, merge a499beb. v0.17.0 marketer & seller pack, PR #19, merge 2be6525. v0.18.0 one mind, PR #20, merge fc5f3f3.
- **Needs Luke (Google Cloud project blaze-511220):** enable the Classroom API and add the scopes `classroom.courses.readonly` + `classroom.coursework.me.readonly` to the OAuth consent screen (Testing mode), and make sure the blaze-auth Worker passes them through.
- **Plan (Luke, 2026-10-12: "build the rest", merge on green, don't ask between steps):** each its own PR, version bump, tests, screenshots.
  1. v0.15.0 long chats: shipped (#17).
  2. v0.16.0 student pack: shipped (#18).
  3. v0.17.0 marketer & seller pack: shipped (#19).
  4. v0.18.0 one mind: shipped (#20). Spec: Blaze-dekstop `docs/plans/plan-one-mind.md` (shared schema + prompt text).
  5. v0.19.0 visual parity: PR #21 (memory graph + Desktop-level screens).
- **Visual parity notes for later PRs:** new screens should use `ui/Kit.kt` (`PageHead`, `GroupHead`, `Rows` + `KitRow`, `Tag`, `KitButton`/`Btn`, `EmptyState`, `KitSearch`, `Segmented`, `KitSwitch`; colours `raised`, `hair2`, `accentSoft`, `onAccent`). The memory graph's model is pure Kotlin (`MemGraph`, tested in `MemGraphTest`); the canvas only draws (layout settled once, ≤ 20 fps, stops after 12 s idle / when not resumed). Not yet restyled: Learning ("Skills Blaze learned"), Proactive, Terminal/Jobs, Specialists, Usage, Voice, Device pages (they still use the older Section/Note/ToggleRow look, which is consistent but plainer).
- **One mind notes:** memories live in `filesDir/mind.db` (`Mind.Db`: tables journal, memories, meta + FTS4 `journal_fts`, `memories_fts`); the graph reads `Mind.all()` (kind, subject, related). `Memory` is a view of the table and mirrors it to prefs `memory`. Old chats are Archive (`Agent.isLive(id)` false). Idle review = WorkManager unique works `mind_review_power` / `mind_review_wifi`.
- **Screenshots:** CI pushes PNGs + logs to the `shots` branch on every build (`ui/ShotsTest`, BLAZE_SHOTS=1; 01–25). The branch is force-pushed by every build, so check `shots.log`'s `sha:` line before reviewing. Review them after every build.
- **Hard rules:** never change applicationId or signing; never touch keystore/ or the CI "Rotate signing key" step; never force-push main; prefs stay backward compatible; free providers only.
