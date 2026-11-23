package org.kitout.application.doctor.checks;

import org.kitout.application.doctor.DoctorCheck;
import org.kitout.application.doctor.DoctorContext;
import org.kitout.application.port.CommandRunner.Result;
import org.kitout.application.registry.RegistryLogins;
import org.kitout.application.run.DockerCli;
import org.kitout.application.run.ServiceSupervisor;
import org.kitout.domain.doctor.CheckResult;
import org.kitout.domain.identity.Redactor;
import org.kitout.domain.project.Project;
import org.kitout.domain.run.ImageRef;
import org.kitout.domain.run.RunMode;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * For each registry that image-mode services pull from: every such service has a tag chosen, a
 * docker login exists for the registry, and
 * one image can actually be read ({@code docker manifest inspect}, which uses the same login as a pull).
 */
@ApplicationScoped
public class RegistryLoginCheck implements DoctorCheck {

    static final String LOGIN_FIX = "Log in under Settings, Container registry (or: docker login <registry>). "
            + "Use your registry login, which may differ from your git token";

    private final DockerCli docker;
    private final ServiceSupervisor supervisor;
    private final RegistryLogins logins;

    public RegistryLoginCheck(DockerCli docker, ServiceSupervisor supervisor, RegistryLogins logins) {
        this.docker = docker;
        this.supervisor = supervisor;
        this.logins = logins;
    }

    @Override
    public String id() {
        return "registry-login";
    }

    @Override
    public int order() {
        return 20;
    }

    @Override
    public boolean appliesTo(DoctorContext context) {
        return context.members().stream().anyMatch(m -> context.runMode(m) == RunMode.IMAGE);
    }

    @Override
    public List<CheckResult> run(DoctorContext context) {
        Map<String, String> sampleImageByRegistry = new LinkedHashMap<>();
        List<String> withoutTag = new ArrayList<>();
        List<CheckResult> results = new ArrayList<>();
        for (Project m : context.members()) {
            if (context.runMode(m) != RunMode.IMAGE) {
                continue;
            }
            if (ServiceSupervisor.needsTag(context.config().serviceSettings(m.id()))) {
                withoutTag.add(m.name());
                continue;
            }
            try {
                String image = supervisor.image(m, context.config().serviceSettings(m.id()), context.config());
                sampleImageByRegistry.putIfAbsent(ImageRef.parse(image).registry(), image);
            } catch (IllegalStateException e) {
                results.add(CheckResult.error(id(), "Image for " + m.name(), e.getMessage(),
                        "Set the container registry in Settings, or an image for this service"));
            }
        }
        if (!withoutTag.isEmpty()) {
            results.add(CheckResult.error(id(), "Image tags", "No image tag chosen for " + String.join(", ", withoutTag),
                    "Choose a tag next to 'Registry image' on the Services page (kitout does not run 'latest'), or run them in another mode"));
        }
        sampleImageByRegistry.forEach((registry, image) -> results.add(check(registry, image)));
        return results;
    }

    private CheckResult check(String registry, String image) {
        String title = "Registry login " + registry;
        // docker itself is the judge: manifest inspect uses exactly the login a pull would use
        Result r = docker.manifestInspect(image);
        if (r.succeeded()) {
            return CheckResult.ok(id(), title, "Logged in; " + image + " is readable");
        }
        String out = r.output().toLowerCase(Locale.ROOT);
        if (out.contains("unauthorized") || out.contains("denied") || out.contains("authentication required")) {
            RegistryLogins.Login login = logins.check(registry);
            return CheckResult.error(id(), title, login.loggedIn()
                    ? "The registry refused the login for " + image + " (expired, or this user may not pull it)"
                    : login.detail(), LOGIN_FIX);
        }
        if (out.contains("no such manifest") || out.contains("not found") || out.contains("manifest unknown")) {
            return CheckResult.warning(id(), title, "Logged in, but " + image + " does not exist",
                    "Choose an existing tag on the Services page; branch and merge request tags disappear when the registry cleans up");
        }
        return CheckResult.warning(id(), title, "Could not read " + image + ": " + Redactor.redact(r.firstLine()),
                "Check your network and run the doctor again");
    }
}
