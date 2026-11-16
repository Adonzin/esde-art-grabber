# ES-DE Commander

An Android companion app for [ES-DE](https://es-de.org), made for handhelds like the Retroid Pocket and fully usable with a controller. It does two jobs:

- **Artwork:** downloads box art, logos and backgrounds and saves them straight into ES-DE's media folders.
- **Categories:** makes your own ES-DE systems and collections (Android app categories, a Moonlight category for your PC games, collections) without editing XML or folders.

(Formerly *ES-DE Art Grabber*. Updates install over the old app.)

## Categories

| Kind | What it makes | Example |
|---|---|---|
| **Android app category** | A new system on ES-DE's main screen, with the apps you tick | *Streaming*: Netflix, YouTube, Plex |
| **Moonlight category** | A system whose entries open a game on your PC through Moonlight, directly | *Moonlight*: Cyberpunk 2077, Desktop |
| **Collection** | An ES-DE custom collection of entries you already have, optionally as its own item | *Media*: VLC, Kodi, Plex |

How it works under the hood:
- App and Moonlight categories are written to `ES-DE/custom_systems/es_systems.xml` (Moonlight also adds an emulator rule to `es_find_rules.xml`), with each category's entries in its own folder in your ROMs folder.
- App categories use ES-DE's own Android Apps launch method: each `.app` file holds an app's package name.
- Moonlight entries launch Moonlight's shortcut screen with the computer's name and the game's name, so each entry must be named exactly like the game in Moonlight.
- **Get games from my PC** fills a Moonlight category from Sunshine/Apollo itself: hosts are found on the network over mDNS (`_nvstream._tcp`, as Moonlight does), the PC name comes from its public `/serverinfo`, and the app list from the settings server's `/api/apps`. Sunshine takes HTTP Basic login and Apollo a `/api/login` session cookie; both are tried. Only home-network addresses are allowed, and the self-signed certificate is pinned on first use.
- Collections are `ES-DE/collections/custom-<name>.cfg`, switched on in `es_settings.xml`.
- Entries this app made are marked `<!-- ES-DE Commander -->`. Systems, rules and files you made yourself are never changed, and ES-DE's own system names can't be reused.
- Every change is backed up first (`ES-DE/commander/backups`), and **Undo last change** puts back every file it touched.
- Close ES-DE before making changes: it reads these files when it starts.

## Themes and custom looks

ES-DE themes only have artwork for ES-DE's own systems, so new categories normally borrow another system's look or show just their name. ES-DE Commander can give them their own:

1. **Make an editable copy** of your theme (Themes screen). ES-DE on Android keeps downloaded themes in its private storage, so the app downloads the same theme again from the source ES-DE's theme downloader uses ([themes-list](https://gitlab.com/es-de/themes/themes-list)), into `ES-DE/themes/<theme>-escommander`, named "*<Theme> (ESCommander)*". Your original theme isn't touched. **Refresh copy** downloads the latest version and puts your custom looks back.
2. **Custom look** (category menu): choose a main picture, a logo and a background. Each can be a picture file or an installed app's icon. App icons on big tiles sit centred on a colour you pick, so they aren't cut off.
3. Choose the ESCommander theme in ES-DE: *UI Settings → Theme*.

How the app knows where pictures go: `ThemeLayout` reads the theme's XML and finds every path that contains `${system.theme}`, directly or through variables that change with the theme's variants and colour schemes. Each one becomes a slot with a role (carousel tile, logo, name logo, background, system info...) and the size and format of the same file for a similar system (normally Android Apps). `ThemeArtPlan` then draws each picture to fit: cropped to fill for tiles, fitted on transparency for logos. SVG slots get an SVG wrapping the picture; ES-DE's SVG renderer, LunaSVG 3.5, draws embedded images. The category also gets its own copy of the theme's system info, with its name and description, the original colours, and none of the other system's facts. Files the theme already has are never overwritten, and everything a look writes is recorded so it can be replaced or removed cleanly.

Checked against PS5 Menu, Linear, Modern, Slate, Canvas, Iconic, Art Book Next and NSO Menu (`THEMES_DIR=/path/to/themes tests/run.sh`).

## Artwork

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
- **Android apps and emulators:** each app's own icon becomes its cover, logo, screenshot and mix image (never the background), at 256 px to keep files small. Apps are never searched as games. App categories made here are treated the same way.
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

**Releasing:** bump `versionCode`/`versionName` in `AndroidManifest.xml`, add `release-notes/vX.Y.md` and push to `main`. When that version has no release yet, the Release workflow tests, builds and signs the APK, creates the `vX.Y` tag and publishes the release, which the in-app updater picks up. It needs a repository secret `KEYSTORE_B64` holding the base64 of `release.keystore`, and it refuses to publish without it. The repository must be public for the app to see releases.

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
| `src/com/esde/artgrabber/MainActivity.java` | The main screen |
| `src/com/esde/artgrabber/EsdeConfig.java` | Writes ES-DE's custom systems, find rules, collections and settings, with backups and undo. No Android code, tested on a desktop. |
| `src/com/esde/artgrabber/CategoriesScreen.java` | The Categories screen |
| `src/com/esde/artgrabber/ThemeLayout.java`, `ThemeArtPlan.java` | Finding where a theme keeps per-system art, and planning a category's files |
| `src/com/esde/artgrabber/ThemeCopies.java`, `CategoryLooks.java` | Theme copies and custom looks |
| `src/com/esde/artgrabber/ThemesScreen.java`, `LookEditor.java`, `LookRenderer.java` | Their screens and on-device drawing |
| `src/com/esde/artgrabber/SunshineClient.java`, `MoonlightImport.java` | Reading a Sunshine/Apollo PC's app list, and its screens |
| `src/com/esde/artgrabber/DeviceAppArt.java` | Turns installed apps' icons into images |
| `tests/` | Desktop tests |
| `tools/sgdb_esde.py` | The original command-line version, for Termux or a PC |

## Notes

- ES-DE has no plugin system for scrapers, so this works alongside ES-DE rather than inside its scraper menu.
- Libretro's art is named after No-Intro ROM names, so it matches best when your ROMs are named that way. It also tries the title from ES-DE's game list.
- Some newer Steam games keep their art at addresses that can't be worked out from the app ID; SteamGridDB covers those.
- Only PNG and JPEG are downloaded, because ES-DE can't read WebP.
