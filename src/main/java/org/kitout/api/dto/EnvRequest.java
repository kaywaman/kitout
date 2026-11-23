package org.kitout.api.dto;

import java.util.List;

/** The complete list of variables of one scope; variables left out are removed. */
public record EnvRequest(List<EnvVarView> variables) {
}
