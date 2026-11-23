package org.kitout.api.dto;

/** @param scope "shared" or a service id; the value goes to the keyring and is never sent back */
public record SecretValueRequest(String scope, String value) {
}
