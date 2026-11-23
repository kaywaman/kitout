package org.kitout.domain.docker;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One compose file, one environment: {@code docker-compose-dev.yml} is the environment {@code dev}.
 *
 * <p>Also accepted: {@code compose-dev.yaml}, {@code docker-compose.dev.yml}, and a plain
 * {@code docker-compose.yml} (environment {@code default}). Override files
 * ({@code docker-compose.override.yml}) are overlays, not environments, and are left out.
 */
public record ComposeFile(String environment, Path file) {

    private static final Pattern NAME = Pattern.compile("^(?:docker-)?compose(?:[-.]([A-Za-z0-9][A-Za-z0-9_.-]*?))?\\.ya?ml$");

    /** The environment a file name stands for, or empty if it is not a compose environment file. */
    public static Optional<String> environmentOf(String fileName) {
        Matcher m = NAME.matcher(fileName);
        if (!m.matches()) {
            return Optional.empty();
        }
        String env = m.group(1) == null ? "default" : m.group(1);
        if (env.toLowerCase(Locale.ROOT).endsWith("override")) {
            return Optional.empty();
        }
        return Optional.of(env);
    }

    private static final Pattern SERVICES = Pattern.compile("(?m)^services\\s*:");

    /**
     * The environment name for a file the developer picked by hand: the usual name if it follows
     * the convention ({@code docker-compose-dev.yml} is {@code dev}), otherwise the file name
     * without {@code .yml}/{@code .yaml} ({@code my-kafka-debug.yml} is {@code my-kafka-debug}).
     */
    public static String nameForPicked(String fileName) {
        return environmentOf(fileName).orElse(fileName.replaceFirst("(?i)\\.ya?ml$", ""));
    }

    public static boolean isYaml(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".yml") || lower.endsWith(".yaml");
    }

    /**
     * True if the YAML text is a compose file: it has a top-level {@code services:} key. Keeps
     * {@code application.yaml} and CI files out of the picker without a YAML parser.
     */
    public static boolean looksLikeCompose(String content) {
        return SERVICES.matcher(content).find();
    }

    /** A relative path as a list item or a value: {@code - ./init:/docker-entrypoint-initdb.d}, {@code context: ../x}. */
    private static final Pattern RELATIVE = Pattern.compile("(?m)^[ \\t]*(?:-[ \\t]*)?(?:[A-Za-z_][\\w.-]*[ \\t]*:[ \\t]*)?[\"']?(\\.{1,2}/[^\\s\"':]*|\\.{1,2})[\"']?(?=[\\s:]|$)");

    /** Keys whose value always names a file or folder, relative to the compose file unless absolute. */
    private static final Pattern FILE_KEYS = Pattern.compile("(?m)^[ \\t]*(?:-[ \\t]*)?(env_file|build|context|dockerfile|file)[ \\t]*:[ \\t]*[\"']?([^\\s\"'#/~$][^\\s\"'#]*)");

    /**
     * The files and folders the compose text names relative to itself ({@code ./init}, {@code ../conf},
     * {@code env_file: mongo.env}, {@code build: .}). A compose file downloaded alone works only without them.
     * Errs on the side of finding too many: a false hit asks for a clone, a miss would mount an empty folder.
     */
    public static java.util.List<String> localReferences(String content) {
        java.util.Set<String> found = new java.util.LinkedHashSet<>();
        Matcher m = RELATIVE.matcher(content);
        while (m.find()) {
            found.add(m.group(1));
        }
        Matcher k = FILE_KEYS.matcher(content);
        while (k.find()) {
            String value = k.group(2);
            // build: with a nested block ("build:" alone) has no value here; image names never come after these keys
            if (!value.isEmpty() && !value.contains("://")) {
                found.add(value);
            }
        }
        return java.util.List.copyOf(found);
    }

    public Path folder() {
        return file.getParent();
    }
}
