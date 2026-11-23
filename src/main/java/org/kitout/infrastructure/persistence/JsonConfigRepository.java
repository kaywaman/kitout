package org.kitout.infrastructure.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.kitout.application.KitoutPaths;
import org.kitout.application.config.PersonalConfigRepository;
import org.kitout.application.config.KitoutConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * {@code ~/.config/kitout/config.json} with a versioned schema.
 *
 * <p>Migrations: when the stored {@code schemaVersion} is older than
 * {@link KitoutConfig#CURRENT_SCHEMA}, the file is backed up as {@code config.json.bak-v<old>}
 * and each step in {@link #MIGRATIONS} transforms the JSON tree before it is read. A file from
 * a newer kitout is never overwritten.
 */
@ApplicationScoped
@Typed({PersonalConfigRepository.class, JsonConfigRepository.class})
public class JsonConfigRepository implements PersonalConfigRepository {

    private static final Logger LOG = Logger.getLogger(JsonConfigRepository.class);

    /** schemaVersion N -> transformation to N+1. Add an entry when you bump CURRENT_SCHEMA. */
    static final Map<Integer, UnaryOperator<ObjectNode>> MIGRATIONS = Map.of(
            1, JsonConfigRepository::oneBffToMany,
            2, root -> root);   // schema 3: the format did not change

    /** Schema 1 to 2: {@code "bff": "x"} (or null) in each group becomes {@code "bffs": ["x"]} (or []). */
    static ObjectNode oneBffToMany(ObjectNode root) {
        JsonNode groups = root.path("groups");
        if (groups.isArray()) {
            for (JsonNode g : groups) {
                if (g instanceof ObjectNode group && !group.has("bffs")) {
                    JsonNode bff = group.remove("bff");
                    var list = group.putArray("bffs");
                    if (bff != null && bff.isTextual() && !bff.asText().isBlank()) {
                        list.add(bff.asText());
                    }
                }
            }
        }
        return root;
    }

    private final JsonFileStore store;

    public JsonConfigRepository(KitoutPaths paths, ObjectMapper json) {
        this.store = new JsonFileStore(paths.configFile(), json);
    }

    @Override
    public KitoutConfig load() {
        return store.locked(this::read);
    }

    @Override
    public KitoutConfig update(UnaryOperator<KitoutConfig> change) {
        return store.locked(() -> {
            KitoutConfig updated = change.apply(read());
            store.writeAtomically(updated);
            return updated;
        });
    }

    private KitoutConfig read() {
        Path file = store.file();
        if (!Files.exists(file)) {
            return KitoutConfig.defaults();
        }
        try {
            JsonNode tree = store.json().readTree(file.toFile());
            if (!(tree instanceof ObjectNode root)) {
                throw new IllegalStateException(file + " is not a JSON object");
            }
            int version = root.path("schemaVersion").asInt(1);
            if (version > KitoutConfig.CURRENT_SCHEMA) {
                throw new IllegalStateException(file + " was written by a newer kitout (schema " + version
                        + "). Update kitout; the file was not changed.");
            }
            if (version < KitoutConfig.CURRENT_SCHEMA) {
                root = migrate(file, root, version);
            }
            return store.json().treeToValue(root, KitoutConfig.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + file + ": " + e.getMessage()
                    + ". Fix the JSON or move the file away to start fresh.", e);
        }
    }

    private ObjectNode migrate(Path file, ObjectNode root, int from) throws IOException {
        Files.copy(file, file.resolveSibling(file.getFileName() + ".bak-v" + from), StandardCopyOption.REPLACE_EXISTING);
        ObjectNode current = root;
        for (int v = from; v < KitoutConfig.CURRENT_SCHEMA; v++) {
            UnaryOperator<ObjectNode> step = MIGRATIONS.get(v);
            if (step != null) {
                current = step.apply(current);
            }
            current.put("schemaVersion", v + 1);
        }
        LOG.infof("Migrated %s from schema %d to %d (backup kept)", file, from, KitoutConfig.CURRENT_SCHEMA);
        return current;
    }
}
