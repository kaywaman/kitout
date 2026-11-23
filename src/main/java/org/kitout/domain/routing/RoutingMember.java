package org.kitout.domain.routing;

/** A group member as the routing check sees it: id, display name and dev port. */
public record RoutingMember(String id, String name, int port) {
}
