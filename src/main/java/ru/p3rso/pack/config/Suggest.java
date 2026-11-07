package ru.p3rso.pack.config;

import java.util.Collection;
import java.util.Locale;

/** Подсказки «может быть, вы имели в виду …» по расстоянию Левенштейна. */
public final class Suggest {
    private Suggest() {}

    public static String closest(String input, Collection<String> candidates) {
        if (input == null || candidates.isEmpty()) return null;
        String in = input.toLowerCase(Locale.ROOT);
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        for (String c : candidates) {
            int d = distance(in, c.toLowerCase(Locale.ROOT));
            if (d < bestDist) { bestDist = d; best = c; }
        }
        int limit = Math.max(2, in.length() / 3);
        return bestDist <= limit ? best : null;
    }

    /** « (может быть, 'x'?)» или пустая строка. */
    public static String hint(String input, Collection<String> candidates) {
        String c = closest(input, candidates);
        return c == null ? "" : " (может быть, '" + c + "'?)";
    }

    static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev; prev = cur; cur = t;
        }
        return prev[b.length()];
    }
}
