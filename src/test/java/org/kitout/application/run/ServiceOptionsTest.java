package org.kitout.application.run;

import org.kitout.application.KitoutPaths;
import org.kitout.application.config.KitoutConfig.VolumeMount;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceOptionsTest {

    private final ServiceOptions options = new ServiceOptions(null, null, new KitoutPaths(Map.of("KITOUT_HOME", "/tmp/kitout-options")));

    @Test
    void emptyRowsAreDroppedAndGoodOnesKept() {
        List<VolumeMount> v = options.check(Arrays.asList(new VolumeMount("", "", false), new VolumeMount("~/certs", "/certs", true), null));
        assertEquals(List.of(new VolumeMount("~/certs", "/certs", true)), v);
    }

    @Test
    void halfFilledRelativeOrDoubledVolumesAreRefused() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> options.check(List.of(new VolumeMount("~/certs", "", false))))
                .getMessage().contains("needs both paths"));
        assertThrows(IllegalArgumentException.class, () -> options.check(List.of(new VolumeMount("~/certs", "certs", false))));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> options.check(List.of(
                new VolumeMount("~/a", "/data", false), new VolumeMount("~/b", "/data", false)))).getMessage().contains("twice"));
    }
}
