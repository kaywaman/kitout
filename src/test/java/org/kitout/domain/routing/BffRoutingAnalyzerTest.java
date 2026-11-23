package org.kitout.domain.routing;

import org.kitout.domain.routing.BffRoutingAnalyzer.Finding;
import org.kitout.domain.routing.BffRoutingAnalyzer.Kind;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BffRoutingAnalyzerTest {

    private static final RoutingMember BFF = new RoutingMember("acme.shop.bff", "bff", 8080);
    private static final List<RoutingMember> SERVICES = List.of(
            new RoutingMember("acme.shop.orders-service", "orders-service", 8081),
            new RoutingMember("acme.shop.billing", "billing", 8082));

    private static Kind kindOf(String route, String url) {
        Map<String, String> routes = new LinkedHashMap<>();
        routes.put(route, url);
        return BffRoutingAnalyzer.analyze(routes, BFF, SERVICES).get(0).kind();
    }

    @Test
    void matchingRouteIsOk() {
        assertEquals(Kind.OK, kindOf("orders", "http://localhost:8081"));
        assertEquals(Kind.OK, kindOf("io.acme.OrdersClient", "http://localhost:8081/api"));
    }

    @Test
    void flagsPortWithoutOwner() {
        assertEquals(Kind.NO_OWNER, kindOf("orders", "http://localhost:9999"));
    }

    @Test
    void flagsRouteNamedForOneServiceButPointingAtAnother() {
        Finding f = BffRoutingAnalyzer.analyze(Map.of("orders", "http://localhost:8082"), BFF, SERVICES).get(0);
        assertEquals(Kind.WRONG_OWNER, f.kind());
        assertTrue(f.message().contains("billing"));
        assertTrue(f.message().contains("orders-service"));
    }

    @Test
    void flagsRouteToItself() {
        assertEquals(Kind.SELF, kindOf("orders", "http://localhost:8080"));
    }

    @Test
    void skipsNonLocalHosts() {
        Finding f = BffRoutingAnalyzer.analyze(Map.of("ext", "https://api.partner.com"), BFF, SERVICES).get(0);
        assertEquals(Kind.NOT_LOCAL, f.kind());
        assertFalse(f.isProblem());
    }

    @Test
    void unknownKeyOnOwnedPortIsTrusted() {
        assertEquals(Kind.OK, kindOf("legacy", "http://127.0.0.1:8082"));
    }

    @Test
    void flagsUnreadableUrl() {
        assertEquals(Kind.UNPARSEABLE, kindOf("orders", "not a url"));
    }
}
