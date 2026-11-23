package org.kitout.api.dto;

/**
 * Sets the run mode of many services at once.
 *
 * @param mode       IMAGE, JVM, NATIVE or IDE
 * @param serviceIds the services to change; empty or null means all known services
 */
public record RunModeRequest(String mode, java.util.List<String> serviceIds) {
}
