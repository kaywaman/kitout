package org.kitout.application.build;

import org.kitout.domain.build.BuildMode;
import org.kitout.domain.build.BuildRequest;
import org.kitout.domain.build.BuildStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildRemovalTest {

    private static BuildJob job(String id, String service, BuildStatus status, Path dir) throws Exception {
        Path log = Files.writeString(dir.resolve(id + ".log"), "log of " + id);
        BuildJob j = new BuildJob(id, service, new BuildRequest("acme." + service, "dev", BuildMode.JVM, false), log, "1.4.0");
        if (status != BuildStatus.QUEUED) {
            j.finish(status, null);
        }
        Thread.sleep(5);   // distinct creation times
        return j;
    }

    @Test
    void finishedAttemptsGoWithTheirLogsQueuedOnesWait() throws Exception {
        Path dir = Files.createTempDirectory("kitout-builds");
        BuildService builds = new BuildService(null, null, null, null, null, null);
        BuildJob old = job("b1", "orders", BuildStatus.SUCCEEDED, dir);
        BuildJob newest = job("b2", "orders", BuildStatus.SUCCEEDED, dir);
        BuildJob billing = job("b3", "billing", BuildStatus.FAILED, dir);
        BuildJob queued = job("b4", "billing", BuildStatus.QUEUED, dir);
        List.of(old, newest, billing, queued).forEach(builds::remember);

        assertTrue(builds.newestSucceeded(newest));
        assertFalse(builds.newestSucceeded(old), "its output was replaced by b2");

        BuildService.Removal r = builds.remove(List.of("b1", "b2", "b3", "b4"));

        assertEquals(List.of("b1", "b2", "b3"), r.removed());
        assertEquals(1, r.kept().size(), r.kept().toString());
        assertTrue(r.kept().get(0).contains("cancel it first"), r.kept().get(0));
        assertFalse(Files.exists(dir.resolve("b1.log")), "the log goes with the attempt");
        assertEquals("1.4.0", newest.version().orElseThrow());
    }
}
