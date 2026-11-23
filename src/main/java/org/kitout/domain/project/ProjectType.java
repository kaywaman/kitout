package org.kitout.domain.project;

import org.kitout.domain.run.RunMode;

import java.util.List;

/**
 * What kind of project a service is. Decides how kitout recognises it, reads its port, profile and
 * health check, builds it, finds its build output and starts it. Everything else (groups, images,
 * environment, logs, health switch, team file) is the same for every type.
 */
public enum ProjectType {

    /** Quarkus: {@code %dev.} config keys, {@code /q/health/ready}, {@code quarkus-app/quarkus-run.jar}, native builds. */
    QUARKUS("Quarkus", true, true, List.of(RunMode.IMAGE, RunMode.JVM, RunMode.NATIVE, RunMode.IDE)),

    /** Spring Boot: {@code application-{profile}} files, {@code /actuator/health/readiness}, the fat jar. JVM only for now. */
    SPRING_BOOT("Spring Boot", true, false, List.of(RunMode.IMAGE, RunMode.JVM, RunMode.IDE)),

    /** A plain Java application with a main class: a runnable jar; port and health check only if the developer sets a port. */
    JAVA("Java", true, false, List.of(RunMode.IMAGE, RunMode.JVM, RunMode.IDE)),

    /** A Node frontend (or app) started with an npm script, e.g. {@code npm run dev}. Not built by kitout. */
    NODE("Node", false, false, List.of(RunMode.IMAGE, RunMode.SCRIPT, RunMode.IDE)),

    /** A service the team file names only by its image: no checkout, run from the registry only. */
    IMAGE("Image", false, false, List.of(RunMode.IMAGE));

    private final String label;
    private final boolean buildable;
    private final boolean nativeBuild;
    private final List<RunMode> runModes;

    ProjectType(String label, boolean buildable, boolean nativeBuild, List<RunMode> runModes) {
        this.label = label;
        this.buildable = buildable;
        this.nativeBuild = nativeBuild;
        this.runModes = runModes;
    }

    /** For people: "Spring Boot". */
    public String label() {
        return label;
    }

    /** kitout builds it (Builds page, {@code kitout build}). */
    public boolean buildable() {
        return buildable;
    }

    /** It can be built as a native executable. Quarkus only, for now. */
    public boolean nativeBuild() {
        return nativeBuild;
    }

    /** Where it can run from, in the order the UI offers them. */
    public List<RunMode> runModes() {
        return runModes;
    }

    /** The mode a new service of this type starts in: from the registry when CI publishes images, else locally. */
    public RunMode defaultMode(RunMode teamDefault) {
        if (runModes.contains(teamDefault)) {
            return teamDefault;
        }
        if (teamDefault != RunMode.IMAGE) {   // a local mode was meant: this type's own (JVM, or an npm script)
            for (RunMode local : List.of(RunMode.JVM, RunMode.SCRIPT)) {
                if (runModes.contains(local)) {
                    return local;
                }
            }
        }
        return runModes.get(0);
    }

    /** The {@code type} value in the API and the team file: {@code quarkus}, {@code spring-boot}, ... */
    public String id() {
        return name().toLowerCase().replace('_', '-');
    }

    public static ProjectType ofId(String id) {
        for (ProjectType t : values()) {
            if (t.id().equalsIgnoreCase(id == null ? "" : id.strip())) {
                return t;
            }
        }
        throw new IllegalArgumentException("Unknown project type " + id + ": quarkus, spring-boot, java, node or image");
    }
}
