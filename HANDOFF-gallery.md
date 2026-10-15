# Gallery handoff (v0.30.0)

What shipped in v0.30.0 is in CHANGELOG.md. Code: `media/GalleryModel.kt` (pure rules, `GalleryModelTest`),
`media/GalleryRepo.kt` (MediaStore, thumbnails, actions), `ui/GalleryScreen.kt` (screen, viewer, dialogs).
Only CI has compiled this. Nobody has run it on a phone yet.

## Test on the Galaxy A12 first
1. Gallery opens with **All** and day headers (Today / Yesterday / date). Generated pictures, beats and anything in
   Music/Blaze, Movies/Blaze and Download/Blaze all show. Songs have cover art or a coloured note tile, and songs and videos show their length.
2. Fling quickly through a few hundred items. Tiles fade in with no stutter. Go back up and they're already there (memory cache).
3. Pills: Images / Music / Videos filter. Albums shows Generated images, Music, Videos, Downloads, and "+ New album".
4. "Show Screenshots and Camera": grant → Screenshots and Camera albums appear. Deny → nothing breaks.
5. Tap a picture: it grows out of its tile. Swipe left and right, pinch to zoom, double-tap to zoom, tap to hide the bars, Back to shrink it into the tile.
6. Tap a song: it plays at once with a seek bar. Swiping to the next item stops it. Tap a video: it plays inline with controls.
7. Viewer actions: Share, Rename (the extension stays), Info, Wallpaper (Home / Lock / Both), Album, Delete (confirm). Deleting
   a Camera photo should bring up Android's own confirmation.
8. Long-press: select several items, tick a day header, Select all. Share several at once, add to a new album, Move (inside a user album),
   Remove, Delete.
9. Albums: create, rename, delete (the files stay). Kill the app and reopen, and the albums are still there.
10. Sort menu (newest/oldest, name A–Z/Z–A, largest/smallest; name and size are flat, with no day headers). Search by name.
11. While the Gallery is open, download a song with the Music agent. It appears within about a second.

## Not done yet (next steps, roughly by value)
- **Pinch the grid to change columns** (2–5 like Google Photos) and a **fast-scroll thumb** with month labels.
- **Real paging**: the full MediaStore index (metadata only) loads in one IO query. That's fine for a few thousand rows, but
  Paging 3 / a windowed cursor would be needed for 20k+ item Camera rolls.
- **True shared-element transition** (Compose `SharedTransitionLayout`, experimental in 1.7). The current one is a
  graphicsLayer zoom from the tile's bounds. It looks right for square images. For tall or wide images it scales by width.
- **Media3/ExoPlayer** for video and audio: background playback, MediaSession and notification controls, gapless audio, and more
  codecs than VideoView/MediaPlayer. Hand off to the Music/Video player apps via intents once they exist.
- Trash/recycle bin (Android 11+ `createTrashRequest`), favourites, edit/crop/rotate, slideshow, "Free up space".
- Rename of shared-storage files Blaze doesn't own (needs `createWriteRequest`). Today it shows a clear message.
- Video thumbnails on Android 8–9 for content URIs use a 1 s frame (MediaMetadataRetriever), which is slower than on 10+.
- Robolectric screenshot of the new Gallery in `ShotsTest`.
