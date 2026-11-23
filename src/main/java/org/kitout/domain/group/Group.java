package org.kitout.domain.group;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A set of services a developer runs together, usually one or more BFFs and the services they route to.
 *
 * @param name            unique, shown in the UI and used in {@code kitout up <name>}
 * @param members         service ids in start order
 * @param bffs            the entry points (e.g. a web BFF and a mobile BFF), started together after the others;
 *                        empty for groups without one
 * @param sequentialStart start members one after another, e.g. to avoid colliding DB migrations
 * @param suggested       true for groups derived from the GitLab namespace and not yet edited
 */
public record Group(String name, List<String> members, List<String> bffs, boolean sequentialStart, boolean suggested) {

    public Group {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Group name must not be blank");
        }
        members = List.copyOf(members);
        bffs = bffs == null ? List.of() : bffs.stream().filter(Objects::nonNull).distinct().toList();
        for (String bff : bffs) {
            if (!members.contains(bff)) {
                throw new IllegalArgumentException("BFF '" + bff + "' is not a member of group '" + name + "'");
            }
        }
    }

    public boolean isBff(String serviceId) {
        return bffs.contains(serviceId);
    }

    /**
     * Start tiers: all other services first (as one tier, or one tier per service if sequential),
     * then the BFFs together. Each tier must be up before the next one starts.
     */
    public List<List<String>> startTiers() {
        List<String> services = members.stream().filter(m -> !bffs.contains(m)).toList();
        List<List<String>> tiers = new ArrayList<>();
        if (sequentialStart) {
            services.forEach(s -> tiers.add(List.of(s)));
        } else if (!services.isEmpty()) {
            tiers.add(services);
        }
        if (!bffs.isEmpty()) {
            tiers.add(members.stream().filter(bffs::contains).toList());   // in member order
        }
        return tiers;
    }

    /** True for the last tier when the group has BFFs. */
    public boolean isBffTier(List<String> tier) {
        return !bffs.isEmpty() && !tier.isEmpty() && bffs.containsAll(tier);
    }

    /** Stop order is the reverse: BFFs first, so they never route to a service that is already gone. */
    public List<String> stopOrder() {
        List<String> order = new ArrayList<>();
        startTiers().reversed().forEach(order::addAll);
        return order;
    }

    /** The same group without the given members (e.g. removed services); BFFs that were removed are dropped too. */
    public Group withoutMembers(java.util.Collection<String> removed) {
        List<String> left = members.stream().filter(m -> !removed.contains(m)).toList();
        return new Group(name, left, bffs.stream().filter(left::contains).toList(), sequentialStart, suggested);
    }
}
