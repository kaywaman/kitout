package org.kitout.api.dto;

import java.util.List;

public record PortView(int port, List<String> services, boolean conflict) {
}
