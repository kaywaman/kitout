package org.kitout.domain.run;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ImageRetentionTest {

    private static final String REPO = "images.example.com/orders";

    private static ImageRetention.Version v(String id, String tag, String day) {
        return new ImageRetention.Version("sha256:" + id + "0000000000000000", tag, "2026-10-" + day + " 10:00:00 +0100 CET");
    }

    @Test
    void keepsTheNewestVersionsAndRemovesTheOldestFirst() {
        List<ImageRetention.Version> versions = List.of(
                v("aaaaaaaaaaaa", "mr-120", "30"), v("bbbbbbbbbbbb", "mr-118", "28"), v("cccccccccccc", "1.4.0", "20"),
                v("dddddddddddd", "<none>", "15"), v("eeeeeeeeeeee", "1.3.0", "10"));
        assertEquals(List.of(REPO + ":1.3.0", "dddddddddddd"), ImageRetention.toRemove(REPO, versions, 3, Set.of()));
        assertEquals(List.of(), ImageRetention.toRemove(REPO, versions, 5, Set.of()));
    }

    @Test
    void anImageInUseStaysWhateverItsAge() {
        List<ImageRetention.Version> versions = List.of(v("aaaaaaaaaaaa", "mr-120", "30"), v("bbbbbbbbbbbb", "mr-118", "28"),
                v("cccccccccccc", "1.0.0", "01"));
        assertEquals(List.of(REPO + ":mr-118"),
                ImageRetention.toRemove(REPO, versions, 1, Set.of("sha256:cccccccccccc0000000000000000")));
    }

    @Test
    void twoTagsOfOneImageAreOneVersion() {
        List<ImageRetention.Version> versions = List.of(v("aaaaaaaaaaaa", "mr-120", "30"), v("aaaaaaaaaaaa", "main", "30"),
                v("bbbbbbbbbbbb", "mr-118", "28"));
        assertEquals(List.of(REPO + ":mr-118"), ImageRetention.toRemove(REPO, versions, 1, Set.of()));
    }
}
