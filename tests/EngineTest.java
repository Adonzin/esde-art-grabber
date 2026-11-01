import com.esde.artgrabber.*;
import java.io.*;
import java.util.*;

public class EngineTest {
    static File R;
    static void touch(String p, String t) throws Exception {
        File f = new File(R, p); f.getParentFile().mkdirs();
        try (Writer w = new FileWriter(f)) { w.write(t); }
    }
    static void check(boolean c, String what) { if (!c) throw new AssertionError(what); System.out.println("  ok  " + what); }
    static boolean exists(String p) { return new File(R, p).isFile(); }
    static String read(String p) throws Exception { return new String(java.nio.file.Files.readAllBytes(new File(R,p).toPath())); }

    static class Fake extends Sgdb {
        static Map<String,Long> DB = new LinkedHashMap<>();
        static { DB.put("The Legend of Zelda - A Link to the Past",1L); DB.put("Super Mario World",2L);
                 DB.put("Final Fantasy VII",3L); DB.put("Hades II",5L); DB.put("Hades",4L); DB.put("Demon's Souls",6L); }
        int searches = 0;
        Fake() { super("x"); }
        @Override public List<Game> search(String t) { searches++;
            List<Game> o = new ArrayList<>(); String k = Names.norm(t); k = k.substring(0, Math.min(4,k.length()));
            for (Map.Entry<String,Long> e : DB.entrySet()) if (Names.norm(e.getKey()).contains(k)) o.add(new Game(e.getValue(), e.getKey(), 0));
            return o; }
        @Override public Game gameBySteamId(String id) { return new Game(99, "Hades (steam " + id + ")", 0); }
        @Override public List<Image> images(String kind, long id, String dims) {
            if (kind.equals("logos") && id == 6) return new ArrayList<>();
            return Arrays.asList(new Image("https://cdn/" + kind + "/" + id + (kind.equals("heroes") ? ".jpg" : ".png?v=2"), 1)); }
        @Override public void download(String url, File dest) throws IOException {
            try (Writer w = new FileWriter(dest)) { w.write(url); } }
    }

    public static void main(String[] a) throws Exception {
        R = new File(a[0]);
        touch("ROMs/snes/Legend of Zelda, The - A Link to the Past (USA) [!].sfc", "");
        touch("ROMs/snes/Super_Mario_World (Europe) (Rev 1).smc", "");
        touch("ROMs/snes/notes.txt", "");
        touch("ROMs/snes/Hacks/Totally Unknown Hack.sfc", "");
        touch("ROMs/snes/systeminfo.txt", "System name:\nsnes\n\nSupported file extensions:\n.sfc .SFC .smc .SMC .zip .ZIP\n\nLaunch command:\nfoo\n");
        touch("ROMs/psx/Final Fantasy VII (USA).m3u", "Final Fantasy VII (USA) (Disc 1).chd\nFinal Fantasy VII (USA) (Disc 2).chd\n");
        touch("ROMs/psx/Final Fantasy VII (USA) (Disc 1).chd", ""); touch("ROMs/psx/Final Fantasy VII (USA) (Disc 2).chd", "");
        touch("ROMs/epic/a1b2c3.epic", "marker");
        touch("ES-DE/gamelists/epic/gamelist.xml", "<gameList><game><path>./a1b2c3.epic</path><name>Hades</name></game></gameList>");
        touch("ROMs/steam/thing.steam", "appid=1145360\n");
        touch("ROMs/ps3/Demon's Souls.ps3/PS3_GAME/x.bin", "");
        touch("ROMs/ps3/systeminfo.txt", "Supported file extensions:\n.desktop .ps3 .PS3\n");
        touch("ES-DE/downloaded_media/snes/covers/Super_Mario_World (Europe) (Rev 1).jpg", "old");
        touch("ES-DE/settings/es_settings.xml", "<?xml version=\"1.0\"?>\n<string name=\"MediaDirectory\" value=\"\" />\n<string name=\"ROMDirectory\" value=\"" + new File(R,"ROMs").getPath() + "\" />\n");

        // folder detection
        File esde = Engine.findEsdeDir(Arrays.asList(new File(R,"nope"), R));
        check(esde != null && esde.getName().equals("ES-DE"), "finds ES-DE folder");
        File roms = Engine.findRomDir(esde, Arrays.asList(R));
        check(roms.equals(new File(R,"ROMs")), "reads ROMDirectory from es_settings.xml");
        File media = Engine.defaultMediaDir(esde);
        check(media.equals(new File(esde,"downloaded_media")), "default media dir");

        Engine e = new Engine(roms, esde, media);
        final List<String> log = new ArrayList<>();
        Engine.Listener l = new Engine.Listener() { public void log(String s){ log.add(s); System.out.println("    | " + s); } public void progress(int d,int t){} };

        Engine.Options o = new Engine.Options(); o.dryRun = true;
        Engine.Result r = e.run(new Fake(), o, l);
        check(!new File(R,"ES-DE/downloaded_media/snes/marquees").exists(), "dry run writes nothing");
        check(r.games == 7, "7 games found (txt ignored, m3u discs hidden, ps3 folder-game) got " + r.games);

        o = new Engine.Options();
        r = e.run(new Fake(), o, l);
        String M = "ES-DE/downloaded_media/";
        check(exists(M+"snes/covers/Legend of Zelda, The - A Link to the Past (USA) [!].png"), "zelda cover");
        check(read(M+"snes/covers/Super_Mario_World (Europe) (Rev 1).jpg").equals("old"), "existing cover kept");
        check(read(M+"epic/covers/a1b2c3.png").contains("grids/4"), "epic marker matched via gamelist name (Hades, not Hades II)");
        check(read(M+"steam/covers/thing.png").contains("grids/99"), "steam appid exact match");
        check(exists(M+"ps3/covers/Demon's Souls.png") && !new File(R,M+"ps3/marquees").exists(), "folder-game + missing logo handled");
        check(exists(M+"psx/fanart/Final Fantasy VII (USA).jpg"), "fanart saved as jpg");
        check(r.misses.size()==1 && r.misses.get(0).game.rel.equals("Hacks/Totally Unknown Hack.sfc"), "unmatched reported");
        check(r.saved == 22, "22 images saved, got " + r.saved);

        Fake f2 = new Fake(); r = e.run(f2, new Engine.Options(), l);
        check(f2.searches == 2 && r.saved == 0, "second run only retries the miss + the game missing a logo");

        o = new Engine.Options(); o.overwrite = true; o.systems = new HashSet<>(Arrays.asList("snes"));
        o.manualMatches.put("snes/Hacks/Totally Unknown Hack.sfc", 2L);
        r = e.run(new Fake(), o, l);
        check(!exists(M+"snes/covers/Super_Mario_World (Europe) (Rev 1).jpg") && exists(M+"snes/covers/Super_Mario_World (Europe) (Rev 1).png"), "overwrite replaces old format");
        check(exists(M+"snes/covers/Hacks/Totally Unknown Hack.png"), "manual match used, subfolder mirrored");


        // ---- Android app / emulator marquees
        touch("ROMs/androidapps/RetroArch.app", "com.retroarch\n");
        touch("ROMs/androidapps/Removed App.app", "com.not.installed");
        touch("ROMs/androidapps/systeminfo.txt", "Supported file extensions:\n.foo\n");
        touch("ROMs/emulators/Dolphin Emulator.app", "org.dolphinemu.dolphinemu");
        touch("ROMs/emulators/com.github.stenzek.duckstation.app", "");
        touch("ROMs/androidgames/Stardew Valley.app", "com.chucklefish.stardewvalley");
        final Set<String> installed = new HashSet<>(Arrays.asList("com.retroarch","org.dolphinemu.dolphinemu","com.chucklefish.stardewvalley","com.github.stenzek.duckstation"));
        Fake.DB.put("Stardew Valley", 7L);
        Engine e2 = new Engine(roms, esde, media);
        e2.appArt = new Engine.AppArt() {
            public boolean isInstalled(String p) { return installed.contains(p); }
            public boolean saveMarquee(String p, File d) throws IOException { try (Writer w = new FileWriter(d)) { w.write("icon:" + p); } return true; } };
        o = new Engine.Options(); o.systems = new HashSet<>(Arrays.asList("androidapps","emulators","androidgames"));
        Fake f3 = new Fake(); r = e2.run(f3, o, l);
        check(read(M+"androidapps/marquees/RetroArch.png").equals("icon:com.retroarch"), "app marquee from package in .app file (ext not in systeminfo)");
        for (String mt : Engine.APP_ICON_MEDIA)
            check(read(M+"androidapps/" + mt + "/RetroArch.png").equals("icon:com.retroarch"), "app icon used as " + mt);
        for (String mt : new String[]{"titlescreens","3dboxes","backcovers","physicalmedia"})
            check(!new File(R,M+"androidapps/" + mt).exists(), "unused type not created: " + mt);
        // leftovers from version 1.8: exact copies get removed, anything else is kept
        touch(M+"emulators/titlescreens/Dolphin Emulator.png", "icon:org.dolphinemu.dolphinemu");
        touch(M+"emulators/3dboxes/Dolphin Emulator.png", "icon:org.dolphinemu.dolphinemu");
        touch(M+"emulators/backcovers/Dolphin Emulator.png", "a real back cover the user added");
        Engine.Result rc = e2.run(new Fake(), o, l);
        check(!exists(M+"emulators/titlescreens/Dolphin Emulator.png") && !exists(M+"emulators/3dboxes/Dolphin Emulator.png"), "1.8's identical icon copies cleaned up");
        check(exists(M+"emulators/backcovers/Dolphin Emulator.png"), "user's own different image kept");
        check(rc.cleaned == 2 && rc.saved == 0, "cleanup counted, nothing re-downloaded: cleaned=" + rc.cleaned + " saved=" + rc.saved);
        check(!new File(R,M+"androidapps/fanart/RetroArch.png").exists() && !new File(R,M+"androidapps/fanart/RetroArch.jpg").exists(), "no fanart for apps");
        check(read(M+"emulators/covers/Dolphin Emulator.png").equals("icon:org.dolphinemu.dolphinemu"), "emulator icon used as cover too");
        check(!exists(M+"androidgames/screenshots/Stardew Valley.png") || read(M+"androidgames/screenshots/Stardew Valley.png").contains("cdn"), "android games still get SteamGridDB art, not icon everywhere");
        check(read(M+"emulators/marquees/Dolphin Emulator.png").equals("icon:org.dolphinemu.dolphinemu"), "emulator marquee");
        check(exists(M+"emulators/marquees/com.github.stenzek.duckstation.png"), "package-as-filename fallback");
        check(read(M+"androidgames/marquees/Stardew Valley.png").startsWith("icon:") && read(M+"androidgames/covers/Stardew Valley.png").contains("grids/7"), "android game: icon marquee + SteamGridDB cover");
        check(r.misses.size()==1 && r.misses.get(0).game.base().equals("Removed App"), "uninstalled app reported as unmatched");
        r = e2.run(new Fake(), o, l);
        check(r.saved == 0, "second run leaves app marquees alone");
        o.appMarquees = false; o.overwrite = true; o.systems = new HashSet<>(Arrays.asList("emulators"));
        r = e2.run(new Fake(), o, l);
        check(read(M+"emulators/marquees/Dolphin Emulator.png").startsWith("icon:"), "toggle off: app marquee not touched by SGDB miss");


        // ---- systems spread across internal storage, SD card and ES-DE custom paths
        File R2 = new File(R, "multi"); File oldR = R; R = R2;
        touch("internal/ROMs/steam/Hades.steam", "x");
        touch("internal/ROMs/snes/empty.txt", "");
        touch("sdcard/ROMs/snes/Super Mario World.sfc", ""); touch("sdcard/ROMs/snes/Zelda.sfc", "");
        touch("sdcard/ROMs/psx/FF7.chd", "");
        touch("sdcard/Games/n64/Mario 64.z64", "");
        touch("internal/ROMs/gc/x.iso", "");
        touch("internal/ES-DE/settings/es_settings.xml", "<string name=\"ROMDirectory\" value=\"" + new File(R2,"internal/ROMs") + "\" />");
        touch("internal/ES-DE/custom_systems/es_systems.xml", "<systemList><system><name>n64</name><path>" + new File(R2,"sdcard/Games/n64") + "</path></system>"
            + "<system><name>gc</name><path>%ROMPATH%/gc</path></system></systemList>");
        List<File> st = Arrays.asList(new File(R2,"internal"), new File(R2,"sdcard"));
        File esde2 = Engine.findEsdeDir(st);
        File rom2 = Engine.findRomDir(esde2, st);
        Map<String, File> sys = Engine.discoverSystems(rom2, esde2, st);
        System.out.println("    systems: " + sys);
        check(sys.keySet().equals(new TreeSet<>(Arrays.asList("gc","n64","psx","snes","steam"))), "all systems across internal + SD + custom paths");
        check(sys.get("snes").getPath().contains("sdcard"), "snes taken from the copy with games (SD), not the near-empty internal one");
        check(sys.get("n64").getPath().endsWith("Games/n64"), "custom_systems absolute path");
        Engine e3 = new Engine(rom2, esde2, new File(R2, "media"), sys);
        Engine.Options o3 = new Engine.Options(); o3.dryRun = true;
        Engine.Result r3 = e3.run(new Fake(), o3, l);
        check(r3.games == 6, "scans games in every location: " + r3.games);
        File noEsdeRom = Engine.findRomDir(null, st);
        check(noEsdeRom.getPath().contains("sdcard") || noEsdeRom.getPath().contains("internal"), "fallback picks a ROMs folder");
        R = oldR;


        // ---- .epic in windows folder with an outdated systeminfo.txt
        touch("ROMs/windows/systeminfo.txt", "System name:\nwindows\n\nFull system name:\nMicrosoft Windows\n\nSupported file extensions:\n.bat .BAT .exe .EXE .lnk .LNK\n\nLaunch command:\n%EMULATOR_WINLATOR% %ROM%\n");
        touch("ROMs/windows/Hades.epic", "x");
        touch("ROMs/windows/Celeste.gog", "x");
        touch("ROMs/windows/weird.xyz", "x");
        touch("ROMs/windows/save.sav", "x");
        Engine e4 = new Engine(roms, esde, media);
        List<Engine.GameFile> wg = e4.scanSystem("windows");
        List<String> wn = new ArrayList<>(); for (Engine.GameFile x : wg) wn.add(x.rel);
        check(wn.equals(Arrays.asList("Celeste.gog","Hades.epic")), "windows: .epic/.gog accepted despite old systeminfo: " + wn);
        final List<String> log4 = new ArrayList<>();
        Engine.Options o4 = new Engine.Options(); o4.dryRun = true; o4.systems = new HashSet<>(Arrays.asList("windows"));
        e4.run(new Fake(), o4, new Engine.Listener(){ public void log(String s){ log4.add(s);} public void progress(int a,int b){} });
        boolean noted = false; for (String x : log4) if (x.contains("windows: skipped 1 file") && x.contains(".xyz")) noted = true;
        check(noted, "skipped unsupported file is reported (saves ignored silently)");
        boolean hint = false; for (String x : log4) if (x.contains("No Android app")) hint = true;
        check(!hint, "no app hint when android systems not selected");

        String[][] names = {{"Legend of Zelda, The (USA)","The Legend of Zelda"},{"Super.Mario.Bros.3","Super Mario Bros 3"},
            {"Pokemon - Red Version (USA, Europe) (SGB Enhanced)","Pokemon - Red Version"},{"Castlevania v1.1","Castlevania"},
            {"Metal Gear Solid (USA) (Disc 1) (v1.1)","Metal Gear Solid"}};
        for (String[] n : names) check(Names.clean(n[0]).equals(n[1]), "clean: " + n[0] + " -> " + Names.clean(n[0]));
        check(Names.similarity("Hades","Hades") == 1.0 && Names.similarity("Hades","Hades II") < 1.0, "exact beats sequel");
        check(Names.similarity("Mario Kart", "Zelda") < 0.5, "unrelated scores low: " + Names.similarity("Mario Kart","Zelda"));
        System.out.println("ALL OK");
    }
}
