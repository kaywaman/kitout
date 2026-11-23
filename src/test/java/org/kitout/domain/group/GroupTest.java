package org.kitout.domain.group;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GroupTest {

    @Test
    void servicesStartTogetherThenBff() {
        Group g = new Group("shop", List.of("bff", "orders", "billing"), List.of("bff"), false, false);
        assertEquals(List.of(List.of("orders", "billing"), List.of("bff")), g.startTiers());
    }

    @Test
    void sequentialStartGivesOneTierPerService() {
        Group g = new Group("shop", List.of("orders", "billing", "bff"), List.of("bff"), true, false);
        assertEquals(List.of(List.of("orders"), List.of("billing"), List.of("bff")), g.startTiers());
    }

    @Test
    void stopsBffFirst() {
        Group g = new Group("shop", List.of("orders", "billing", "bff"), List.of("bff"), false, false);
        assertEquals(List.of("bff", "orders", "billing"), g.stopOrder());
    }

    @Test
    void worksWithoutBff() {
        Group g = new Group("tools", List.of("a", "b"), List.of(), false, false);
        assertEquals(List.of(List.of("a", "b")), g.startTiers());
    }

    @Test
    void severalBffsStartTogetherAfterTheServices() {
        Group g = new Group("shop", List.of("web-bff", "orders", "mobile-bff", "billing"), List.of("mobile-bff", "web-bff"), false, false);
        assertEquals(List.of(List.of("orders", "billing"), List.of("web-bff", "mobile-bff")), g.startTiers(),
                "the BFF tier keeps member order");
        assertEquals(List.of("web-bff", "mobile-bff", "orders", "billing"), g.stopOrder());
        assertEquals(true, g.isBffTier(List.of("web-bff", "mobile-bff")));
        assertEquals(false, g.isBffTier(List.of("orders", "billing")));
    }

    @Test
    void withoutMembersDropsRemovedBffs() {
        Group g = new Group("shop", List.of("web-bff", "orders", "mobile-bff"), List.of("web-bff", "mobile-bff"), false, false);
        Group left = g.withoutMembers(List.of("web-bff"));
        assertEquals(List.of("orders", "mobile-bff"), left.members());
        assertEquals(List.of("mobile-bff"), left.bffs());
    }

    @Test
    void bffMustBeMember() {
        assertThrows(IllegalArgumentException.class, () -> new Group("x", List.of("a"), List.of("bff"), false, false));
    }
}
