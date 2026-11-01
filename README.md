# ES-DE Art Grabber

An Android app that downloads box art, logos and backgrounds from [SteamGridDB](https://www.steamgriddb.com) and saves them straight into [ES-DE](https://es-de.org)'s media folders. It's made for handhelds like the Retroid Pocket and works with a controller.

ES-DE's built-in scrapers (ScreenScraper and TheGamesDB) often have nothing for PC games, such as GameNative's `.epic`/`.steam`/`.gog` exports, or for Android apps. This fills those gaps.

## Features

- **Finds your folders:** reads ES-DE's settings and collects system folders from internal storage, SD cards and ES-DE custom system paths.
- **Works with anything ES-DE lists:** ROMs, disc images, `.m3u` playlists, folder-games, and launcher files (`.epic`, `.gog`, `.steam`, `.desktop`, `.app`). Launcher files are accepted even when an old `systeminfo.txt` doesn't list them.
- **Smart matching:** it cleans up ROM names, uses titles from ES-DE's `gamelist.xml` when there are any, and matches `.steam` entries by Steam app ID.
- **Choose what to download:** covers, logos (marquees), fanart, screenshots and title screens.
- **Preview first:** see every match before anything is saved.
- **Fix unmatched:** search for the right game yourself, then download automatically or choose images.
- **Pick art by hand:** browse every SteamGridDB image for a game as thumbnails and choose your own.
- **Android apps and emulators:** each app's own icon becomes its cover, logo, screenshot and mix image (never the background). It's saved at 256 px to keep files small.
- **Never re-downloads:** art you already have is skipped unless you choose to replace it.

## Install

1. Download the APK from [Releases](../../releases) and open it on your device. Allow installing from unknown sources when asked.
2. When the app asks, turn on **Allow access to manage all files**. It needs this to see every system folder.
3. Get a free API key on steamgriddb.com (log in → Preferences → API), then tap **Enter key**.
4. Tap **Preview matches**, then **Download art**. Restart ES-DE to see the new art.

Requires Android 6 or later.

## Building

No Gradle or Android Studio needed: it builds with the Android tools packaged in Ubuntu.

```sh
sudo apt install android-sdk-platform-23 aapt apksigner dalvik-exchange zipalign libandroid-json-java
./build.sh          # → ES-DE-Art-Grabber.apk
tests/run.sh        # engine tests on the desktop JVM, incl. a fake SteamGridDB server
```

**Signing:** `build.sh` signs with `release.keystore` and creates a new key if that file is missing. The key isn't in this repository; keep yours safe and put it next to `build.sh` before building. Android only installs an update over an existing install if it's signed with the same key. Builds from GitHub Actions use a throwaway key, so they install as a fresh copy.

## Layout

| Path | What |
|---|---|
| `src/com/esde/artgrabber/Engine.java` | Scanning, matching and saving. Plain Java with no Android code, so it can be tested on a desktop. |
| `src/com/esde/artgrabber/Sgdb.java` | SteamGridDB API client |
| `src/com/esde/artgrabber/Names.java` | ROM-name cleanup and fuzzy matching |
| `src/com/esde/artgrabber/MainActivity.java` | The app's screens |
| `src/com/esde/artgrabber/DeviceAppArt.java` | Turns installed apps' icons into images |
| `tests/` | Desktop tests |
| `tools/sgdb_esde.py` | The original command-line version, for Termux or a PC |

## Notes

- ES-DE has no plugin system for scrapers, so this works alongside ES-DE rather than inside its scraper menu.
- SteamGridDB is strongest for PC games. For retro consoles, ES-DE's ScreenScraper usually has better coverage, so deselect those systems here.
- Only PNG and JPEG are downloaded, because ES-DE can't read WebP.
