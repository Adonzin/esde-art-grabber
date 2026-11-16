package com.esde.artgrabber;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Editable copies of ES-DE themes: find the current theme, make a copy, refresh or delete it. */
class ThemesScreen {
    private final MainActivity a;
    private Dialog screen;
    private LinearLayout body;

    ThemesScreen(MainActivity a) { this.a = a; }

    private ThemeCopies tc() { return new ThemeCopies(a.esdeDir); }

    void open() {
        if (!a.canUseStorage()) { a.refreshAccessCard(); a.toast("Allow file access first."); return; }
        if (a.esdeDir == null) { a.toast("The app couldn't find your ES-DE folder yet (Folders card)."); return; }
        screen = new Dialog(a, android.R.style.Theme_Material_NoActionBar_Fullscreen);
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(MainActivity.BG);
        root.setPadding(a.dp(16), a.dp(12), a.dp(16), a.dp(12));
        LinearLayout top = a.row();
        LinearLayout titles = new LinearLayout(a);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(a.text("Themes", 22, MainActivity.TEXT, true));
        titles.addView(a.text("Editable copies of your ES-DE theme, for custom category looks", 13, MainActivity.MUTED, false));
        a.addWeighted(top, titles, 1, 0);
        Button done = a.button("Done", true);
        top.addView(done);
        root.addView(top);
        ScrollView sc = new ScrollView(a);
        body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, a.dp(12), 0, 0);
        sc.addView(body);
        root.addView(sc, new LinearLayout.LayoutParams(-1, 0, 1));
        done.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { screen.dismiss(); a.updateLabels(); } });
        screen.setContentView(root);
        screen.show();
        refresh();
    }

    void refresh() {
        body.removeAllViews();
        final ThemeCopies tc = tc();
        final String cur = tc.currentTheme();

        LinearLayout now = a.card(body, "Your theme");
        TextView t = a.text(cur == null ? "Couldn't tell which theme ES-DE uses (open ES-DE once, then come back)."
                : "ES-DE is set to: " + cur, 15, MainActivity.TEXT, false);
        t.setPadding(0, a.dp(6), 0, a.dp(8));
        now.addView(t);
        if (cur != null) {
            final File copy = tc.copyDir(cur);
            boolean usingCopy = ThemeCopies.isCopy(cur);
            if (copy.isDirectory()) {
                long when = tc.downloadedAt(copy);
                now.addView(a.text("Editable copy: " + nameOf(copy) + (when > 0 ? " · downloaded " + DateFormat.getDateInstance().format(new Date(when)) : ""),
                        14, MainActivity.GOOD, false));
                if (!usingCopy) now.addView(hint("To see your custom looks, choose \"" + nameOf(copy) + "\" in ES-DE: Main menu → "
                        + "UI Settings → Theme."));
                final TextView sizeText = a.text("", 12, MainActivity.MUTED, false);
                now.addView(sizeText);
                showSize(copy, sizeText);
                LinearLayout r = a.row();
                r.setPadding(0, a.dp(10), 0, 0);
                Button refresh = a.button("Refresh copy", false), check = a.button("Check copy", false), del = a.button("Delete copy", false);
                a.addWeighted(r, refresh, 1, 0);
                a.addWeighted(r, check, 1, 8);
                a.addWeighted(r, del, 1, 8);
                now.addView(r);
                check.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { checkCopy(copy); } });
                refresh.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { download(ThemeCopies.originalOf(cur), true); } });
                del.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { confirmDelete(copy); } });
                layoutCard(copy);
            } else {
                now.addView(hint("ES-DE keeps downloaded themes where other apps can't change them, so this downloads the same theme "
                        + "again, from where ES-DE's theme downloader gets it, as a separate theme you can edit: \"<name> (ESCommander)\". "
                        + "Your current theme stays as it is."));
                Button make = a.button("Make an editable copy", true);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = a.dp(10);
                now.addView(make, lp);
                make.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { download(cur, false); } });
            }
        }

        // copies of other themes
        List<File> copies = new ArrayList<File>();
        for (File d : tc.localThemes()) if (ThemeCopies.isCopy(d.getName()) && (cur == null || !d.equals(tc.copyDir(cur)))) copies.add(d);
        if (!copies.isEmpty()) {
            LinearLayout oc = a.card(body, "Other editable copies");
            for (final File d : copies) {
                LinearLayout r = a.row();
                r.setPadding(0, a.dp(8), 0, 0);
                LinearLayout names = new LinearLayout(a);
                names.setOrientation(LinearLayout.VERTICAL);
                names.addView(a.text(nameOf(d), 14, MainActivity.TEXT, false));
                TextView sz = a.text("", 12, MainActivity.MUTED, false);
                names.addView(sz);
                showSize(d, sz);
                a.addWeighted(r, names, 1, 0);
                Button refresh = a.button("Refresh", false), del = a.button("Delete", false);
                r.addView(refresh);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.leftMargin = a.dp(8);
                r.addView(del, lp);
                oc.addView(r);
                refresh.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { download(ThemeCopies.originalOf(d.getName()), true); } });
                del.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { confirmDelete(d); } });
            }
        }

        final List<File> left = tc.leftovers();
        if (!left.isEmpty()) {
            LinearLayout lc = a.card(body, "Leftovers");
            final TextView lt = a.text(left.size() + " unfinished folder" + (left.size() == 1 ? "" : "s")
                    + " from interrupted downloads, taking up space.", 13, MainActivity.WARN, false);
            lt.setPadding(0, a.dp(6), 0, a.dp(8));
            lc.addView(lt);
            Button clean = a.button("Clean up", false);
            lc.addView(clean, new LinearLayout.LayoutParams(-1, -2));
            clean.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    long freed = 0;
                    for (File d : left) { freed += ThemeCopies.size(d); EsdeConfig.deleteTree(d); }
                    a.toast(String.format(java.util.Locale.ROOT, "Freed %.0f MB.", freed / 1048576.0));
                    refresh();
                }
            });
        }

        LinearLayout other = a.card(body, "Another theme");
        other.addView(hint("Make an editable copy of any theme from ES-DE's theme downloader, for example to try your categories' looks in it."));
        Button pick = a.button("Choose a theme…", false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = a.dp(10);
        other.addView(pick, lp);
        pick.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { chooseOther(); } });
    }

    /** What the copy can show for a category, so you know what pictures matter. */
    private void layoutCard(final File copy) {
        final LinearLayout c = a.card(body, "What this theme shows for a category");
        final TextView t = a.text("Reading the theme…", 13, MainActivity.MUTED, false);
        t.setPadding(0, a.dp(6), 0, 0);
        c.addView(t);
        new Thread(new Runnable() {
            public void run() {
                String msg;
                try {
                    ThemeLayout l = ThemeLayout.read(copy, null);
                    StringBuilder b = new StringBuilder();
                    java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<String>();
                    for (ThemeLayout.Slot s : l.slots) {
                        String line = "• " + s.label + (s.refW > 0 && !".svg".equals(s.ext) ? " – " + s.refW + "×" + s.refH : "")
                                + (s.custom ? " (customisation)" : "");
                        if (seen.add(line)) b.append(line).append('\n');
                    }
                    msg = l.slots.isEmpty() ? "This theme doesn't keep per-system artwork the app can recognise, so custom looks won't show in it."
                            : b.toString().trim();
                } catch (Exception e) {
                    msg = "Couldn't read the theme: " + e.getMessage();
                }
                final String fm = msg;
                a.ui.post(new Runnable() { public void run() { t.setText(fm); t.setTextColor(MainActivity.TEXT); } });
            }
        }).start();
    }

    private void showSize(final File d, final TextView t) {
        new Thread(new Runnable() {
            public void run() {
                final long n = ThemeCopies.size(d);
                a.ui.post(new Runnable() { public void run() { t.setText(String.format(java.util.Locale.ROOT, "%.0f MB", n / 1048576.0)); } });
            }
        }).start();
    }

    /** Which of your systems have no carousel image in this copy. */
    private void checkCopy(final File copy) {
        final AlertDialog wait = a.dialog().setMessage("Checking " + nameOf(copy) + "…").setCancelable(false).show();
        new Thread(new Runnable() {
            public void run() {
                String msg;
                try {
                    Engine e = a.newEngine();
                    List<String> systems = new ArrayList<String>();
                    for (String sys : e.systems()) {
                        String[] kids = e.systemDirs.get(sys).list();
                        if (kids != null && kids.length > 0) systems.add(sys);   // ES-DE hides empty systems anyway
                    }
                    for (EsdeConfig.Category c : new EsdeConfig(a.esdeDir, a.romDir).categories())
                        if (!EsdeConfig.KIND_COLLECTION.equals(c.kind)) { systems.remove(c.name); systems.remove(c.theme); }
                    List<String> missing = ThemeCopies.missingArt(copy, systems);
                    if (missing.isEmpty()) msg = "All " + systems.size() + " of your systems have a carousel image in this copy.";
                    else msg = missing.size() + " of your " + systems.size() + " systems have no carousel image in this copy:\n\n• "
                            + android.text.TextUtils.join("\n• ", missing)
                            + "\n\nIf your original theme shows images for these, the copy is incomplete: tap Refresh copy. "
                            + "If it doesn't either, the theme simply has no art for them (and your categories can get theirs "
                            + "under Custom look).";
                } catch (Exception ex) {
                    msg = "Couldn't check: " + ex.getMessage();
                }
                final String fm = msg;
                a.ui.post(new Runnable() {
                    public void run() { wait.dismiss(); a.dialog().setTitle("Check copy").setMessage(fm).setPositiveButton("OK", null).show(); }
                });
            }
        }).start();
    }

    private TextView hint(String s) {
        TextView t = a.text(s, 13, MainActivity.MUTED, false);
        t.setPadding(0, a.dp(4), 0, 0);
        return t;
    }

    static String nameOf(File theme) {
        String caps = EsdeConfig.read(new File(theme, "capabilities.xml"));
        if (caps != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("<themeName>([^<]*)</themeName>").matcher(caps);
            if (m.find()) return EsdeConfig.unx(m.group(1).trim());
        }
        return theme.getName();
    }

    private void chooseOther() {
        final AlertDialog wait = a.dialog().setMessage("Getting ES-DE's theme list…").setCancelable(false).show();
        new Thread(new Runnable() {
            public void run() {
                List<ThemeCopies.Entry> list = null;
                String err = null;
                try { list = tc().officialList(); } catch (Exception e) { err = e.getMessage(); }
                final List<ThemeCopies.Entry> fl = list;
                final String fe = err;
                a.ui.post(new Runnable() {
                    public void run() {
                        wait.dismiss();
                        if (fl == null) { a.toast("Couldn't get the theme list: " + fe); return; }
                        final List<ThemeCopies.Entry> shown = new ArrayList<ThemeCopies.Entry>();
                        for (ThemeCopies.Entry e : fl) if (!e.deprecated) shown.add(e);
                        String[] labels = new String[shown.size()];
                        for (int i = 0; i < labels.length; i++) labels[i] = shown.get(i).toString();
                        a.dialog().setTitle("Copy which theme?").setItems(labels, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { download(shown.get(w).reponame, tc().copyDir(shown.get(w).reponame).isDirectory()); }
                        }).setNegativeButton("Cancel", null).show();
                    }
                });
            }
        }).start();
    }

    /** Downloads (or refreshes) the copy of a theme, then puts every custom look back into it. */
    private void download(final String reponame, final boolean refresh) {
        LinearLayout wrap = new LinearLayout(a);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(a.dp(20), a.dp(10), a.dp(20), 0);
        final ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setIndeterminate(true);
        final TextView label = a.text("Finding the theme…", 13, MainActivity.MUTED, false);
        wrap.addView(bar, new LinearLayout.LayoutParams(-1, a.dp(12)));
        wrap.addView(label);
        wrap.addView(a.text("Themes can be large (some are a few hundred MB), so this can take a while.", 12, MainActivity.MUTED, false));
        final AlertDialog dlg = a.dialog().setTitle(refresh ? "Refreshing the copy" : "Making an editable copy").setView(wrap)
                .setCancelable(false).show();
        a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new Thread(new Runnable() {
            public void run() {
                String msg;
                try {
                    ThemeCopies tc = tc();
                    ThemeCopies.Entry e = ThemeCopies.find(tc.officialList(), reponame);
                    if (e == null) {
                        File existing = tc.copyDir(reponame);
                        e = existing.isDirectory() ? tc.sourceOf(existing) : null;
                    }
                    if (e == null) throw new java.io.IOException("\"" + reponame + "\" isn't in ES-DE's theme list, so the app doesn't "
                            + "know where to download it from.");
                    File copy = tc.makeCopy(e, a.getCacheDir(), new ThemeCopies.Progress() {
                        public void update(final String what, final long done, final long total) {
                            a.ui.post(new Runnable() {
                                public void run() {
                                    if (total > 0) { bar.setIndeterminate(false); bar.setProgress((int) (done * 1000 / total)); }
                                    label.setText(what.equals("Downloading")
                                            ? String.format(java.util.Locale.ROOT, "Downloading… %.1f MB", done / 1048576.0)
                                            : "Unpacking… " + done + " files");
                                }
                            });
                        }
                    });
                    List<String> re = new CategoryLooks(a.esdeDir).reapplyAll(copy, new LookRenderer(a.getPackageManager()));
                    msg = (refresh ? "Refreshed " : "Made ") + nameOf(copy) + "."
                            + (re.isEmpty() ? "" : " Custom looks put back: " + android.text.TextUtils.join(", ", re) + ".")
                            + (refresh ? "" : " Choose it in ES-DE under UI Settings → Theme.");
                } catch (Exception ex) {
                    msg = "Couldn't make the copy: " + ex.getMessage();
                }
                final String fm = msg;
                a.ui.post(new Runnable() {
                    public void run() {
                        a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        dlg.dismiss();
                        a.dialog().setMessage(fm).setPositiveButton("OK", null).show();
                        a.appendLog("  ◈ " + fm);
                        refresh();
                    }
                });
            }
        }).start();
    }

    private void confirmDelete(final File copy) {
        a.dialog().setTitle("Delete " + nameOf(copy) + "?")
                .setMessage("The copy is removed from ES-DE/themes. Your original theme isn't affected, and your categories' "
                        + "pictures are kept, so you can make the copy again later. If ES-DE is set to use this copy, switch "
                        + "ES-DE back to your original theme first.")
                .setPositiveButton("Delete", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        CategoryLooks looks = new CategoryLooks(a.esdeDir);
                        for (CategoryLooks.Look l : looks.all()) {
                            if (!l.applied.containsKey(copy.getName())) continue;
                            try { looks.remove(l, copy); } catch (Exception ignored) { }
                            // remember that it was applied there, so a new copy gets it back
                            l.applied.put(copy.getName(), new ArrayList<String>());
                            try { looks.save(l); } catch (Exception ignored) { }
                        }
                        EsdeConfig.deleteTree(copy);
                        a.toast("Deleted " + copy.getName() + ".");
                        refresh();
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }
}
