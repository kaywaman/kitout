package org.kitout.domain.routing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Ports of a group's members and which ports are claimed twice. Ports are read, never assigned. */
public final class PortMap {

    private final Map<Integer, List<String>> byPort = new TreeMap<>();

    public PortMap(Map<String, Integer> portByService) {
        portByService.forEach((service, port) -> byPort.computeIfAbsent(port, p -> new ArrayList<>()).add(service));
    }

    /** Port to the services that claim it, only where more than one does. */
    public Map<Integer, List<String>> conflicts() {
        Map<Integer, List<String>> result = new LinkedHashMap<>();
        byPort.forEach((port, services) -> {
            if (services.size() > 1) {
                result.put(port, List.copyOf(services));
            }
        });
        return result;
    }

    public Map<Integer, List<String>> all() {
        Map<Integer, List<String>> copy = new LinkedHashMap<>();
        byPort.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        return copy;
    }
}
