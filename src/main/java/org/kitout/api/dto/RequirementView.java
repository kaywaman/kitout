package org.kitout.api.dto;

/**
 * One machine requirement and what this machine has.
 *
 * @param outcome PASS, WARN or FAIL
 * @param note    why it is only a warning, if it is one
 */
public record RequirementView(String id, String name, String required, String found, String detail, String outcome,
                              String problem, String fix, String note) {
}
