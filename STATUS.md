# Blaze Android status

- **Now:** v0.15.0 (versionCode 57) on branch `long-chat`, PR #17: long-chat engine (rolling summary, project brief, search_chat over SQLite FTS4, artifacts, token meter) + fixes from the v0.14.0 screenshot review. See CHANGELOG.md.
- **Plan (Luke, 2026-10-12: "build the rest", merge on green, don't ask between steps):** each its own PR, version bump, tests, screenshots.
  1. v0.15.0 long chats (this PR).
  2. v0.16.0 student pack (branch `student-pack`): Classroom tools, study skills, Learn view with flashcards, low-data mode.
  3. v0.17.0 marketer & seller pack (branch `marketer-pack`): spec in Blaze-dekstop `docs/plans/plan-v1.0-redesign.md` section D.
- **Screenshots:** CI pushes PNGs + logs to the `shots` branch on every build (`ui/ShotsTest`, BLAZE_SHOTS=1). Review them after every build.
- **Hard rules:** never change applicationId or signing; never touch keystore/ or the CI "Rotate signing key" step; never force-push main; prefs stay backward compatible; free providers only.
