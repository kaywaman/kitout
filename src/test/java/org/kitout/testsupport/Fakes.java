package org.kitout.testsupport;

import org.kitout.application.config.ConfigRepository;
import org.kitout.application.config.KitoutConfig;
import org.kitout.application.port.SecretStore;
import org.kitout.application.run.RunState;
import org.kitout.application.run.StateRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** Small in-memory stand-ins for ports, for fast tests without files or a keyring. */
public final class Fakes {

    private Fakes() {
    }

    public static final class InMemoryConfig implements org.kitout.application.config.PersonalConfigRepository {
        private KitoutConfig config;

        public InMemoryConfig(KitoutConfig initial) {
            this.config = initial;
        }

        @Override
        public synchronized KitoutConfig load() {
            return config;
        }

        @Override
        public synchronized KitoutConfig update(UnaryOperator<KitoutConfig> change) {
            config = change.apply(config);
            return config;
        }
    }

    public static final class InMemorySecrets implements SecretStore {
        private final Map<String, String> tokens = new HashMap<>();

        @Override
        public Optional<String> lookup(String host) {
            return Optional.ofNullable(tokens.get(host));
        }

        @Override
        public void store(String host, String token) {
            tokens.put(host, token);
        }

        @Override
        public void delete(String host) {
            tokens.remove(host);
        }

        @Override
        public String location() {
            return "memory";
        }
    }

    public static final class InMemoryState implements StateRepository {
        private RunState state = RunState.empty();

        @Override
        public synchronized RunState load() {
            return state;
        }

        @Override
        public synchronized RunState update(UnaryOperator<RunState> change) {
            state = change.apply(state);
            return state;
        }
    }
}
