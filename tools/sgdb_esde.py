#!/usr/bin/env python3
"""
sgdb_esde.py - Fill ES-DE's media folders with artwork from SteamGridDB.

Scans every system folder in your ES-DE ROMs directory (any file type:
ROMs, disc images, .epic/.steam/.gog marker files, .desktop, folders-as-games),
finds each game on SteamGridDB, and saves the art where ES-DE looks for it:

    ES-DE/downloaded_media/<system>/<mediatype>/<rom name without extension>.png

Pure Python standard library - runs in Termux on Android with just `pkg install python`.
"""

import argparse
import difflib
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

API = "https://www.steamgriddb.com/api/v2"
CONFIG_PATH = os.path.expanduser("~/.config/sgdb_esde/config.json")
UA = "sgdb-esde/1.0"

# ES-DE media folder  ->  (SteamGridDB endpoint, extra query params)
# Each entry is tried in order until one returns an image.
MEDIA_MAP = {
    "covers":      [("grids", {"dimensions": "600x900"}),
                    ("grids", {"dimensions": "342x482,660x930"})],
    "marquees":    [("logos", {})],
    "fanart":      [("heroes", {})],
    "screenshots": [("grids", {"dimensions": "920x430,460x215"}),
                    ("heroes", {})],
    "titlescreens": [("grids", {"dimensions": "920x430,460x215"})],
    "icons":       [("icons", {})],  # not an ES-DE folder by default; opt-in
}
DEFAULT_MEDIA = ["covers", "marquees", "fanart", "screenshots"]

IMAGE_EXTS = (".png", ".jpg", ".jpeg", ".webp", ".gif", ".svg")

# Files that are never games, whatever system folder they sit in.
IGNORE_EXTS = {
    ".txt", ".xml", ".nfo", ".md", ".pdf", ".ini", ".cfg", ".json", ".log",
    ".sav", ".srm", ".state", ".bak", ".db", ".dat", ".sfv", ".md5", ".sha1",
    ".png", ".jpg", ".jpeg", ".webp", ".gif", ".bmp", ".mp4", ".mkv",
    ".nomedia", ".ds_store",
}
IGNORE_DIRS = {"media", "images", "videos", "downloaded_images", "downloaded_media",
               "manuals", "snap", "snaps", "boxart", "_to_delete"}

# Common ROM-folder locations on Android. First that exists wins.
ROM_DIR_GUESSES = [
    "/storage/emulated/0/ROMs", "/sdcard/ROMs", "~/storage/shared/ROMs",
    "/storage/emulated/0/Roms", "/storage/emulated/0/roms",
]
ESDE_DIR_GUESSES = [
    "/storage/emulated/0/ES-DE", "/sdcard/ES-DE", "~/storage/shared/ES-DE", "~/ES-DE",
]


# ----------------------------------------------------------------------------- helpers

def log(msg, quiet=False):
    if not quiet:
        print(msg, flush=True)


def first_existing(paths):
    for p in paths:
        p = os.path.expanduser(p)
        if os.path.isdir(p):
            return p
    return None


def load_config():
    try:
        with open(CONFIG_PATH, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def save_config(cfg):
    os.makedirs(os.path.dirname(CONFIG_PATH), exist_ok=True)
    with open(CONFIG_PATH, "w", encoding="utf-8") as f:
        json.dump(cfg, f, indent=2)
    try:
        os.chmod(CONFIG_PATH, 0o600)
    except OSError:
        pass


# ----------------------------------------------------------------------------- names

REGION_TAGS = re.compile(r"\s*[\(\[\{][^\)\]\}]*[\)\]\}]")
VERSION_TAG = re.compile(r"\s+v\d+(\.\d+)*[a-z]?$", re.I)
DISC_TAG = re.compile(r"\s*-?\s*\b(disc|disk|cd)\s*\d+\b.*$", re.I)


def clean_name(raw):
    """'Legend of Zelda, The - A Link to the Past (USA) [!]' -> 'The Legend of Zelda - A Link to the Past'"""
    name = REGION_TAGS.sub("", raw)
    name = name.replace("_", " ")
    # dots used as spaces ("Super.Mario.World") but keep "Mr. X" / "Vol. 2"
    if name.count(".") >= 2 and " " not in name:
        name = name.replace(".", " ")
    name = DISC_TAG.sub("", name)
    name = VERSION_TAG.sub("", name)
    # "Legend of Zelda, The - Subtitle" / "Legend of Zelda, The"
    m = re.match(r"^(.*?),\s*(The|A|An)(\s*[-:].*)?$", name.strip(), re.I)
    if m:
        name = f"{m.group(2)} {m.group(1)}{m.group(3) or ''}"
    return re.sub(r"\s+", " ", name).strip(" -")


def norm(s):
    s = s.lower().replace("&", "and")
    s = re.sub(r"[^a-z0-9 ]+", " ", s)
    return re.sub(r"\s+", " ", s).strip()


def similarity(a, b):
    na, nb = norm(a), norm(b)
    if not na or not nb:
        return 0.0
    if na == nb:
        return 1.0
    ratio = difflib.SequenceMatcher(None, na, nb).ratio()
    # a result that starts with our whole query ("Hades" -> "Hades II") is close but not exact
    if nb.startswith(na) or na.startswith(nb):
        ratio = max(ratio, 0.85)
    return ratio


# ----------------------------------------------------------------------------- ES-DE data

def system_extensions(system_dir):
    """Read the file extensions ES-DE lists for this system in systeminfo.txt."""
    path = os.path.join(system_dir, "systeminfo.txt")
    try:
        with open(path, encoding="utf-8", errors="ignore") as f:
            lines = f.read().splitlines()
    except OSError:
        return None
    exts, grab = set(), False
    for line in lines:
        low = line.lower()
        if "extension" in low:
            grab = True
            continue
        if grab:
            if not line.strip():
                if exts:
                    break
                continue
            found = re.findall(r"(?<![\w/])\.[A-Za-z0-9_+\-]{1,12}\b", line)
            if not found:
                if exts:
                    break
                continue
            exts.update(e.lower() for e in found)
    return exts or None


def gamelist_names(esde_dir, system, rom_dir):
    """Map relative rom path -> <name> from ES-DE's gamelist.xml (helps for cryptic file names)."""
    names = {}
    for path in (os.path.join(esde_dir, "gamelists", system, "gamelist.xml"),
                 os.path.join(rom_dir, system, "gamelist.xml")):
        try:
            root = ET.parse(path).getroot()
        except (OSError, ET.ParseError):
            continue
        for game in root.iter("game"):
            p, n = game.findtext("path"), game.findtext("name")
            if p and n:
                if p.startswith("./"):
                    p = p[2:]
                names[os.path.normpath(p)] = n.strip()
    return names


def find_games(system_dir, exts):
    """Yield (relative_path, is_dir) for every game in a system folder, recursing into subfolders."""
    for dirpath, dirnames, filenames in os.walk(system_dir):
        rel_dir = os.path.relpath(dirpath, system_dir)
        rel_dir = "" if rel_dir == "." else rel_dir
        keep = []
        for d in sorted(dirnames):
            if d.startswith(".") or d.lower() in IGNORE_DIRS:
                continue
            ext = os.path.splitext(d)[1].lower()
            if ext and exts and ext in exts:     # directory-as-game, e.g. "Game.ps3"
                yield os.path.join(rel_dir, d), True
            else:
                keep.append(d)
        dirnames[:] = keep
        for fn in sorted(filenames):
            if fn.startswith("."):
                continue
            ext = os.path.splitext(fn)[1].lower()
            if exts is not None:
                if ext not in exts:
                    continue
            elif not ext or ext in IGNORE_EXTS:
                continue
            yield os.path.join(rel_dir, fn), False


def m3u_children(system_dir, games):
    """Files referenced by .m3u playlists are hidden by ES-DE, so don't scrape them separately."""
    hidden = set()
    for rel, _ in games:
        if rel.lower().endswith(".m3u"):
            try:
                with open(os.path.join(system_dir, rel), encoding="utf-8", errors="ignore") as f:
                    for line in f:
                        line = line.strip()
                        if line and not line.startswith("#"):
                            hidden.add(os.path.normpath(os.path.join(os.path.dirname(rel), line)))
            except OSError:
                pass
    return hidden


STEAM_ID_PATTERNS = [
    re.compile(r"steam://rungameid/(\d+)", re.I),
    re.compile(r"\bapp_?id\b[\"'\s:=]+(\d{2,10})", re.I),
]


def steam_appid_from_file(path):
    """GameNative/.steam/.desktop marker files often carry the Steam app id - use it for an exact match."""
    try:
        if os.path.getsize(path) > 8192:
            return None
        with open(path, encoding="utf-8", errors="ignore") as f:
            text = f.read()
    except OSError:
        return None
    for pat in STEAM_ID_PATTERNS:
        m = pat.search(text)
        if m:
            return m.group(1)
    if re.fullmatch(r"\s*\d{2,10}\s*", text):
        return text.strip()
    return None


def existing_media(media_dir, base):
    for ext in IMAGE_EXTS:
        p = os.path.join(media_dir, base + ext)
        if os.path.isfile(p):
            return p
    return None


# ----------------------------------------------------------------------------- SteamGridDB

class SGDB:
    def __init__(self, key, delay=0.15):
        self.key, self.delay = key, delay
        self.search_cache, self.image_cache = {}, {}

    def _get(self, path, params=None):
        url = API + path
        if params:
            url += "?" + urllib.parse.urlencode(params)
        req = urllib.request.Request(url, headers={"Authorization": f"Bearer {self.key}",
                                                   "User-Agent": UA})
        for attempt in range(4):
            try:
                time.sleep(self.delay)
                with urllib.request.urlopen(req, timeout=30) as r:
                    return json.load(r)
            except urllib.error.HTTPError as e:
                if e.code == 404:
                    return {"success": False, "data": []}
                if e.code == 401:
                    sys.exit("SteamGridDB rejected the API key (401). Run with --set-key to fix it.")
                if e.code == 429 or e.code >= 500:
                    time.sleep(2 * (attempt + 1))
                    continue
                raise
            except (urllib.error.URLError, TimeoutError):
                time.sleep(2 * (attempt + 1))
        return {"success": False, "data": []}

    def search(self, term):
        if term not in self.search_cache:
            res = self._get("/search/autocomplete/" + urllib.parse.quote(term, safe=""))
            self.search_cache[term] = res.get("data") or []
        return self.search_cache[term]

    def game_by_steam(self, appid):
        res = self._get(f"/games/steam/{appid}")
        return res.get("data") if res.get("success") else None

    def images(self, kind, game_id, params):
        key = (kind, game_id, tuple(sorted(params.items())))
        if key not in self.image_cache:
            q = {"types": "static", "nsfw": "false", "humor": "false",
                 "mimes": "image/png,image/jpeg"}
            if kind == "icons":
                q["mimes"] = "image/png"
            q.update(params)
            res = self._get(f"/{kind}/game/{game_id}", q)
            data = res.get("data") or []
            data.sort(key=lambda i: (i.get("score") or 0), reverse=True)
            self.image_cache[key] = data
        return self.image_cache[key]


def pick_match(results, query, threshold, interactive, label):
    scored = sorted(((similarity(query, r.get("name", "")), r) for r in results),
                    key=lambda t: t[0], reverse=True)
    if interactive and scored and scored[0][0] < 0.999:
        print(f"\n  {label}  ->  searching \"{query}\"")
        for i, (s, r) in enumerate(scored[:8], 1):
            yr = ""
            if r.get("release_date"):
                yr = f" ({time.gmtime(r['release_date']).tm_year})"
            print(f"    {i}. {r.get('name')}{yr}   [{s:.0%}]")
        ans = input("    Pick number, Enter = 1, s = skip, or type a new search: ").strip()
        if ans.lower() == "s":
            return None, 0
        if ans == "" and scored:
            return scored[0][1], scored[0][0]
        if ans.isdigit() and 1 <= int(ans) <= len(scored[:8]):
            s, r = scored[int(ans) - 1]
            return r, s
        return ("RESEARCH", ans), 0
    if scored and scored[0][0] >= threshold:
        return scored[0][1], scored[0][0]
    return None, scored[0][0] if scored else 0


def download(url, dest, dry):
    if dry:
        return True
    tmp = dest + ".part"
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=60) as r, open(tmp, "wb") as f:
            while True:
                chunk = r.read(65536)
                if not chunk:
                    break
                f.write(chunk)
        os.replace(tmp, dest)
        return True
    except (urllib.error.URLError, OSError, TimeoutError) as e:
        try:
            os.remove(tmp)
        except OSError:
            pass
        print(f"      ! download failed: {e}")
        return False


# ----------------------------------------------------------------------------- main

def parse_args(argv=None):
    p = argparse.ArgumentParser(
        description="Download SteamGridDB artwork into ES-DE's downloaded_media folders.")
    p.add_argument("--roms", help="ES-DE ROMs directory (default: auto-detect /storage/emulated/0/ROMs)")
    p.add_argument("--esde", help="ES-DE data directory (default: auto-detect /storage/emulated/0/ES-DE)")
    p.add_argument("--media-dir", help="Custom media directory if you set one in ES-DE "
                                       "(default: <ES-DE dir>/downloaded_media)")
    p.add_argument("--systems", nargs="+", metavar="SYS",
                   help="Only these system folders, e.g. --systems epic steam windows")
    p.add_argument("--skip-systems", nargs="+", metavar="SYS", default=[],
                   help="System folders to leave alone")
    p.add_argument("--media", nargs="+", choices=list(MEDIA_MAP), default=DEFAULT_MEDIA,
                   help=f"Which media to fetch (default: {' '.join(DEFAULT_MEDIA)})")
    p.add_argument("--overwrite", action="store_true", help="Replace art that already exists")
    p.add_argument("--only-missing", action="store_true",
                   help="Skip a game entirely if it already has a cover")
    p.add_argument("--threshold", type=float, default=0.72,
                   help="How close a name match must be, 0-1 (default 0.72)")
    p.add_argument("-i", "--interactive", action="store_true",
                   help="Ask you to choose when a match isn't exact")
    p.add_argument("-n", "--dry-run", action="store_true", help="Show what would happen, save nothing")
    p.add_argument("--set-key", nargs="?", const="", metavar="KEY",
                   help="Save your SteamGridDB API key (prompts if no value given)")
    p.add_argument("--key", help="Use this API key for this run only")
    p.add_argument("-q", "--quiet", action="store_true", help="Only print a summary")
    return p.parse_args(argv)


def main(argv=None, api_factory=SGDB):
    args = parse_args(argv)
    cfg = load_config()

    if args.set_key is not None:
        key = args.set_key or input("Paste your SteamGridDB API key: ").strip()
        cfg["api_key"] = key
        save_config(cfg)
        print(f"Saved key to {CONFIG_PATH}")
        if not (args.roms or args.systems):
            return 0

    key = args.key or os.environ.get("SGDB_API_KEY") or cfg.get("api_key")
    if not key:
        print("No SteamGridDB API key yet.\n"
              "Get one free at https://www.steamgriddb.com/profile/preferences/api\n"
              "then run:  python sgdb_esde.py --set-key")
        return 2

    rom_dir = os.path.expanduser(args.roms) if args.roms else first_existing(ROM_DIR_GUESSES)
    esde_dir = os.path.expanduser(args.esde) if args.esde else first_existing(ESDE_DIR_GUESSES)
    if not rom_dir or not os.path.isdir(rom_dir):
        print("Couldn't find your ROMs folder. Pass it with --roms /path/to/ROMs")
        return 2
    if args.media_dir:
        media_root = os.path.expanduser(args.media_dir)
    elif esde_dir:
        media_root = os.path.join(esde_dir, "downloaded_media")
    else:
        print("Couldn't find the ES-DE folder. Pass it with --esde /path/to/ES-DE")
        return 2

    log(f"ROMs:  {rom_dir}\nMedia: {media_root}\nFetching: {', '.join(args.media)}"
        + ("   (DRY RUN - nothing will be saved)" if args.dry_run else ""))

    api = api_factory(key)
    systems = sorted(d for d in os.listdir(rom_dir)
                     if os.path.isdir(os.path.join(rom_dir, d)) and not d.startswith("."))
    if args.systems:
        wanted = {s.lower() for s in args.systems}
        systems = [s for s in systems if s.lower() in wanted]
    skip = {s.lower() for s in args.skip_systems}
    systems = [s for s in systems if s.lower() not in skip]

    stats = {"games": 0, "matched": 0, "saved": 0, "kept": 0, "nomatch": 0, "noart": 0}
    misses = []

    for system in systems:
        sys_dir = os.path.join(rom_dir, system)
        exts = system_extensions(sys_dir)
        games = list(find_games(sys_dir, exts))
        hidden = m3u_children(sys_dir, games)
        games = [g for g in games if os.path.normpath(g[0]) not in hidden]
        if not games:
            continue
        names = gamelist_names(esde_dir or "", system, rom_dir) if esde_dir else {}
        log(f"\n== {system}  ({len(games)} games)", args.quiet)

        for rel, is_dir in games:
            stats["games"] += 1
            sub, fname = os.path.split(rel)
            base = os.path.splitext(fname)[0]

            # what's still needed?
            todo = []
            for m in args.media:
                mdir = os.path.join(media_root, system, m, sub)
                have = existing_media(mdir, base)
                if have and not args.overwrite:
                    stats["kept"] += 1
                    continue
                todo.append((m, mdir, have))
            if args.only_missing and not args.overwrite and \
                    existing_media(os.path.join(media_root, system, "covers", sub), base):
                todo = []
            if not todo:
                log(f"  ✓ {base}  (already has art)", args.quiet)
                continue

            # find the game on SteamGridDB
            game, score = None, 0.0
            appid = None if is_dir else steam_appid_from_file(os.path.join(sys_dir, rel))
            if appid:
                game = api.game_by_steam(appid)
                score = 1.0 if game else 0.0
            query = names.get(os.path.normpath(rel)) or clean_name(base)
            while game is None:
                results = api.search(query)
                game, score = pick_match(results, query, args.threshold, args.interactive, base)
                if isinstance(game, tuple) and game[0] == "RESEARCH":
                    query, game = game[1], None
                    continue
                break
            if not game:
                stats["nomatch"] += 1
                misses.append(f"{system}/{rel}\t(searched: {query}; best score {score:.2f})")
                log(f"  ✗ {base}  - no confident match for \"{query}\"", args.quiet)
                continue

            stats["matched"] += 1
            got = []
            for m, mdir, have in todo:
                for kind, params in MEDIA_MAP[m]:
                    imgs = api.images(kind, game["id"], params)
                    if not imgs:
                        continue
                    url = imgs[0]["url"]
                    ext = os.path.splitext(urllib.parse.urlparse(url).path)[1].lower() or ".png"
                    if ext == ".jpeg":
                        ext = ".jpg"
                    dest = os.path.join(mdir, base + ext)
                    if not args.dry_run:
                        os.makedirs(mdir, exist_ok=True)
                        # ES-DE picks one file per name; remove the old one in another format
                        if have and os.path.abspath(have) != os.path.abspath(dest):
                            os.remove(have)
                    if download(url, dest, args.dry_run):
                        stats["saved"] += 1
                        got.append(m)
                    break
            if got:
                log(f"  + {base}  ->  {game.get('name')}  [{score:.0%}]  {', '.join(got)}", args.quiet)
            else:
                stats["noart"] += 1
                log(f"  ~ {base}  ->  {game.get('name')}  (found, but no art of the wanted types)",
                    args.quiet)

    print(f"\nDone. {stats['games']} games checked, {stats['matched']} matched, "
          f"{stats['saved']} images {'would be ' if args.dry_run else ''}saved, "
          f"{stats['nomatch']} not found, {stats['noart']} matched without art.")
    if misses:
        miss_path = os.path.join(media_root, "sgdb_unmatched.txt")
        if not args.dry_run:
            os.makedirs(media_root, exist_ok=True)
            with open(miss_path, "w", encoding="utf-8") as f:
                f.write("\n".join(misses) + "\n")
            print(f"Unmatched games listed in {miss_path}\n"
                  "Tip: re-run with -i to pick matches by hand, or rename the file to the game's real title.")
    if stats["saved"] and not args.dry_run:
        print("Restart ES-DE (or Utilities > Rescan ROM directory) to see the new art.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\nStopped.")
        sys.exit(130)
