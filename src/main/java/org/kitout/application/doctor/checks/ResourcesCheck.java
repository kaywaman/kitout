package org.kitout.application.doctor.checks;

import org.kitout.application.KitoutPaths;
import org.kitout.application.doctor.DoctorCheck;
import org.kitout.application.doctor.DoctorContext;
import org.kitout.application.port.SystemInfo;
import org.kitout.domain.doctor.CheckResult;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/** Enough free memory and disk for native builds. */
@ApplicationScoped
public class ResourcesCheck implements DoctorCheck {

    static final long MIN_DISK_MB = 10 * 1024;
    private final SystemInfo system;
    private final KitoutPaths paths;

    public ResourcesCheck(SystemInfo system, KitoutPaths paths) {
        this.system = system;
        this.paths = paths;
    }

    @Override
    public String id() {
        return "resources";
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    public List<CheckResult> run(DoctorContext context) {
        List<CheckResult> results = new ArrayList<>();
        int minMemory = context.config().build().nativeMinFreeMemoryMb();
        OptionalLong memory = system.availableMemoryMb();
        if (memory.isPresent()) {
            results.add(memory.getAsLong() >= minMemory
                    ? CheckResult.ok(id(), "Free memory", memory.getAsLong() + " MB available")
                    : CheckResult.warning(id(), "Free memory", memory.getAsLong() + " MB available; native builds need "
                    + minMemory + " MB", "Close applications or stop services before a native build"));
        }
        OptionalLong disk = system.freeDiskMb(paths.expand(context.config().gitlab().workspaceRoot()));
        if (disk.isPresent()) {
            results.add(disk.getAsLong() >= MIN_DISK_MB
                    ? CheckResult.ok(id(), "Free disk", disk.getAsLong() / 1024 + " GB free in the workspace")
                    : CheckResult.warning(id(), "Free disk", disk.getAsLong() / 1024 + " GB free in the workspace",
                    "Free disk space, for example with 'docker system prune'"));
        }
        return results;
    }
}
