package org.kitout.application.doctor.checks;

import org.kitout.application.doctor.DoctorCheck;
import org.kitout.application.doctor.DoctorContext;
import org.kitout.application.port.CommandRunner.Result;
import org.kitout.application.run.DockerCli;
import org.kitout.domain.doctor.CheckResult;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

/** Docker daemon running and usable without sudo. */
@ApplicationScoped
public class DockerCheck implements DoctorCheck {

    private static final String TITLE = "Docker daemon";
    private final DockerCli docker;

    public DockerCheck(DockerCli docker) {
        this.docker = docker;
    }

    @Override
    public String id() {
        return "docker";
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public List<CheckResult> run(DoctorContext context) {
        Result r = docker.info();
        if (r.succeeded()) {
            return List.of(CheckResult.ok(id(), TITLE, "Docker " + r.firstLine() + " is running"));
        }
        if (r.notFound()) {
            return List.of(CheckResult.error(id(), TITLE, "The docker command is not installed",
                    "Install Docker Engine for your Linux distribution"));
        }
        if (r.output().contains("permission denied")) {
            return List.of(CheckResult.error(id(), TITLE, "Your user may not talk to the Docker daemon",
                    "Add your user to the docker group: sudo usermod -aG docker $USER, then log out and in again"));
        }
        return List.of(CheckResult.error(id(), TITLE, "Docker is not running: " + r.firstLine(),
                "Start Docker: sudo systemctl start docker"));
    }
}
