package org.kitout.domain.run;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageRefTest {

    @Test
    void splitsRepositoryAndTagWithoutConfusingARegistryPort() {
        ImageRef ref = ImageRef.parse("registry.example.com:5000/acme/shop/orders:mr-123");
        assertEquals("registry.example.com:5000/acme/shop/orders", ref.repository());
        assertEquals(Optional.of("mr-123"), ref.tag());
        assertEquals("registry.example.com:5000", ref.registry());
        assertEquals("acme/shop/orders", ref.path());

        ImageRef untagged = ImageRef.parse("registry.example.com:5000/acme/shop/orders");
        assertEquals(Optional.empty(), untagged.tag());
        assertEquals("registry.example.com:5000/acme/shop/orders:1.4.2", untagged.withTag("1.4.2"));

        assertEquals(Optional.of("@sha256:abc"), ImageRef.parse("r.example.com/a/b@sha256:abc").tag());
        assertEquals("docker.io", ImageRef.parse("postgres:17").registry());
    }

    @Test
    void validatesTags() {
        assertTrue(ImageRef.isValidTag("mr-123"));
        assertTrue(ImageRef.isValidTag("1.4.2_build.7"));
        assertFalse(ImageRef.isValidTag("-x"));
        assertFalse(ImageRef.isValidTag("a b"));
        assertFalse(ImageRef.isValidTag("x;rm -rf /"));
        assertFalse(ImageRef.isValidTag(null));
    }

    @Test
    void pickerLeavesOutLatestAndShowsNewestFirst() {
        assertEquals(List.of("mr-120", "mr-99", "main", "1.10.0", "1.9.2"),
                ImageTags.forPicker(List.of("1.9.2", "latest", "mr-99", "1.10.0", "main", "mr-120", "LATEST")));
    }
}
