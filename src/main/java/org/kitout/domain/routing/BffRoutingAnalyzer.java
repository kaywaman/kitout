package org.kitout.domain.routing;

import org.kitout.domain.config.Endpoint;
import org.kitout.domain.config.EndpointExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Cross-checks the BFF's hardcoded dev URLs against the group's port map.
 *
 * <p>Flags a route that points at a port no member owns, a route whose config key names one
 * service but whose port belongs to another, and a route that points back at the BFF itself.
 * The result is advisory: it reads files and cannot see environment variables set at runtime.
 */
public final class BffRoutingAnalyzer {

    public enum Kind { OK, NO_OWNER, WRONG_OWNER, SELF, NOT_LOCAL, UNPARSEABLE }

    public record Finding(String route, String url, Kind kind, Optional<String> owner, Optional<String> expected) {

        public boolean isProblem() {
            return kind == Kind.NO_OWNER || kind == Kind.WRONG_OWNER || kind == Kind.SELF || kind == Kind.UNPARSEABLE;
        }

        public String message() {
            return switch (kind) {
                case OK -> "'" + route + "' -> " + owner.orElse("?");
                case NO_OWNER -> "'" + route + "' points at " + url + ", but no service in the group uses that port";
                case WRONG_OWNER -> "'" + route + "' points at " + owner.orElse("?") + "'s port, but its name suggests "
                        + expected.orElse("?");
                case SELF -> "'" + route + "' points at the BFF's own port";
                case NOT_LOCAL -> "'" + route + "' points at " + url + " (not localhost, not checked)";
                case UNPARSEABLE -> "'" + route + "' has a URL that cannot be read: " + url;
            };
        }
    }

    private static final Set<String> NOISE = Set.of("client", "service", "api", "rest", "svc", "ms", "backend");

    private BffRoutingAnalyzer() {
    }

    public static List<Finding> analyze(Map<String, String> routes, RoutingMember bff, List<RoutingMember> services) {
        List<Finding> findings = new ArrayList<>();
        routes.forEach((route, url) -> findings.add(analyzeRoute(route, url, bff, services)));
        return findings;
    }

    private static Finding analyzeRoute(String route, String url, RoutingMember bff, List<RoutingMember> services) {
        Optional<Endpoint> endpoint = EndpointExtractor.parseHttpUrl(url, route);
        if (endpoint.isEmpty()) {
            return new Finding(route, url, Kind.UNPARSEABLE, Optional.empty(), Optional.empty());
        }
        if (!endpoint.get().isLocal()) {
            return new Finding(route, url, Kind.NOT_LOCAL, Optional.empty(), Optional.empty());
        }
        int port = endpoint.get().port();
        if (port == bff.port()) {
            return new Finding(route, url, Kind.SELF, Optional.of(bff.name()), Optional.empty());
        }
        Optional<RoutingMember> owner = services.stream().filter(s -> s.port() == port).findFirst();
        if (owner.isEmpty()) {
            return new Finding(route, url, Kind.NO_OWNER, Optional.empty(), bestMatch(route, services).map(RoutingMember::name));
        }
        if (related(route, owner.get())) {
            return new Finding(route, url, Kind.OK, Optional.of(owner.get().name()), Optional.empty());
        }
        Optional<RoutingMember> better = bestMatch(route, services);
        if (better.isPresent() && !better.get().id().equals(owner.get().id())) {
            return new Finding(route, url, Kind.WRONG_OWNER, Optional.of(owner.get().name()), Optional.of(better.get().name()));
        }
        // The key does not name any member clearly; the port is owned, so trust it.
        return new Finding(route, url, Kind.OK, Optional.of(owner.get().name()), Optional.empty());
    }

    private static Optional<RoutingMember> bestMatch(String route, List<RoutingMember> services) {
        return services.stream().filter(s -> related(route, s)).findFirst();
    }

    /** "orders", "OrdersClient", "io.acme.orders.OrderService" all relate to a service named "orders-service". */
    static boolean related(String route, RoutingMember member) {
        Set<String> routeTokens = tokens(lastSegment(route));
        Set<String> memberTokens = tokens(member.name());
        memberTokens.addAll(tokens(lastSegment(member.id())));
        for (String r : routeTokens) {
            for (String m : memberTokens) {
                if (r.equals(m) || singular(r).equals(singular(m))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String lastSegment(String s) {
        int dot = s.lastIndexOf('.');
        return dot >= 0 && dot < s.length() - 1 ? s.substring(dot + 1) : s;
    }

    private static Set<String> tokens(String s) {
        return java.util.Arrays.stream(s.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(t -> t.length() > 1 && !NOISE.contains(t))
                .collect(Collectors.toCollection(java.util.HashSet::new));
    }

    private static String singular(String s) {
        return s.endsWith("s") && s.length() > 3 ? s.substring(0, s.length() - 1) : s;
    }
}
