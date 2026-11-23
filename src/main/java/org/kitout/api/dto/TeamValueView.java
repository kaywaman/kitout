package org.kitout.api.dto;

/** A variable or secret the team file sets for everyone. A secret's value is never sent; {@code set} says if this developer entered it. */
public record TeamValueView(String name, String value, boolean secret, boolean set, String scope) {
}
