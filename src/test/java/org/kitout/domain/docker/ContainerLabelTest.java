package org.kitout.domain.docker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContainerLabelTest {

    @Test
    void kitoutsContainersAreRecognisedByTheirLabel() {
        String kitout = "abc\tkitout-orders\timg\trunning\tUp\t\t\t\tacme.orders";
        String other = "abc\tpostgres\timg\trunning\tUp\t\tinfra\t/x.yml\t";
        assertEquals("acme.orders", Container.parse(kitout).orElseThrow().kitoutService());
        assertEquals("", Container.parse(other).orElseThrow().kitoutService());
    }
}
