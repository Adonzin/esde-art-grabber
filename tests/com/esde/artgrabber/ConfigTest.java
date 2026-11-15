package com.esde.artgrabber;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;

/** EsdeConfig against a realistic ES-DE folder that already has user-made configuration. */
public class ConfigTest {
    static int fails = 0;

    static void check(boolean c, String w) {
        if (!c) { System.out.println("FAIL " + w); fails++; } else System.out.println("  ok  " + w);
    }

    static void write(File f, String t) throws IOException {
        f.getParentFile().mkdirs();
        Writer w = new FileWriter(f);
        w.write(t);
        w.close();
    }

    static String read(File f) { String s = EsdeConfig.read(f); return s == null ? "" : s; }

    static boolean wellFormed(File f) {
        try { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f); return true; }
        catch (Exception e) { System.out.println("    xml error: " + e.getMessage()); return false; }
    }

    public static void main(String[] a) throws Exception {
        File R = new File(a[0]);
        File esde = new File(R, "ES-DE"), roms = new File(R, "ROMs");
        roms.mkdirs();
        String userSystem = "<?xml version=\"1.0\"?>\n<systemList>\n    <system>\n        <name>dos</name>\n"
                + "        <fullname>DOS (moved to SD)</fullname>\n        <path>/storage/ABCD/ROMs/dos</path>\n"
                + "        <extension>.zip</extension>\n        <command label=\"DOSBox\">%EMULATOR_DOSBOX% %DATA%=%ROMSAF%</command>\n"
                + "        <platform>pc</platform>\n        <theme>dos</theme>\n    </system>\n</systemList>\n";
        write(new File(esde, "custom_systems/es_systems.xml"), userSystem);
        String settings = "<?xml version=\"1.0\"?>\n<bool name=\"ShowHelpPrompts\" value=\"true\" />\n"
                + "<string name=\"CollectionSystemsCustom\" value=\"Action\" />\n<string name=\"ThemeSet\" value=\"linear-es-de\" />\n";
        write(new File(esde, "settings/es_settings.xml"), settings);
        write(new File(esde, "collections/custom-Action.cfg"), "%ROMPATH%/snes/Contra III.sfc\n");

        EsdeConfig cfg = new EsdeConfig(esde, roms);
        File sys = cfg.systemsFile();

        // ---- names
        check(EsdeConfig.systemNameFor("Streaming & Video!").equals("streamingvideo"), "system name from display name");
        check(cfg.nameProblem("nes", null) != null && cfg.nameProblem("androidapps", null) != null, "ES-DE's own system names refused");
        check(cfg.nameProblem("dos", null) != null, "name of a system already in the custom file refused");
        new File(roms, "taken").mkdirs();
        check(cfg.nameProblem("taken", null) != null, "existing ROM folder refused");
        check(cfg.nameProblem("streaming", null) == null, "free name accepted");

        // ---- Android app category
        EsdeConfig.Category st = new EsdeConfig.Category();
        st.name = "streaming";
        st.fullName = "Streaming & Video";
        Map<String, String> apps = new LinkedHashMap<String, String>();
        apps.put("Netflix", "com.netflix.mediaclient");
        apps.put("YouTube: Kids?", "com.google.android.apps.youtube.kids");
        cfg.saveAppCategory(st, apps);
        String s1 = read(sys);
        check(s1.contains("<name>dos</name>") && s1.contains("/storage/ABCD/ROMs/dos"), "user's own custom system untouched");
        check(s1.contains(EsdeConfig.MARK + "\n    <system>\n        <name>streaming</name>"), "category added with its marker");
        check(s1.contains("<fullname>Streaming &amp; Video</fullname>") && s1.contains("%ANDROIDAPP%=%FILEINJECT%")
                && s1.contains("<theme>androidapps</theme>"), "same launch command and look as ES-DE's Android Apps");
        check(wellFormed(sys), "custom systems file is valid XML");
        check(read(new File(roms, "streaming/Netflix.app")).trim().equals("com.netflix.mediaclient"), "entry file holds the package name");
        check(new File(roms, "streaming/YouTube Kids.app").isFile(), "unsafe characters removed from file names");
        check(cfg.find(EsdeConfig.KIND_APPS, "streaming") != null, "category remembered");

        // ---- update: swap an app; user's own file in the folder survives
        write(new File(roms, "streaming/my notes.txt"), "mine");
        String beforeUpdate = read(sys);
        apps.remove("Netflix");
        apps.put("Plex", "com.plexapp.android");
        st.fullName = "Streaming";
        cfg.saveAppCategory(st, apps);
        check(!new File(roms, "streaming/Netflix.app").exists() && new File(roms, "streaming/Plex.app").isFile(), "apps added and removed");
        check(new File(roms, "streaming/my notes.txt").isFile(), "files the app didn't make are never deleted");
        String s2 = read(sys);
        check(s2.split("<name>streaming</name>", -1).length == 2 && s2.contains("<fullname>Streaming</fullname>"), "system replaced, not duplicated");
        check(cfg.apps(st).keySet().equals(new HashSet<String>(Arrays.asList("YouTube Kids", "Plex"))), "lists current apps");

        // ---- undo the update
        check(cfg.lastChange().contains("Streaming"), "knows the last change");
        cfg.undo();
        check(read(sys).equals(beforeUpdate), "undo restores the systems file exactly");
        check(new File(roms, "streaming/Netflix.app").isFile() && !new File(roms, "streaming/Plex.app").exists(), "undo restores the app entries");

        // ---- someone else's system with our name is never overwritten
        EsdeConfig.Category clash = new EsdeConfig.Category();
        clash.name = "dos";
        clash.fullName = "Clash";
        boolean refused = false;
        try { cfg.saveAppCategory(clash, apps); } catch (IOException e) { refused = true; }
        check(refused && read(sys).contains("DOS (moved to SD)"), "refuses to replace a system the user wrote");

        // ---- Moonlight
        EsdeConfig.Category ml = new EsdeConfig.Category();
        ml.name = "moonlight";
        ml.fullName = "Moonlight – Gaming PC";
        ml.pc = "GAMING-PC";
        cfg.saveMoonlightCategory(ml, Arrays.asList("Cyberpunk 2077", "Desktop", "Steam Big Picture"));
        String s3 = read(sys);
        check(s3.contains("%EMULATOR_MOONLIGHT% %EXTRA_Name%=\"GAMING-PC\" %EXTRA_AppName%=\"%BASENAME%\""), "Moonlight launch command passes computer and game name");
        check(new File(roms, "moonlight/Cyberpunk 2077.moonlight").isFile(), "one file per game, named like the game");
        String rules = read(cfg.findRulesFile());
        check(rules.contains("com.limelight/com.limelight.ShortcutTrampoline") && wellFormed(cfg.findRulesFile()), "Moonlight find rule written, valid XML");
        check(!EsdeConfig.moonlightNameOk("Halo: MCC") && EsdeConfig.moonlightNameOk("Halo MCC"), "names that can't be file names are detected");

        EsdeConfig.Category ml2 = new EsdeConfig.Category();
        ml2.name = "moonlightlaptop";
        ml2.fullName = "Moonlight – Laptop";
        ml2.pc = "Laptop";
        cfg.saveMoonlightCategory(ml2, Collections.singletonList("Hades"));
        check(read(cfg.findRulesFile()).split("<emulator name=\"MOONLIGHT\">", -1).length == 2, "one shared Moonlight rule for several computers");
        cfg.delete(ml);
        check(read(cfg.findRulesFile()).contains("MOONLIGHT") && !new File(roms, "moonlight").exists(), "deleting one keeps the rule; empty folder removed");
        cfg.delete(ml2);
        check(!read(cfg.findRulesFile()).contains("MOONLIGHT") && !read(sys).contains("moonlight"), "deleting the last removes the rule and systems");

        // a Moonlight rule the user wrote is left alone
        String own = "<?xml version=\"1.0\"?>\n<ruleList>\n    <emulator name=\"MOONLIGHT\">\n        <rule type=\"androidpackage\">\n"
                + "            <entry>com.limelight.custom/com.limelight.ShortcutTrampoline</entry>\n        </rule>\n    </emulator>\n</ruleList>\n";
        write(cfg.findRulesFile(), own);
        cfg.saveMoonlightCategory(ml, Collections.singletonList("Desktop"));
        check(read(cfg.findRulesFile()).equals(own), "user's own Moonlight rule not replaced");

        // ---- collection
        EsdeConfig.Category col = new EsdeConfig.Category();
        col.name = EsdeConfig.collectionNameFor("Media/Apps.");
        col.fullName = "Media Apps";
        col.separate = true;
        cfg.saveCollection(col, Arrays.asList(new File(roms, "androidapps/VLC.app"), new File(roms, "streaming/Plex.app")));
        check(col.name.equals("MediaApps") && cfg.collectionFile("MediaApps").isFile(), "collection file created with a safe name");
        check(read(cfg.collectionFile("MediaApps")).contains(new File(roms, "androidapps/VLC.app").getAbsolutePath()), "collection lists the entries");
        check("Action,MediaApps".equals(cfg.setting("CollectionSystemsCustom")), "collection switched on, existing ones kept: " + cfg.setting("CollectionSystemsCustom"));
        check("never".equals(cfg.setting("CollectionCustomGrouping")), "shown as its own item when asked");
        String set = read(cfg.settingsFile());
        check(set.contains("<string name=\"ThemeSet\" value=\"linear-es-de\" />") && set.contains("<bool name=\"ShowHelpPrompts\" value=\"true\" />"), "other settings untouched");
        check(read(new File(esde, "collections/custom-Action.cfg")).contains("Contra"), "existing collection untouched");
        write(cfg.collectionFile("MediaApps"), "%ROMPATH%/androidapps/VLC.app\n");   // as ES-DE rewrites it
        check(cfg.collectionEntries(col).contains(new File(roms, "androidapps/VLC.app").getAbsolutePath()), "%ROMPATH% paths understood");
        cfg.delete(col);
        check(!cfg.collectionFile("MediaApps").exists() && "Action".equals(cfg.setting("CollectionSystemsCustom")), "deleting switches it off again");

        // ---- undo a creation completely
        EsdeConfig.Category tools = new EsdeConfig.Category();
        tools.name = "tools";
        tools.fullName = "Tools";
        String beforeTools = read(sys);
        cfg.saveAppCategory(tools, Collections.singletonMap("Files", "com.google.android.documentsui"));
        cfg.undo();
        check(!new File(roms, "tools").exists() && read(sys).equals(beforeTools) && cfg.find(EsdeConfig.KIND_APPS, "tools") == null,
                "undoing a new category removes its folder, entries and system");
        check(wellFormed(sys), "systems file still valid after everything");

        System.out.println(fails == 0 ? "CONFIG TESTS OK" : fails + " FAILURES");
        if (fails > 0) System.exit(1);
    }
}
