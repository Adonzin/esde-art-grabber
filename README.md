# ES-DE Art Grabber

An Android app that downloads box art, logos and backgrounds and saves them straight into [ES-DE](https://es-de.org)'s media folders. It's made for handhelds like the Retroid Pocket and works with a controller.

It uses three sources, none of which ES-DE's built-in scrapers cover:

| Source | Used for | Key |
|---|---|---|
| [Libretro thumbnails](https://github.com/libretro-thumbnails/libretro-thumbnails) | Retro consoles: box art, screenshots, title screens, logos | None |
| Official Steam art | Steam games, and other games that are also on Steam | None |
| [SteamGridDB](https://www.steamgriddb.com) | Everything else, and whatever the others are missing | Free API key |

Each game uses the first source that has art for it, and the next ones fill whatever is still missing. Any source can be switched off.

ES-DE's built-in scrapers (ScreenScraper and TheGamesDB) often have nothing for PC games, such as GameNative's `.epic`/`.steam`/`.gog` exports, or for Android apps. This fills those gaps.

## Features

- **Finds your folders:** reads ES-DE's settings and collects system folders from internal storage, SD cards and ES-DE custom system paths.
- **Works with anything ES-DE lists:** ROMs, disc images, `.m3u` playlists, folder-games, and launcher files (`.epic`, `.gog`, `.steam`, `.desktop`, `.app`). Launcher files are accepted even when an old `systeminfo.txt` doesn't list them.
- **Smart matching:** it cleans up ROM names, uses titles from ES-DE's `gamelist.xml` when there are any, and matches `.steam` entries by Steam app ID.
- **Libretro matching:** looks for the exact No-Intro file name, then the same title in your ROM's region, so `Super Mario World (Europe).sfc` gets the European box. Each system's image list is cached for a week.
- **Choose what to download:** covers, logos (marquees), fanart, screenshots and title screens.
- **Preview first:** see every match before anything is saved.
- **Fix unmatched:** search for the right game yourself, then download automatically or choose images.
- **Pick art by hand:** browse every SteamGridDB image for a game as thumbnails and choose your own.
- **Android apps and emulators:** each app's own icon becomes its cover, logo, screenshot and mix image (never the background). It's saved at 256 px to keep files small.
- **Update app button:** checks this repository's releases and installs a newer version for you.
- **Never re-downloads:** art you already have is skipped unless you choose to replace it.

## Install

1. Download the APK from [Releases](../../releases) and open it on your device. Allow installing from unknown sources when asked.
2. When the app asks, turn on **Allow access to manage all files**. It needs this to see every system folder.
3. Get a free API key on steamgriddb.com (log in → Preferences → API), then tap **Enter key**. You can skip this if you switch SteamGridDB off.
4. Tap **Preview matches**, then **Download art**. Restart ES-DE to see the new art.

Requires Android 6 or later.

## Building

No Gradle or Android Studio needed: it builds with the Android tools packaged in Ubuntu.

```sh
sudo apt install android-sdk-platform-23 aapt apksigner dalvik-exchange zipalign libandroid-json-java
./build.sh          # → ES-DE-Art-Grabber.apk
tests/run.sh        # engine tests on the desktop JVM, incl. a fake SteamGridDB server
```

**Releasing:** bump `versionCode`/`versionName` in `AndroidManifest.xml`, add `release-notes/vX.Y.md`, then push a matching tag (`git tag vX.Y && git push origin vX.Y`). The Release workflow tests, builds and signs the APK, then publishes it as a GitHub release, which the in-app updater picks up. It needs a repository secret `KEYSTORE_B64` holding the base64 of `release.keystore`, and it refuses to publish without it. The repository must be public for the app to see releases.

**Signing:** `build.sh` signs with `release.keystore` and creates a new key if that file is missing. The key isn't in this repository; keep yours safe and put it next to `build.sh` before building. Android only installs an update over an existing install if it's signed with the same key. The Build workflow (every push) uses a throwaway key, so its APKs install as a fresh copy; the Release workflow signs with the real key from the `KEYSTORE_B64` secret.

## Layout

| Path | What |
|---|---|
| `src/com/esde/artgrabber/Engine.java` | Scanning, matching and saving. Plain Java with no Android code, so it can be tested on a desktop. |
| `src/com/esde/artgrabber/Sgdb.java` | SteamGridDB API client |
| `src/com/esde/artgrabber/Libretro.java` | Libretro thumbnails: system table, listings, name matching |
| `src/com/esde/artgrabber/SteamArt.java` | Official Steam images by app ID |
| `src/com/esde/artgrabber/Updater.java`, `UpdateProvider.java` | In-app updates from GitHub releases |
| `src/com/esde/artgrabber/Names.java` | ROM-name cleanup and fuzzy matching |
| `src/com/esde/artgrabber/MainActivity.java` | The app's screens |
| `src/com/esde/artgrabber/DeviceAppArt.java` | Turns installed apps' icons into images |
| `tests/` | Desktop tests |
| `tools/sgdb_esde.py` | The original command-line version, for Termux or a PC |

## Notes

- ES-DE has no plugin system for scrapers, so this works alongside ES-DE rather than inside its scraper menu.
- Libretro's art is named after No-Intro ROM names, so it matches best when your ROMs are named that way. It also tries the title from ES-DE's game list.
- Some newer Steam games keep their art at addresses that can't be worked out from the app ID; SteamGridDB covers those.
- Only PNG and JPEG are downloaded, because ES-DE can't read WebP.
