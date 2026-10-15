# Handoff: Blaze Music + Blaze Video (v0.30.1, branch `media-apps`)

## Done
- **Two in-app apps** (own task like Terminal/Browser): `mediaapps/MusicActivity.kt`, `mediaapps/VideoActivity.kt`, each with the Player | Library | Store pill (`AppPill` in `mediaapps/MediaUi.kt`, same look as Chat | Projects | Home). Opened from Views, `/music`, `/video`, open_view (`ui/Views.kt`, `ui/ChatScreen.kt`, `agent/Commands.kt`).
- **Music:** media3 `MusicService` (MediaSessionService): background play, notification + lock-screen + headset controls, audio focus, unplug pause, gapless, shuffle (tapped song first), repeat off/all/one, speed, sleep timer (minutes / end of song), last queue + position restored. Now playing: artwork-tinted background, seek, queue (long-press drag handle to reorder, ×), synced lyrics (LRCLIB, cached), like. Mini-player on Library/Store.
- **Music library** (`MediaLib.kt`): downloads + Blaze Gallery audio, songs / liked / artists / albums / playlists (create, rename, delete, add, remove, move up/down; JSON in `filesDir/mediaapps`) / recently played / search; delete a download.
- **Video:** ExoPlayer, swipe seek / brightness / volume, double-tap ±10 s, fullscreen + rotation, PiP (auto on 12+, on leave 8–11), speed, embedded subtitles + audio track choice, resume position, Continue watching, library grid with delete.
- **Stores:** one `StoreSource` interface (`storeapi/` module). Sources: Jamendo (needs client_id), Internet Archive audio (CC/PD only), Internet Archive Feature Films, Blender open movies, YouTube (plug-in). Grid with picture/title/author/length/licence, preview sheet with licence + quality/track choice, Download, Play/Watch (stream).
- **Downloads** (`Downloads.kt`, pure state in `DownloadLogic.kt`): WorkManager foreground queue, 8 MB ranges, resume after drop / kill / reboot, backoff retries, URL refresh, free-space check, pause/resume/cancel/retry, progress notification, MediaMuxer merge/remux (`MediaFiles.kt`), publish to `Music/Blaze/Music` and `Movies/Blaze/Videos`.
- **Tests (JVM):** `app/src/test/.../mediaapps/MediaAppsTest.kt` (queue/shuffle/repeat, playlists, recents, resume, download state/resume/retry/JSON, artwork colour, LRC), `StoreSourcesTest.kt` (licences, Archive parsing on real fixtures, Jamendo, plug-in discovery), `youtube/src/test/.../YtTest.kt` (search/player parsing, quality selection).

## YouTube plug-in: how it's wired, how to drop it
- Everything YouTube is in **`youtube/`** (Gradle module `:youtube`): `YtClient.kt`, `YtParse.kt`, `YtQuality.kt`, `YouTubeSource.kt`, `src/test/**`, `src/main/resources/META-INF/proguard/blaze-youtube.pro`.
- `settings.gradle.kts` includes `:youtube` only if `youtube/build.gradle.kts` exists; `app/build.gradle.kts` adds `implementation(project(":youtube"))` only if that project exists; `StoreSources.plugin(YOUTUBE_CLASS)` loads it by class name at runtime.
- **To ship without YouTube: delete the `youtube/` folder.** No other file changes. CI job `build-without-youtube` proves it on every push (tests + assembleRelease + "no YouTube classes in the APK").
- Moving it to a private repo: keep the folder layout and check it out into `youtube/` before building (e.g. a submodule or a CI checkout step). It depends only on `:storeapi`, kotlinx-coroutines and (compileOnly) org.json.

## Licence decision
NewPipeExtractor (GPL-3.0) not used: incompatible with Blaze's PolyForm Noncommercial 1.0.0 licence and the bundled proprietary Play services libraries. The YouTube module is Blaze's own InnerTube client. YouTube downloading may breach YouTube's ToS; it's isolated for that reason. Recorded in THIRD_PARTY_NOTICES.md.

## Not done / next steps
1. Agent tools (`music_play`, `media_download`) via the tool framework (ToolRouter); optional in the brief.
2. Drag-reorder inside playlists (has Move up/down in the ⋮ menu; the queue has real drag).
3. External subtitle files (.srt next to a video), subtitle styling, casting, Android Auto, widget, equalizer, crossfade.
4. Device-wide music library (READ_MEDIA_AUDIO) — today the library is Blaze's downloads + Gallery files only.
5. ShotsTest screenshots of the new screens.
6. YouTube streams were not verified end-to-end from here (the sandbox IP gets YouTube's bot wall on /player; search works). Needs a phone test; if all three clients fail, update the client versions in `YtClient.clients`.
7. Jamendo needs a client_id: set `JAMENDO_CLIENT_ID` as a repo Actions secret/env for CI builds, or paste one in the Music Store.

## How to proceed
- Build: `./gradlew testReleaseUnitTest :storeapi:test :youtube:test assembleRelease`.
- Code map: STATUS.md section "media-apps".
