package org.kitout.domain.build;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Maps common native build failures to a plain explanation and a fix.
 *
 * <p>To teach kitout a new error, add one {@link Rule} to {@link #RULES}. Order matters: the
 * first matching rule wins, so put specific patterns before general ones.
 */
public final class NativeBuildErrorExplainer {

    public record Explanation(String cause, String fix) {
    }

    record Rule(Pattern pattern, Explanation explanation) {
        Rule(String regex, String cause, String fix) {
            this(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), new Explanation(cause, fix));
        }
    }

    static final List<Rule> RULES = List.of(
            new Rule("exit code:? 137|OutOfMemoryError|\\bKilled\\b|GC overhead limit",
                    "The native build ran out of memory.",
                    "Close other applications or stop running services, then build again. "
                            + "Native builds need about 6 GB of free memory."),
            new Rule("No space left on device",
                    "The disk is full.",
                    "Free disk space, for example with 'docker system prune', then build again."),
            new Rule("Cannot connect to the Docker daemon|permission denied while trying to connect to the Docker",
                    "The container build cannot reach Docker.",
                    "Start Docker and make sure your user is in the 'docker' group (run 'kitout doctor')."),
            new Rule("Cannot find the `native-image`|native-image.*(not found|could not be found)",
                    "No GraalVM or Mandrel is installed for a local native build.",
                    "Enable 'native container build' in kitout settings, or install GraalVM/Mandrel."),
            new Rule("Could not resolve dependencies|Could not transfer artifact",
                    "Maven could not download dependencies.",
                    "Check your network, proxy and Maven settings, then build again."),
            new Rule("Unsupported features in \\d+ methods|ClassNotFoundException|was not registered for reflection",
                    "Code uses reflection or a library that native images cannot see at build time.",
                    "Register the classes with @RegisterForReflection or use the Quarkus extension for the library."));

    private NativeBuildErrorExplainer() {
    }

    public static Optional<Explanation> explain(String buildOutput) {
        if (buildOutput == null) {
            return Optional.empty();
        }
        return RULES.stream()
                .filter(r -> r.pattern().matcher(buildOutput).find())
                .map(Rule::explanation)
                .findFirst();
    }
}
