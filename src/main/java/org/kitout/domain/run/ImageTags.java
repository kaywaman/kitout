package org.kitout.domain.run;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The tags the tag picker offers. {@code latest} is left out on purpose: it changes under the
 * developer's feet and rarely matches the task at hand; a developer picks a concrete tag instead.
 */
public final class ImageTags {

    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private ImageTags() {
    }

    /** Without {@code latest}, newest-looking first: natural order, descending (1.10.0 before 1.9.0, mr-120 before mr-99). */
    public static List<String> forPicker(List<String> tags) {
        List<String> result = new ArrayList<>(tags.stream().filter(t -> !t.equalsIgnoreCase("latest")).distinct().toList());
        result.sort(NATURAL.reversed());
        return result;
    }

    /** Compares digit runs as numbers and everything else case-insensitively. */
    static final Comparator<String> NATURAL = (a, b) -> {
        Matcher ma = NUMBER.matcher(a);
        Matcher mb = NUMBER.matcher(b);
        int ia = 0;
        int ib = 0;
        while (ia < a.length() && ib < b.length()) {
            boolean da = ma.find(ia) && ma.start() == ia;
            boolean db = mb.find(ib) && mb.start() == ib;
            if (da && db) {
                String na = ma.group().replaceFirst("^0+(?=.)", "");
                String nb = mb.group().replaceFirst("^0+(?=.)", "");
                int c = na.length() != nb.length() ? Integer.compare(na.length(), nb.length()) : na.compareTo(nb);
                if (c != 0) {
                    return c;
                }
                ia = ma.end();
                ib = mb.end();
            } else {
                int c = Character.compare(Character.toLowerCase(a.charAt(ia)), Character.toLowerCase(b.charAt(ib)));
                if (c != 0) {
                    return c;
                }
                ia++;
                ib++;
            }
        }
        int c = Integer.compare(a.length() - ia, b.length() - ib);
        return c != 0 ? c : a.toLowerCase(Locale.ROOT).compareTo(b.toLowerCase(Locale.ROOT));
    };
}
