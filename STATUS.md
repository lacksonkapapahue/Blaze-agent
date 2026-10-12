# Blaze Android status

- **Now:** v0.16.0 (versionCode 58) on branch `student-pack`: Google Classroom tools (opt-in scopes), six study skills, Learn view with flashcards (SM-2), dated Schedule items, low-data mode. See CHANGELOG.md.
- **Shipped:** v0.15.0 long chats, PR #17, merge 2a86aaf.
- **Needs Luke (Google Cloud project blaze-511220):** enable the Classroom API and add the scopes `classroom.courses.readonly` + `classroom.coursework.me.readonly` to the OAuth consent screen (Testing mode), and make sure the blaze-auth Worker passes them through.
- **Plan (Luke, 2026-10-12: "build the rest", merge on green, don't ask between steps):** each its own PR, version bump, tests, screenshots.
  1. v0.15.0 long chats: shipped (#17).
  2. v0.16.0 student pack (this PR).
  3. v0.17.0 marketer & seller pack (branch `marketer-pack`): spec in Blaze-dekstop `docs/plans/plan-v1.0-redesign.md` section D.
- **Screenshots:** CI pushes PNGs + logs to the `shots` branch on every build (`ui/ShotsTest`, BLAZE_SHOTS=1). Review them after every build.
- **Hard rules:** never change applicationId or signing; never touch keystore/ or the CI "Rotate signing key" step; never force-push main; prefs stay backward compatible; free providers only.
