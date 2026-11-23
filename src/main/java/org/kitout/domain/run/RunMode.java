package org.kitout.domain.run;

/** Where a service's process comes from. Which modes a service offers depends on its project type. */
public enum RunMode {
    /** A registry image with the tag the developer chose, host networking. */
    IMAGE,
    /** The local JVM build: Quarkus' {@code quarkus-app/quarkus-run.jar}, Spring Boot's or a plain Java app's runnable jar. */
    JVM,
    /** The local native executable ({@code *-runner}); Quarkus only. */
    NATIVE,
    /** An npm script in the project's folder, e.g. {@code npm run dev}; Node projects. */
    SCRIPT,
    /** Runs in the developer's IDE (dev mode); kitout only watches its port. */
    IDE
}
