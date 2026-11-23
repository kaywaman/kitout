package org.kitout.infrastructure;

import org.kitout.application.config.KitoutConfig;
import org.kitout.application.run.RunState;
import org.kitout.domain.group.Group;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Types that Jackson reads and writes outside of REST signatures (the JSON files). A native
 * executable only keeps reflection data for registered types, so every persisted record is
 * listed here. Keeping the list in one place keeps framework annotations out of the domain.
 * REST DTOs need no entry: Quarkus registers them from the resource signatures.
 */
@RegisterForReflection(targets = {
        KitoutConfig.class,
        KitoutConfig.GitLabSettings.class,
        KitoutConfig.ServiceSettings.class,
        KitoutConfig.BuildSettings.class,
        KitoutConfig.DoctorSettings.class,
        KitoutConfig.DockerSettings.class,
        KitoutConfig.RuntimeSettings.class,
        KitoutConfig.EnvVar.class,
        KitoutConfig.TeamSettings.class,
        Group.class,
        RunState.class,
        RunState.Run.class,
        RunState.Phase.class
})
public final class NativeReflection {

    private NativeReflection() {
    }
}
