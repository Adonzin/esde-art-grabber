package com.esde.artgrabber;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The Categories screen: make ES-DE systems and collections without touching XML or folders. */
class CategoriesScreen {
    private final MainActivity a;
    private Dialog screen;
    private ListView list;
    private TextView undoText;
    private Button undoBtn;
    private final List<EsdeConfig.Category> shown = new ArrayList<EsdeConfig.Category>();

    CategoriesScreen(MainActivity a) { this.a = a; }

    private EsdeConfig cfg() { return new EsdeConfig(a.esdeDir, a.romDir); }

    // ================================================================ main screen

    void open() {
        if (!a.canUseStorage()) { a.refreshAccessCard(); a.toast("Allow file access first."); return; }
        if (a.esdeDir == null || a.romDir == null) {
            a.toast("Set your ROMs folder and let the app find ES-DE first (Folders card).");
            return;
        }
        screen = new Dialog(a, android.R.style.Theme_Material_NoActionBar_Fullscreen);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MainActivity.BG);
        root.setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(12));

        LinearLayout top = a.row();
        LinearLayout titles = new LinearLayout(a);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(a.text("Categories", 22, MainActivity.TEXT, true));
        titles.addView(a.text("Your own systems and collections for ES-DE", 13, MainActivity.MUTED, false));
        a.addWeighted(top, titles, 1, 0);
        Button done = a.button("Done", true);
        top.addView(done);
        root.addView(top);

        TextView warn = a.text("Close ES-DE before making changes (swipe it away from recent apps). "
                + "Changes appear the next time you open ES-DE.", 13, MainActivity.WARN, false);
        warn.setBackground(a.box(Color.parseColor("#3A2A12"), MainActivity.WARN, 10, 1));
        warn.setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(-1, -2);
        wlp.topMargin = a.dp(10);
        root.addView(warn, wlp);

        LinearLayout make = a.row();
        make.setPadding(0, a.dp(10), 0, a.dp(10));
        Button newApps = a.button("+ App category", true);
        Button newMoon = a.button("+ Moonlight", true);
        Button newCol = a.button("+ Collection", true);
        a.addWeighted(make, newApps, 1, 0);
        a.addWeighted(make, newMoon, 1, 8);
        a.addWeighted(make, newCol, 1, 8);
        root.addView(make);

        TextView empty = a.text("No categories yet. Start with one of the buttons above.", 14, MainActivity.MUTED, false);
        empty.setPadding(a.dp(4), a.dp(16), 0, 0);
        root.addView(empty);
        list = new ListView(a);
        list.setEmptyView(empty);
        list.setDivider(null);
        list.setSelector(a.box(Color.TRANSPARENT, Color.WHITE, 10, 2));
        list.setDrawSelectorOnTop(true);
        root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout bottom = a.row();
        bottom.setPadding(0, a.dp(8), 0, 0);
        undoText = a.text("", 13, MainActivity.MUTED, false);
        a.addWeighted(bottom, undoText, 1, 0);
        undoBtn = a.button("Undo last change", false);
        bottom.addView(undoBtn);
        root.addView(bottom);

        done.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { screen.dismiss(); } });
        newApps.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { editDetails(null, EsdeConfig.KIND_APPS); } });
        newMoon.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { editDetails(null, EsdeConfig.KIND_MOONLIGHT); } });
        newCol.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { editDetails(null, EsdeConfig.KIND_COLLECTION); } });
        undoBtn.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { undo(); } });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) { categoryMenu(shown.get(pos)); }
        });

        screen.setContentView(root);
        screen.show();
        refresh();
        newApps.requestFocus();
    }

    private void refresh() {
        final EsdeConfig cfg = cfg();
        shown.clear();
        shown.addAll(cfg.categories());
        final List<String> labels = new ArrayList<String>();
        for (EsdeConfig.Category c : shown) labels.add(describe(cfg, c));
        list.setAdapter(new ArrayAdapter<String>(a, android.R.layout.simple_list_item_1, labels) {
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                TextView tv = (TextView) super.getView(pos, cv, parent);
                tv.setTextColor(MainActivity.TEXT);
                tv.setBackground(a.box(MainActivity.CARD, 0, 10, 0));
                tv.setPadding(a.dp(14), a.dp(12), a.dp(14), a.dp(12));
                return tv;
            }
        });
        String last = cfg.lastChange();
        undoText.setText(last == null ? "Nothing to undo" : "Last change: " + last);
        undoBtn.setEnabled(last != null);
    }

    private String describe(EsdeConfig cfg, EsdeConfig.Category c) {
        String what;
        if (EsdeConfig.KIND_APPS.equals(c.kind)) { int n = cfg.apps(c).size(); what = n + " app" + (n == 1 ? "" : "s"); }
        else if (EsdeConfig.KIND_MOONLIGHT.equals(c.kind)) { int n = cfg.moonlightGames(c).size(); what = n + " game" + (n == 1 ? "" : "s") + " on " + c.pc; }
        else { int n = cfg.collectionEntries(c).size(); what = n + " entr" + (n == 1 ? "y" : "ies"); }
        return c.fullName + "\n" + c.kindLabel() + " · " + what;
    }

    private void categoryMenu(final EsdeConfig.Category c) {
        final boolean collection = EsdeConfig.KIND_COLLECTION.equals(c.kind);
        String change = EsdeConfig.KIND_APPS.equals(c.kind) ? "Choose apps" : EsdeConfig.KIND_MOONLIGHT.equals(c.kind) ? "Edit games" : "Choose entries";
        String[] items = collection ? new String[]{change, "Delete"} : new String[]{change, "Rename or change look", "Delete"};
        a.dialog().setTitle(c.fullName).setItems(items, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                if (w == 0) editItems(c);
                else if (w == 1 && !collection) editDetails(c, c.kind);
                else confirmDelete(c);
            }
        }).setNegativeButton("Close", null).show();
    }

    // ================================================================ name + look

    static final String[][] LOOKS = {
            {"androidapps", "Android Apps look"}, {"androidgames", "Android Games look"}, {"emulators", "Emulators look"},
            {"windows", "Windows / PC look"}, {"steam", "Steam look"}, {"epic", "Epic Games look"}, {"", "Plain (just the name)"}};

    /** Step 1 for new categories, or rename/look for existing ones. */
    private void editDetails(final EsdeConfig.Category existing, final String kind) {
        final boolean moon = EsdeConfig.KIND_MOONLIGHT.equals(kind), col = EsdeConfig.KIND_COLLECTION.equals(kind);
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(a.dp(20), a.dp(4), a.dp(20), 0);
        wrap.addView(a.text(col ? "Collection name" : "Name shown in ES-DE", 13, MainActivity.MUTED, false));
        final EditText name = new EditText(a);
        name.setSingleLine(true);
        name.setHint(moon ? "Moonlight" : col ? "e.g. Media" : "e.g. Streaming");
        if (existing != null) name.setText(existing.fullName);
        else if (moon) name.setText("Moonlight");
        wrap.addView(name);
        final EditText pc = new EditText(a);
        if (moon) {
            wrap.addView(a.text("Computer name, exactly as Moonlight shows it", 13, MainActivity.MUTED, false));
            pc.setSingleLine(true);
            pc.setHint("e.g. GAMING-PC");
            if (existing != null && existing.pc != null) pc.setText(existing.pc);
            wrap.addView(pc);
        }
        final CheckBox separate = a.checkbox("Show as its own item on ES-DE's main screen");
        if (col) {
            separate.setChecked(true);
            wrap.addView(separate);
            wrap.addView(a.text("Otherwise ES-DE puts it inside its Collections item. This switches ES-DE's \"Group custom "
                    + "collections\" setting to Never, so it applies to all your custom collections.", 12, MainActivity.MUTED, false));
        }
        final int[] look = {0};
        String defTheme = existing != null && existing.theme != null ? existing.theme : moon ? "windows" : "androidapps";
        for (int i = 0; i < LOOKS.length; i++) if (LOOKS[i][0].equals(defTheme)) look[0] = i;
        final Button lookBtn = a.button("Look: " + LOOKS[look[0]][1], false);
        if (!col) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = a.dp(10);
            wrap.addView(lookBtn, lp);
            wrap.addView(a.text("Your ES-DE theme only has artwork for its own systems, so a category borrows one of their looks. "
                    + "\"Plain\" shows just the name, if your theme supports that.", 12, MainActivity.MUTED, false));
            lookBtn.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    String[] labels = new String[LOOKS.length];
                    for (int i = 0; i < LOOKS.length; i++) labels[i] = LOOKS[i][1];
                    a.dialog().setTitle("Look").setSingleChoiceItems(labels, look[0], new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { look[0] = w; lookBtn.setText("Look: " + LOOKS[w][1]); d.dismiss(); }
                    }).show();
                }
            });
        }
        String title = existing != null ? "Rename \"" + existing.fullName + "\"" : moon ? "New Moonlight category"
                : col ? "New collection" : "New Android app category";
        final AlertDialog dlg = a.dialog().setTitle(title).setView(wrap)
                .setPositiveButton(existing != null ? "Save" : "Next", null).setNegativeButton("Cancel", null).show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String full = name.getText().toString().trim();
                EsdeConfig cfg = cfg();
                EsdeConfig.Category c = existing != null ? existing : new EsdeConfig.Category();
                if (existing == null) {
                    c.kind = kind;
                    c.name = col ? EsdeConfig.collectionNameFor(full) : EsdeConfig.systemNameFor(full);
                    String problem = col ? (c.name.isEmpty() ? "Give the collection a name." : cfg.find(EsdeConfig.KIND_COLLECTION, c.name) != null
                            || cfg.collectionFile(c.name).exists() ? "You already have a collection with that name." : null)
                            : cfg.nameProblem(c.name, null);
                    if (problem != null) { a.toast(problem); return; }
                }
                if (full.isEmpty()) { a.toast("Give it a name."); return; }
                c.fullName = full;
                if (!col) c.theme = LOOKS[look[0]][0].isEmpty() ? c.name : LOOKS[look[0]][0];
                if (moon) {
                    c.pc = pc.getText().toString().trim();
                    if (c.pc.isEmpty()) { a.toast("Enter the computer's name as Moonlight shows it."); return; }
                }
                if (col) c.separate = separate.isChecked();
                dlg.dismiss();
                if (existing != null) saveSame(c);
                else editItems(c);
            }
        });
    }

    /** Re-saves a system category with its current items (after a rename or look change). */
    private void saveSame(final EsdeConfig.Category c) {
        final EsdeConfig cfg = cfg();
        save("Saving…", new Job() {
            public String run() throws IOException {
                if (EsdeConfig.KIND_APPS.equals(c.kind)) cfg.saveAppCategory(c, cfg.apps(c));
                else cfg.saveMoonlightCategory(c, cfg.moonlightGames(c));
                return "Saved \"" + c.fullName + "\".";
            }
        });
    }

    // ================================================================ items

    private void editItems(EsdeConfig.Category c) {
        if (EsdeConfig.KIND_APPS.equals(c.kind)) pickApps(c);
        else if (EsdeConfig.KIND_MOONLIGHT.equals(c.kind)) editGames(c);
        else pickEntries(c);
    }

    static class Item {
        final String key, label, sub;
        Item(String key, String label, String sub) { this.key = key; this.label = label; this.sub = sub; }
        @Override public String toString() { return sub == null ? label : label + "   ·  " + sub; }
    }

    /** Installed apps you can open, as a tick list. */
    private void pickApps(final EsdeConfig.Category c) {
        final EsdeConfig cfg = cfg();
        final Map<String, String> current = cfg.find(c.kind, c.name) != null ? cfg.apps(c) : new LinkedHashMap<String, String>();
        background("Finding your apps…", new Runnable() {
            public void run() {
                PackageManager pm = a.getPackageManager();
                Map<String, Item> byPkg = new LinkedHashMap<String, Item>();
                for (String cat : new String[]{Intent.CATEGORY_LAUNCHER, "android.intent.category.LEANBACK_LAUNCHER"}) {
                    Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat);
                    for (ResolveInfo r : pm.queryIntentActivities(i, 0)) {
                        String pkg = r.activityInfo.packageName;
                        if (byPkg.containsKey(pkg) || pkg.equals(a.getPackageName())) continue;
                        String label = String.valueOf(r.activityInfo.applicationInfo.loadLabel(pm));
                        byPkg.put(pkg, new Item(pkg, label, pkg));
                    }
                }
                final List<Item> items = new ArrayList<Item>(byPkg.values());
                Collections.sort(items, new Comparator<Item>() {
                    public int compare(Item x, Item y) { return x.label.compareToIgnoreCase(y.label); }
                });
                final Set<String> chosen = new HashSet<String>(current.values());
                a.ui.post(new Runnable() {
                    public void run() {
                        tickList("Apps in \"" + c.fullName + "\"", items, chosen, new Picked() {
                            public void picked(final List<Item> sel) {
                                final Map<String, String> apps = new LinkedHashMap<String, String>();
                                for (Item it : sel) {
                                    String nm = it.label;
                                    // keep the name used before for apps that were already in the category
                                    for (Map.Entry<String, String> e : current.entrySet()) if (e.getValue().equals(it.key)) nm = e.getKey();
                                    while (apps.containsKey(nm)) nm = nm + " ";
                                    apps.put(nm.trim().isEmpty() ? it.key : nm, it.key);
                                }
                                save("Saving " + c.fullName + "…", new Job() {
                                    public String run() throws IOException {
                                        cfg.saveAppCategory(c, apps);
                                        return "Saved \"" + c.fullName + "\" with " + apps.size() + " app" + (apps.size() == 1 ? "" : "s")
                                                + ". Tap Download art to give them their icons.";
                                    }
                                });
                            }
                        });
                    }
                });
            }
        });
    }

    /** Moonlight games: one per line, exactly as Moonlight shows them. */
    private void editGames(final EsdeConfig.Category c) {
        final EsdeConfig cfg = cfg();
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(a.dp(20), a.dp(4), a.dp(20), 0);
        wrap.addView(a.text("One game per line, spelled exactly as in Moonlight's list for " + c.pc + ".", 13, MainActivity.MUTED, false));
        final EditText games = new EditText(a);
        games.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        games.setMinLines(6);
        games.setHint("Cyberpunk 2077\nDesktop\nSteam Big Picture");
        StringBuilder b = new StringBuilder();
        if (cfg.find(c.kind, c.name) != null) for (String g : cfg.moonlightGames(c)) b.append(g).append('\n');
        games.setText(b.toString());
        wrap.addView(games);
        if (a.getPackageManager().getLaunchIntentForPackage("com.limelight") == null
                && a.getPackageManager().getLaunchIntentForPackage("com.limelight.root") == null) {
            wrap.addView(a.text("Moonlight doesn't seem to be installed. Install it and pair it with your computer first.", 13, MainActivity.WARN, false));
        }
        final AlertDialog dlg = a.dialog().setTitle("Games in \"" + c.fullName + "\"").setView(wrap)
                .setPositiveButton("Save", null).setNegativeButton("Cancel", null).show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final List<String> list = new ArrayList<String>();
                List<String> bad = new ArrayList<String>();
                for (String line : games.getText().toString().split("\\r?\\n")) {
                    String g = line.trim();
                    if (g.isEmpty() || list.contains(g)) continue;
                    list.add(g);
                    if (!EsdeConfig.moonlightNameOk(g)) bad.add(g);
                }
                Runnable save = new Runnable() {
                    public void run() {
                        dlg.dismiss();
                        save("Saving " + c.fullName + "…", new Job() {
                            public String run() throws IOException {
                                cfg.saveMoonlightCategory(c, list);
                                return "Saved \"" + c.fullName + "\" with " + list.size() + " game" + (list.size() == 1 ? "" : "s")
                                        + ". Tap Download art to give them artwork.";
                            }
                        });
                    }
                };
                if (bad.isEmpty()) { save.run(); return; }
                final Runnable fsave = save;
                a.dialog().setTitle("Some names can't be used as-is")
                        .setMessage("These contain characters that can't be in a file name (\\ / : * ? \" < > |):\n\n• "
                                + android.text.TextUtils.join("\n• ", bad)
                                + "\n\nThey'll be saved without those characters, so Moonlight won't recognise them. "
                                + "Rename them in Sunshine (or GeForce Experience) to fix this.")
                        .setPositiveButton("Save anyway", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { fsave.run(); }
                        })
                        .setNegativeButton("Back", null).show();
            }
        });
    }

    /** Collection entries: anything from your systems, with a filter. */
    private void pickEntries(final EsdeConfig.Category c) {
        final EsdeConfig cfg = cfg();
        background("Finding your entries…", new Runnable() {
            public void run() {
                Engine e = a.newEngine();
                final List<Item> items = new ArrayList<Item>();
                List<String> systems = e.systems();
                // app systems first: that's what collections here are mostly for
                Collections.sort(systems, new Comparator<String>() {
                    public int compare(String x, String y) {
                        boolean ax = e2app(x), ay = e2app(y);
                        return ax != ay ? (ax ? -1 : 1) : x.compareToIgnoreCase(y);
                    }
                    boolean e2app(String s) { return s.toLowerCase(Locale.ROOT).startsWith("android") || s.equalsIgnoreCase("emulators"); }
                });
                for (String sys : systems) {
                    Map<String, String> titles = e.titles(sys);
                    for (Engine.GameFile g : e.scanSystem(sys)) {
                        if (g.isDir) continue;
                        File f = new File(e.systemDirs.get(sys), g.rel);
                        items.add(new Item(f.getAbsolutePath(), e.searchName(g, titles), sys));
                    }
                }
                final Set<String> chosen = new HashSet<String>(cfg.collectionEntries(c));
                a.ui.post(new Runnable() {
                    public void run() {
                        tickList("Entries in \"" + c.fullName + "\"", items, chosen, new Picked() {
                            public void picked(List<Item> sel) {
                                final List<File> files = new ArrayList<File>();
                                for (Item it : sel) files.add(new File(it.key));
                                save("Saving " + c.fullName + "…", new Job() {
                                    public String run() throws IOException {
                                        cfg.saveCollection(c, files);
                                        return "Saved \"" + c.fullName + "\" with " + files.size() + " entr" + (files.size() == 1 ? "y" : "ies") + ".";
                                    }
                                });
                            }
                        });
                    }
                });
            }
        });
    }

    interface Picked { void picked(List<Item> selection); }

    /** Filterable tick list that keeps ticks while filtering. */
    private void tickList(String title, final List<Item> all, final Set<String> chosen, final Picked cb) {
        if (all.isEmpty()) { a.toast("Nothing found to choose from."); return; }
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(a.dp(20), a.dp(4), a.dp(20), 0);
        final EditText filter = new EditText(a);
        filter.setSingleLine(true);
        filter.setHint("Type to filter " + all.size());
        wrap.addView(filter);
        final TextView count = a.text("", 12, MainActivity.MUTED, false);
        wrap.addView(count);
        final List<Item> visible = new ArrayList<Item>(all);
        final ArrayAdapter<Item> adapter = new ArrayAdapter<Item>(a, android.R.layout.simple_list_item_multiple_choice, visible) {
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                TextView tv = (TextView) super.getView(pos, cv, parent);
                tv.setTextColor(MainActivity.TEXT);
                return tv;
            }
        };
        final ListView lv = new ListView(a);
        lv.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        lv.setAdapter(adapter);
        wrap.addView(lv, new LinearLayout.LayoutParams(-1, a.dp(330)));
        final Runnable sync = new Runnable() {
            public void run() {
                for (int i = 0; i < visible.size(); i++) lv.setItemChecked(i, chosen.contains(visible.get(i).key));
                count.setText(chosen.size() + " selected");
            }
        };
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> p, View v, int pos, long id) {
                String k = visible.get(pos).key;
                if (lv.isItemChecked(pos)) chosen.add(k); else chosen.remove(k);
                count.setText(chosen.size() + " selected");
            }
        });
        filter.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int x, int y, int z) { }
            public void onTextChanged(CharSequence s, int x, int y, int z) { }
            public void afterTextChanged(Editable s) {
                String q = s.toString().toLowerCase(Locale.ROOT).trim();
                visible.clear();
                for (Item it : all) if (q.isEmpty() || it.toString().toLowerCase(Locale.ROOT).contains(q)) visible.add(it);
                adapter.notifyDataSetChanged();
                sync.run();
            }
        });
        sync.run();
        a.dialog().setTitle(title).setView(wrap)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        List<Item> sel = new ArrayList<Item>();
                        for (Item it : all) if (chosen.contains(it.key)) sel.add(it);
                        cb.picked(sel);
                    }
                })
                .setNegativeButton("Cancel", null).show();
        lv.requestFocus();
    }

    // ================================================================ delete / undo

    private void confirmDelete(final EsdeConfig.Category c) {
        String what = EsdeConfig.KIND_COLLECTION.equals(c.kind)
                ? "The collection is removed from ES-DE. The games and apps in it stay where they are."
                : "Its system is removed from ES-DE, along with the entries this app made for it. Anything else in its folder is kept.";
        a.dialog().setTitle("Delete \"" + c.fullName + "\"?").setMessage(what + "\n\nYou can undo this.")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        final EsdeConfig cfg = cfg();
                        save("Deleting…", new Job() {
                            public String run() throws IOException { cfg.delete(c); return "Deleted \"" + c.fullName + "\"."; }
                        });
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void undo() {
        final EsdeConfig cfg = cfg();
        final String last = cfg.lastChange();
        if (last == null) return;
        a.dialog().setTitle("Undo?").setMessage("Puts back every file the last change touched:\n\n" + last)
                .setPositiveButton("Undo", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        save("Undoing…", new Job() {
                            public String run() throws IOException { String u = cfg.undo(); return u == null ? "Nothing to undo." : "Undone: " + u; }
                        });
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ================================================================ threading

    interface Job { String run() throws IOException; }

    /** Runs a file change off the main thread, then reports and refreshes the list. */
    private void save(String busy, final Job job) {
        final AlertDialog wait = a.dialog().setMessage(busy).setCancelable(false).show();
        new Thread(new Runnable() {
            public void run() {
                String msg;
                try { msg = job.run(); }
                catch (Exception e) { msg = "Couldn't save: " + e.getMessage(); }
                final String fm = msg;
                a.ui.post(new Runnable() {
                    public void run() {
                        wait.dismiss();
                        a.toast(fm);
                        for (EsdeConfig.Category c : cfg().categories())
                            if (!EsdeConfig.KIND_COLLECTION.equals(c.kind)) a.includeSystem(c.name);
                        a.appendLog("  ▤ " + fm);
                        if (screen != null && screen.isShowing()) refresh();
                    }
                });
            }
        }).start();
    }

    private void background(String busy, final Runnable work) {
        final AlertDialog wait = a.dialog().setMessage(busy).setCancelable(false).show();
        new Thread(new Runnable() {
            public void run() {
                try { work.run(); }
                finally { a.ui.post(new Runnable() { public void run() { wait.dismiss(); } }); }
            }
        }).start();
    }
}
