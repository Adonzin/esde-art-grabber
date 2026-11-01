# sgdb_esde — SteamGridDB art for ES-DE

Scans every system folder in your ES-DE ROMs directory, finds each game on SteamGridDB, and saves the art where ES-DE looks for it:

```
ES-DE/downloaded_media/<system>/covers/<rom name>.png
                               /marquees/...   (SteamGridDB logos)
                               /fanart/...     (SteamGridDB heroes)
                               /screenshots/...(wide grids / heroes)
```

It works on any file type ES-DE lists for a system: ROMs, disc images, `.m3u` playlists, folders-as-games like `Game.ps3`, and GameNative's `.epic` / `.steam` / `.gog` markers. It reads each system's `systeminfo.txt` to know which files count as games, and it skips art you already have.

## One-time setup on the Retroid Pocket 5

1. **Install Termux** from F-Droid or its GitHub releases. The Play Store version is outdated.
2. In Termux:
   ```
   pkg update && pkg install python
   termux-setup-storage        # tap Allow - gives Termux access to your ROMs/ES-DE folders
   ```
3. Copy `sgdb_esde.py` to your device, e.g. into `Download`, then:
   ```
   cp ~/storage/downloads/sgdb_esde.py ~/
   ```
4. **Get a free SteamGridDB API key:** log in at steamgriddb.com, go to Preferences → API, and generate a key. Then save it:
   ```
   python sgdb_esde.py --set-key
   ```

## Use

```
python sgdb_esde.py --dry-run          # preview first, saves nothing
python sgdb_esde.py                    # all systems
python sgdb_esde.py --systems epic steam windows
python sgdb_esde.py -i                 # pick the match yourself when it isn't exact
python sgdb_esde.py --overwrite        # replace existing art
```

Then restart ES-DE so it picks up the new art.

| Option | What it does |
|---|---|
| `--roms PATH` / `--esde PATH` | Set the folders yourself if auto-detect misses them. It tries `/storage/emulated/0/ROMs` and `/storage/emulated/0/ES-DE`. Use these for an SD card. |
| `--media-dir PATH` | Use this if you set a custom media folder in ES-DE's settings. |
| `--media covers marquees fanart screenshots titlescreens` | Choose which art types to fetch. |
| `--skip-systems snes nes` | Leave some systems alone, e.g. ones you already scraped with ScreenScraper. |
| `--only-missing` | Skip any game that already has a cover. |
| `--threshold 0.8` | Require a stricter name match. The default is 0.72. |

## How matching works

- **Name cleanup:** ROM names are cleaned before searching. `Legend of Zelda, The (USA) [!]` becomes `The Legend of Zelda`, and region, revision, disc and version tags are dropped.
- **gamelist.xml names first:** If ES-DE's `gamelist.xml` already has a proper name for a file, that name is searched instead. This is what makes cryptic GameNative markers like `a1b2c3.epic` work.
- **Exact Steam matches:** If a marker file contains a Steam app ID, it's looked up by ID for an exact match.
- **Unmatched games:** Anything below the match threshold is listed in `downloaded_media/sgdb_unmatched.txt`. Re-run with `-i` to choose those by hand.

## Notes

- **Older consoles:** SteamGridDB is strongest for PC games but has community art for many console games too. For retro systems with poor coverage, ES-DE's built-in ScreenScraper is still the better source. Use `--skip-systems` so the two don't fight.
- **Image formats:** Only PNG and JPG are downloaded, because ES-DE doesn't read WebP. Animated art is skipped.
