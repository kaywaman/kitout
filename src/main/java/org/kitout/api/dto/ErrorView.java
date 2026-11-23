package org.kitout.api.dto;

/** Every error response: a stable code for the UI and a sentence for the developer. */
public record ErrorView(String error, String message) {
}
