package org.kitout.application.env;

import org.kitout.application.config.ConfigRepository;
import org.kitout.application.config.KitoutConfig;
import org.kitout.application.config.KitoutConfig.EnvUse;
import org.kitout.application.config.KitoutConfig.EnvVar;
import org.kitout.application.port.SecretStore;
import org.kitout.application.team.TeamDefaults;
import org.kitout.application.team.TeamOverlay;
import org.kitout.application.workspace.WorkspaceService;
import org.kitout.domain.run.EnvNames;
import org.kitout.domain.team.TeamSetup;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Environment variables for the services kitout starts: local database settings, feature switches,
 * credentials of local test systems. Two levels: <b>shared</b> (every service) and <b>per service</b>
 * (wins over shared of the same name). Applied in every mode kitout starts: a registry image gets them
 * with {@code docker run -e NAME} (the value comes from the environment of the docker command, never
 * from its arguments), a local JVM or native build gets them in its process environment.
 *
 * <p>A team file adds a layer underneath: its variables apply unless the developer sets the same
 * name, and its secrets (names only) are asked from every developer once.
 *
 * <p>Each variable says when it applies ({@link EnvUse}): when the service runs (the default), when
 * kitout builds it (the process environment of {@code ./mvnw} or {@code ./gradlew}, e.g. a token for
 * a private Maven repository or {@code MAVEN_OPTS}), or both. The same two levels and the same keyring
 * hold both; the team file's variables are for running only.
 *
 * <p>Secret values (passwords, tokens) are kept in the keyring, not in {@code config.json}, and are
 * never sent back by the API: the UI only learns that a value is set.
 */
@ApplicationScoped
public class EnvironmentService {

    /** The scope name of the shared variables. */
    public static final String SHARED = "*";

    /** A variable as the UI sees it: {@code value} is null for secrets; {@code set} says whether one is stored. */
    public record Entry(String name, String value, boolean secret, boolean set, EnvUse use) {

        public Entry(String name, String value, boolean secret, boolean set) {
            this(name, value, secret, set, EnvUse.RUN);
        }
    }

    /** A change from the UI: a secret with a null value keeps the stored value. A null {@code use} means {@link EnvUse#RUN}. */
    public record Change(String name, String value, boolean secret, EnvUse use) {

        public Change(String name, String value, boolean secret) {
            this(name, value, secret, EnvUse.RUN);
        }
    }

    private final ConfigRepository config;
    private final SecretStore secrets;
    private final WorkspaceService workspace;
    private final TeamDefaults team;

    public EnvironmentService(ConfigRepository config, SecretStore secrets, WorkspaceService workspace, TeamDefaults team) {
        this.config = config;
        this.secrets = secrets;
        this.workspace = workspace;
        this.team = team;
    }

    /** A variable the team file sets, shown read-only so the developer knows where a value comes from. */
    public record TeamValue(String name, String value, boolean secret, boolean set) {
    }

    /** The team's variables and secrets for a scope (shared, or one service). Secret values are never returned. */
    public List<TeamValue> teamValues(String scope) {
        return team.current().map(t -> {
            List<TeamValue> result = new ArrayList<>();
            Map<String, String> vars = scope.equals(SHARED) ? t.variables()
                    : t.services().stream().filter(s -> TeamOverlay.serviceId(s.repo()).equals(scope)).findFirst()
                            .map(TeamSetup.Service::variables).orElse(Map.of());
            List<String> secretNames = scope.equals(SHARED) ? t.secrets()
                    : t.services().stream().filter(s -> TeamOverlay.serviceId(s.repo()).equals(scope)).findFirst()
                            .map(TeamSetup.Service::secrets).orElse(List.of());
            vars.forEach((n, v) -> result.add(new TeamValue(n, v, false, true)));
            secretNames.forEach(n -> result.add(new TeamValue(n, null, true, secrets.lookup(key(scope, n)).isPresent())));
            return result;
        }).orElse(List.of());
    }

    /** Secrets the team file asks for that this developer has not entered yet, as "NAME" or "NAME (service)". */
    public List<String> missingTeamSecrets() {
        return team.current().map(t -> {
            List<String> missing = new ArrayList<>();
            t.secrets().stream().filter(n -> secrets.lookup(key(SHARED, n)).isEmpty()).forEach(missing::add);
            for (TeamSetup.Service s : t.services()) {
                String id = TeamOverlay.serviceId(s.repo());
                s.secrets().stream().filter(n -> secrets.lookup(key(id, n)).isEmpty()).forEach(n -> missing.add(n + " (" + id + ")"));
            }
            return missing;
        }).orElse(List.of());
    }

    /**
     * @param added    plain variables taken over
     * @param secrets  values that went to the keyring (by name: *PASSWORD*, *SECRET*, *TOKEN*, ... or asked for by the team)
     * @param skipped  names left out, with the reason
     */
    public record ImportResult(List<String> added, List<String> secrets, List<String> skipped) {
    }

    /**
     * Takes over the variables of a {@code .env} file (the one used with docker compose, say). Values
     * the team file already sets the same way are skipped; secrets the team asks for are stored as
     * this developer's value.
     */
    public ImportResult importDotEnv(String scope, String text) {
        requireScope(scope);
        Map<String, String> values = org.kitout.domain.run.DotEnv.parse(text);
        List<TeamValue> team = teamValues(scope);
        List<String> added = new ArrayList<>();
        List<String> secretNames = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<Change> changes = new ArrayList<>();
        Map<String, Entry> current = new LinkedHashMap<>();
        list(scope).forEach(e -> current.put(e.name(), e));
        for (Map.Entry<String, String> v : values.entrySet()) {
            String name = v.getKey();
            try {
                EnvNames.validate(name);
            } catch (IllegalArgumentException e) {
                skipped.add(name + ": " + e.getMessage());
                continue;
            }
            Optional<TeamValue> fromTeam = team.stream().filter(t -> t.name().equals(name)).findFirst();
            if (fromTeam.isPresent() && fromTeam.get().secret()) {
                if (v.getValue().isEmpty()) {
                    skipped.add(name + ": empty");
                } else {
                    secrets.store(key(scope, name), v.getValue());
                    secretNames.add(name);
                }
                continue;
            }
            if (fromTeam.isPresent() && v.getValue().equals(fromTeam.get().value())) {
                skipped.add(name + ": the team file sets the same value");
                continue;
            }
            boolean secret = org.kitout.domain.run.DotEnv.looksSecret(name) || org.kitout.domain.identity.Redactor.hasEmbeddedCredentials(v.getValue());
            current.remove(name);
            changes.add(new Change(name, v.getValue(), secret));
            (secret ? secretNames : added).add(name);
        }
        List<Change> all = new ArrayList<>();
        current.values().forEach(e -> all.add(new Change(e.name(), e.value(), e.secret(), e.use())));
        all.addAll(changes);
        if (!changes.isEmpty()) {
            save(scope, all);
        }
        return new ImportResult(added, secretNames, skipped);
    }

    /** Stores this developer's value for a secret the team file asks for. */
    public void setTeamSecret(String scope, String name, String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Enter a value for " + name);
        }
        boolean asked = teamValues(scope).stream().anyMatch(v -> v.secret() && v.name().equals(name));
        if (!asked) {
            throw new IllegalArgumentException(name + " is not a secret the team file asks for");
        }
        secrets.store(key(scope, name), value);
    }

    public List<Entry> list(String scope) {
        return vars(config.load(), scope).stream()
                .map(v -> new Entry(v.name(), v.secret() ? null : v.value(), v.secret(),
                        !v.secret() || secrets.lookup(key(scope, v.name())).isPresent(), v.use()))
                .toList();
    }

    /**
     * Replaces the variables of a scope. Names may be typed as Quarkus properties and are stored as
     * environment variable names. Secrets go to the keyring; secrets that were removed, or are no
     * longer secret, are deleted from it.
     */
    public List<Entry> save(String scope, List<Change> changes) {
        requireScope(scope);
        List<EnvVar> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Map<String, String> newSecrets = new LinkedHashMap<>();
        Set<String> keptSecrets = new HashSet<>();
        for (Change c : changes == null ? List.<Change>of() : changes) {
            if (c.name() == null || c.name().isBlank()) {
                continue;
            }
            String name = EnvNames.fromProperty(c.name());
            EnvNames.validate(name);
            if (!names.add(name)) {
                throw new IllegalArgumentException(name + " is listed twice");
            }
            if (c.secret()) {
                if (c.value() != null) {
                    newSecrets.put(name, c.value());
                } else if (secrets.lookup(key(scope, name)).isEmpty()) {
                    throw new IllegalArgumentException("Enter a value for the secret " + name);
                } else {
                    keptSecrets.add(name);
                }
            }
            result.add(new EnvVar(name, c.value(), c.secret(), c.use()));
        }
        List<EnvVar> before = vars(config.load(), scope);
        Set<String> teamSecrets = new HashSet<>();
        teamValues(scope).stream().filter(TeamValue::secret).forEach(v -> teamSecrets.add(v.name()));
        newSecrets.forEach((name, value) -> secrets.store(key(scope, name), value));
        for (EnvVar old : before) {
            // a secret the team file asks for shares the keyring entry: removing the personal line keeps it
            if (old.secret() && !newSecrets.containsKey(old.name()) && !keptSecrets.contains(old.name()) && !teamSecrets.contains(old.name())) {
                secrets.delete(key(scope, old.name()));
            }
        }
        config.update(c -> scope.equals(SHARED)
                ? c.withRuntime(new KitoutConfig.RuntimeSettings(result))
                : c.withService(scope, c.serviceSettings(scope).withEnvironment(result)));
        return list(scope);
    }

    /**
     * The variables a service starts with: shared ones, overridden by its own, secrets read from the keyring.
     *
     * @throws IllegalStateException when a secret is missing from the keyring (e.g. after moving to a new machine)
     */
    public Map<String, String> resolve(String serviceId) {
        KitoutConfig cfg = config.load();
        Map<String, String> env = new LinkedHashMap<>();
        for (String scope : List.of(SHARED, serviceId)) {
            // the team's values first, the developer's own on top: the same name wins over the team's
            for (TeamValue v : teamValues(scope)) {
                env.put(v.name(), v.secret() ? teamSecret(scope, v.name()) : v.value());
            }
            for (EnvVar v : vars(cfg, scope)) {
                if (v.use().forRun()) {
                    env.put(v.name(), v.secret() ? secret(scope, v.name()) : v.value());
                }
            }
        }
        return env;
    }

    /**
     * The variables a service is built with (on top of kitout's own environment): shared ones,
     * overridden by its own, for builds or both. The team file's variables are for running and are
     * not included.
     *
     * @throws IllegalStateException when a secret is missing from the keyring
     */
    public Map<String, String> resolveBuild(String serviceId) {
        KitoutConfig cfg = config.load();
        Map<String, String> env = new LinkedHashMap<>();
        for (String scope : List.of(SHARED, serviceId)) {
            for (EnvVar v : vars(cfg, scope)) {
                if (v.use().forBuild()) {
                    env.put(v.name(), v.secret() ? secret(scope, v.name()) : v.value());
                }
            }
        }
        return env;
    }

    private String teamSecret(String scope, String name) {
        return secrets.lookup(key(scope, name)).orElseThrow(() -> new IllegalStateException("The team file asks every developer for "
                + name + (scope.equals(SHARED) ? "" : " (" + scope + ")") + ". Enter yours in Settings, Team setup."));
    }

    private String secret(String scope, String name) {
        Optional<String> value = secrets.lookup(key(scope, name));
        return value.orElseThrow(() -> new IllegalStateException("The secret " + name
                + (scope.equals(SHARED) ? " (shared)" : " of " + scope) + " is not in the keyring any more. Enter it again under Environment."));
    }

    private static List<EnvVar> vars(KitoutConfig cfg, String scope) {
        return scope.equals(SHARED) ? cfg.runtime().environment() : cfg.serviceSettings(scope).environment();
    }

    private void requireScope(String scope) {
        if (!scope.equals(SHARED)) {
            workspace.require(scope);
        }
    }

    /** Where a secret variable lives in the keyring. */
    public static String key(String scope, String name) {
        return "kitout-env/" + (scope.equals(SHARED) ? "shared" : scope) + "/" + name;
    }
}
