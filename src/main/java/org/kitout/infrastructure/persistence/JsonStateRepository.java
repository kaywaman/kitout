package org.kitout.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.kitout.application.KitoutPaths;
import org.kitout.application.run.RunState;
import org.kitout.application.run.StateRepository;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.util.function.UnaryOperator;

/**
 * {@code ~/.local/state/kitout/state.json}: the process groups of running services.
 * An unreadable state file is treated as empty (and kept as {@code state.json.broken}),
 * because the worst case is that kitout forgets a service it started.
 */
@ApplicationScoped
public class JsonStateRepository implements StateRepository {

    private static final Logger LOG = Logger.getLogger(JsonStateRepository.class);
    private final JsonFileStore store;

    public JsonStateRepository(KitoutPaths paths, ObjectMapper json) {
        this.store = new JsonFileStore(paths.stateFile(), json);
    }

    @Override
    public RunState load() {
        return store.locked(this::read);
    }

    @Override
    public RunState update(UnaryOperator<RunState> change) {
        return store.locked(() -> {
            RunState updated = change.apply(read());
            store.writeAtomically(updated);
            return updated;
        });
    }

    private RunState read() {
        if (!Files.exists(store.file())) {
            return RunState.empty();
        }
        try {
            return store.json().readValue(store.file().toFile(), RunState.class);
        } catch (IOException e) {
            LOG.warnf("Ignoring unreadable %s: %s", store.file(), e.getMessage());
            try {
                Files.move(store.file(), store.file().resolveSibling("state.json.broken"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // nothing more to do
            }
            return RunState.empty();
        }
    }
}
