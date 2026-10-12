# Blaze Android status

- **Now:** v0.12.0 (versionCode 54), branch `redesign-a`: 1.0 redesign part A (calm shell, /views, open_view, Design view, Projects, one live work card). See CHANGELOG.md.
- **Waiting on:** Luke's OK on the new UI (release link + screenshots sent). Nothing else starts until then.
- **Queued after approval (each its own PR, version bump, tests, screenshots):**
  1. BlazeRouter: Kotlin port of Desktop presets (category:tier ranking, chaos fan-out to 5 providers, last-known-good), Desktop router over Blaze Link when paired, Settings card with "Classic editor" link, old custom keys carry over, credit OmniRoute (MIT) in THIRD_PARTY_NOTICES.md + README.
  2. Long-chat engine: rolling summary at 60% context, project brief, search_chat over Room FTS, big outputs as artifacts, token meter.
  3. Student pack. 4. Marketer pack.
- **Screenshots:** CI pushes PNGs + logs to the `shots` branch on every build (`ui/ShotsTest`, BLAZE_SHOTS=1).
