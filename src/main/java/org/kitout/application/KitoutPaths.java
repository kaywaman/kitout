package org.kitout.application;

import jakarta.enterprise.context.ApplicationScoped;

import java.nio.file.Path;

/**
 * Where kitout keeps its files, following the XDG base directory convention:
 * settings in {@code ~/.config/kitout}, run state and logs in {@code ~/.local/state/kitout}.
 * Override the root for tests with the {@code KITOUT_HOME} environment variable.
 */
@ApplicationScoped
public class KitoutPaths {

    private final Path configDir;
    private final Path stateDir;
    private final Path home;

    public KitoutPaths() {
        this(System.getenv());
    }

    public KitoutPaths(java.util.Map<String, String> env) {
        this.home = Path.of(System.getProperty("user.home"));
        String kitoutHome = env.get("KITOUT_HOME");
        if (kitoutHome != null && !kitoutHome.isBlank()) {
            Path root = Path.of(kitoutHome);
            configDir = root.resolve("config");
            stateDir = root.resolve("state");
        } else {
            configDir = xdg(env, "XDG_CONFIG_HOME", home.resolve(".config")).resolve("kitout");
            stateDir = xdg(env, "XDG_STATE_HOME", home.resolve(".local/state")).resolve("kitout");
        }
    }

    private static Path xdg(java.util.Map<String, String> env, String name, Path fallback) {
        String value = env.get(name);
        return value == null || value.isBlank() ? fallback : Path.of(value);
    }

    public Path configFile() {
        return configDir.resolve("config.json");
    }

    public Path stateFile() {
        return stateDir.resolve("state.json");
    }

    public Path serviceLogDir() {
        return stateDir.resolve("logs");
    }

    public Path serviceLog(String serviceId) {
        return serviceLogDir().resolve(serviceId + ".log");
    }

    public Path buildLog(String buildId) {
        return stateDir.resolve("builds").resolve(buildId + ".log");
    }

    /** kitout's own log (the daemon's and the CLI's warnings), next to the service logs. */
    public Path kitoutLog() {
        return stateDir.resolve("kitout.log");
    }

    public Path configDir() {
        return configDir;
    }

    public Path nativeBuildLock() {
        return stateDir.resolve("native-build.lock");
    }

    public Path askpassScript() {
        return stateDir.resolve("askpass.sh");
    }

    public Path fallbackTokenDir() {
        return stateDir.resolve("secrets");
    }

    /** The developer's home folder; the folder browser never leaves it. */
    public Path home() {
        return home;
    }

    public Path stateDir() {
        return stateDir;
    }

    /** Expands a leading {@code ~} the way a developer expects in a settings field. */
    public Path expand(String path) {
        if (path.equals("~")) {
            return home();
        }
        if (path.startsWith("~/")) {
            return home().resolve(path.substring(2));
        }
        return Path.of(path);
    }
}
