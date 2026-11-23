package org.kitout.domain.project;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A project's own version, as its build file declares it: Maven's {@code <project><version>} (or the
 * parent's, which a module inherits), with {@code ${revision}}-style properties resolved; Gradle's
 * {@code version = "..."} or {@code version=} in {@code gradle.properties}. Empty when it cannot tell.
 */
public final class ProjectVersion {

    private static final Pattern BLOCKS = Pattern.compile(
            "(?s)<(dependencies|dependencyManagement|build|profiles|modules|reporting|pluginRepositories|repositories|distributionManagement)>.*?</\\1>");
    private static final Pattern PARENT = Pattern.compile("(?s)<parent>(.*?)</parent>");
    private static final Pattern PROPERTIES = Pattern.compile("(?s)<properties>(.*?)</properties>");
    private static final Pattern VERSION = Pattern.compile("<version>\\s*([^<\\s]+)\\s*</version>");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern GRADLE = Pattern.compile("(?m)^\\s*version\\s*(?:=)?\\s*[\"']([^\"']+)[\"']");
    private static final Pattern GRADLE_PROPERTY = Pattern.compile("(?m)^\\s*version\\s*[=:]\\s*(\\S+)\\s*$");

    private ProjectVersion() {
    }

    public static Optional<String> fromPom(String pom) {
        String text = pom.replaceAll("(?s)<!--.*?-->", "");
        Matcher parent = PARENT.matcher(text);
        String parentVersion = parent.find() ? first(VERSION, parent.group(1)) : "";
        Map<String, String> properties = new java.util.HashMap<>();
        Matcher props = PROPERTIES.matcher(text);
        if (props.find()) {
            Matcher p = Pattern.compile("<([A-Za-z0-9_.-]+)>\\s*([^<]*?)\\s*</\\1>").matcher(props.group(1));
            while (p.find()) {
                properties.put(p.group(1), p.group(2));
            }
        }
        String own = first(VERSION, BLOCKS.matcher(PARENT.matcher(PROPERTIES.matcher(text).replaceAll("")).replaceAll("")).replaceAll(""));
        String version = own.isEmpty() ? parentVersion : own;
        properties.putIfAbsent("project.parent.version", parentVersion);
        return resolve(version, properties);
    }

    public static Optional<String> fromGradle(String buildFile, String gradleProperties) {
        String v = first(GRADLE, buildFile);
        if (v.isEmpty()) {
            v = first(GRADLE_PROPERTY, gradleProperties == null ? "" : gradleProperties);
        }
        return v.isEmpty() || v.equals("unspecified") ? Optional.empty() : Optional.of(v);
    }

    private static Optional<String> resolve(String version, Map<String, String> properties) {
        String v = version;
        for (int i = 0; i < 5 && v.contains("${"); i++) {
            Matcher m = PLACEHOLDER.matcher(v);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(properties.getOrDefault(m.group(1), m.group(0))));
            }
            m.appendTail(sb);
            if (sb.toString().equals(v)) {
                break;
            }
            v = sb.toString();
        }
        return v.isEmpty() || v.contains("${") ? Optional.empty() : Optional.of(v);
    }

    private static String first(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : "";
    }
}
