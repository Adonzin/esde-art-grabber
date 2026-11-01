package com.esde.artgrabber;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** ROM-name cleanup and fuzzy title matching. */
public final class Names {
    private Names() {}

    private static final Pattern TAGS = Pattern.compile("\\s*[\\(\\[\\{][^\\)\\]\\}]*[\\)\\]\\}]");
    private static final Pattern VERSION = Pattern.compile("(?i)\\s+v\\d+(\\.\\d+)*[a-z]?$");
    private static final Pattern DISC = Pattern.compile("(?i)\\s*-?\\s*\\b(disc|disk|cd)\\s*\\d+\\b.*$");
    private static final Pattern ARTICLE = Pattern.compile("(?i)^(.*?),\\s*(The|A|An)(\\s*[-:].*)?$");

    /** "Legend of Zelda, The - A Link to the Past (USA) [!]" -> "The Legend of Zelda - A Link to the Past" */
    public static String clean(String raw) {
        String n = TAGS.matcher(raw).replaceAll("");
        n = n.replace('_', ' ');
        if (count(n, '.') >= 2 && n.indexOf(' ') < 0) n = n.replace('.', ' ');
        n = DISC.matcher(n).replaceAll("");
        n = VERSION.matcher(n).replaceAll("");
        Matcher m = ARTICLE.matcher(n.trim());
        if (m.matches()) {
            n = m.group(2) + " " + m.group(1) + (m.group(3) == null ? "" : m.group(3));
        }
        n = n.replaceAll("\\s+", " ").trim();
        while (n.endsWith("-")) n = n.substring(0, n.length() - 1).trim();
        while (n.startsWith("-")) n = n.substring(1).trim();
        return n;
    }

    public static String norm(String s) {
        s = s.toLowerCase(Locale.ROOT).replace("&", "and");
        s = s.replaceAll("[^a-z0-9 ]+", " ");
        return s.replaceAll("\\s+", " ").trim();
    }

    /** 0..1 similarity, like Python's difflib ratio, with a boost for prefix matches. */
    public static double similarity(String a, String b) {
        String na = norm(a), nb = norm(b);
        if (na.isEmpty() || nb.isEmpty()) return 0;
        if (na.equals(nb)) return 1;
        double r = 2.0 * matches(na, 0, na.length(), nb, 0, nb.length()) / (na.length() + nb.length());
        if (nb.startsWith(na) || na.startsWith(nb)) r = Math.max(r, 0.85);
        return r;
    }

    // Ratcliff/Obershelp: longest common block, then recurse on both sides.
    private static int matches(String a, int alo, int ahi, String b, int blo, int bhi) {
        if (alo >= ahi || blo >= bhi) return 0;
        int bestI = alo, bestJ = blo, bestK = 0;
        int[] prev = new int[bhi - blo + 1];
        for (int i = alo; i < ahi; i++) {
            int[] cur = new int[bhi - blo + 1];
            for (int j = blo; j < bhi; j++) {
                if (a.charAt(i) == b.charAt(j)) {
                    int k = prev[j - blo] + 1;
                    cur[j - blo + 1] = k;
                    if (k > bestK) { bestK = k; bestI = i - k + 1; bestJ = j - k + 1; }
                }
            }
            prev = cur;
        }
        if (bestK == 0) return 0;
        return bestK + matches(a, alo, bestI, b, blo, bestJ)
                + matches(a, bestI + bestK, ahi, b, bestJ + bestK, bhi);
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }
}
