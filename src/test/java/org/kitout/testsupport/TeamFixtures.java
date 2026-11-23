package org.kitout.testsupport;

import org.kitout.domain.team.TeamSetup;
import org.kitout.domain.team.TeamSetupParser;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The shipped example team file, as is and with every placeholder filled with its own example value. */
public final class TeamFixtures {

    private static final Pattern PLACEHOLDER = Pattern.compile("<<REPLACE:[^>]*?e\\.g\\. ([^;>]+)[^>]*>>");

    private TeamFixtures() {
    }

    public static String exampleText() {
        try {
            return Files.readString(Path.of("docs/team/kitout-team.example.json"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Each {@code <<REPLACE: ..., e.g. X>>} becomes X. */
    public static String filledText() {
        Matcher m = PLACEHOLDER.matcher(exampleText());
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1).strip()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static Map<String, Object> filledJson() {
        return MiniJson.object(filledText());
    }

    public static TeamSetup filled() {
        TeamSetupParser.Result r = TeamSetupParser.parse(filledJson(), "1.0.0");
        if (r.setup().isEmpty()) {
            throw new IllegalStateException("The filled example is not valid: " + r.problems());
        }
        return r.setup().get();
    }
}
