package org.kitout.api;

import org.kitout.api.dto.BuildView;
import org.kitout.api.dto.CheckView;
import org.kitout.api.dto.CloneCandidateView;
import org.kitout.api.dto.CloneItemView;
import org.kitout.api.dto.CloneJobView;
import org.kitout.api.dto.DoctorReportView;
import org.kitout.api.dto.ExplanationView;
import org.kitout.api.dto.GroupStartView;
import org.kitout.api.dto.GroupView;
import org.kitout.api.dto.ServiceView;
import org.kitout.api.dto.SettingsView;
import org.kitout.application.build.BuildJob;
import org.kitout.application.clone.CloneService;
import org.kitout.application.config.KitoutConfig;
import org.kitout.application.config.KitoutConfig.ServiceSettings;
import org.kitout.application.doctor.DoctorService.Report;
import org.kitout.application.run.GroupRunner.Progress;
import org.kitout.application.run.ServiceStatus;
import org.kitout.domain.doctor.CheckResult;
import org.kitout.domain.doctor.Severity;
import org.kitout.domain.group.Group;
import org.kitout.domain.identity.RepoIdentity;
import org.kitout.domain.project.Project;
import org.kitout.domain.run.ImagePath;

import java.util.List;

/**
 * Turns application objects into REST views. The API never exposes application or domain types
 * directly, so they can change without breaking the UI, and nothing leaks by accident.
 */
public final class ApiMapper {

    private ApiMapper() {
    }

    public static ServiceView service(Project p, ServiceStatus s, ServiceSettings settings, String image, String imageRepository) {
        return new ServiceView(p.id(), p.name(), p.identity().map(RepoIdentity::fullPath).orElse(null),
                p.path().toString(), s.state().name(), s.mode().name(), s.port(), p.devPortAdvisory(), s.detail(),
                s.startedAt().orElse(null), p.looksLikeBff(), image, settings.runtimeProfile(), settings.startTimeoutSeconds(),
                s.health().name(), p.health().path(), p.health().port(), s.startupTime().map(java.time.Duration::toMillis).orElse(null),
                imageRepository, settings.imageTag(), settings.environment().size(), p.version().orElse(null),
                settings.volumes().stream().map(v -> new org.kitout.api.dto.VolumeView(v.hostPath(), v.containerPath(), v.readOnly())).toList(),
                settings.dockerArgs(), settings.checksHealth(p.hasHealthEndpoint()),
                p.type().id(), p.type().label(), p.type().runModes().stream().map(Enum::name).toList(),
                p.type().buildable(), p.type().nativeBuild(), p.scripts(),
                p.type() == org.kitout.domain.project.ProjectType.NODE && !p.scripts().isEmpty()
                        ? org.kitout.application.run.ServiceSupervisor.scriptOf(p, settings) : null,
                settings.port(), p.devPort() > 0);
    }

    public static GroupView group(Group g) {
        return new GroupView(g.name(), g.members(), g.bffs(), g.sequentialStart(), g.suggested());
    }

    public static Group group(GroupView v, String name) {
        return new Group(name, v.members(), v.bffs() == null ? List.of() : v.bffs().stream().filter(b -> b != null && !b.isBlank()).toList(),
                v.sequentialStart(), false);
    }

    public static GroupStartView progress(Progress p) {
        return new GroupStartView(p.group(), p.startedAt(), p.messages(), p.finished());
    }

    public static CheckView check(CheckResult r) {
        return new CheckView(r.checkId(), r.title(), r.severity().name(), r.detail(), r.fix().orElse(null),
                r.advisory(), r.action().orElse(null), r.items());
    }

    public static DoctorReportView report(Report r) {
        return new DoctorReportView(r.results().stream().map(ApiMapper::check).toList(),
                r.count(Severity.ERROR), r.count(Severity.WARNING), r.hasErrors());
    }

    public static BuildView build(BuildJob j, int queuePosition) {
        return build(j, queuePosition, java.util.Optional.empty(), java.util.Optional.empty());
    }

    /**
     * @param notRemovable why the attempt cannot be removed now; empty if it can
     * @param outputNote   for a succeeded attempt whose output is no longer there: why ("replaced by a later build")
     */
    public static BuildView build(BuildJob j, int queuePosition, java.util.Optional<String> notRemovable, java.util.Optional<String> outputNote) {
        return new BuildView(j.id(), j.request().serviceId(), j.serviceName(), j.request().profile(), j.request().mode().name(),
                j.request().skipTests(), j.status().name(), queuePosition, j.waitingFor().orElse(null), j.createdAt(),
                j.startedAt().orElse(null), j.finishedAt().orElse(null),
                j.explanation().map(e -> new ExplanationView(e.cause(), e.fix())).orElse(null), j.request().isProdProfile(),
                j.version().orElse(null), notRemovable.isEmpty(), notRemovable.orElse(null), outputNote.orElse(null));
    }

    public static CloneCandidateView candidate(CloneService.Candidate c) {
        return new CloneCandidateView(c.name(), c.fullPath(), c.defaultBranch(), c.target().toString(), c.alreadyLocal());
    }

    public static CloneJobView cloneJob(CloneService.Job j) {
        return new CloneJobView(j.id(), j.items().stream()
                .map(i -> new CloneItemView(i.fullPath(), i.light(), i.status().name(), i.message())).toList(), j.finished());
    }

    public static SettingsView settings(KitoutConfig c) {
        return new SettingsView(c.workspaceRoots(),
                new SettingsView.GitLab(c.gitlab().host(), c.gitlab().cloneProtocol(), c.gitlab().workspaceRoot(), c.gitlab().registryHost(),
                        c.gitlab().imagePath(), c.gitlab().provider(), c.gitlab().kind().name()),
                new SettingsView.Build(c.build().nativeContainerBuild(), c.build().nativeMinFreeMemoryMb(), c.build().profiles()),
                new SettingsView.Doctor(c.doctor().embeddedCredentialsAsError()),
                new SettingsView.Docker(c.docker().composeDirs(), c.docker().composeFiles(), c.docker().keepImageVersions()));
    }

    public static KitoutConfig apply(KitoutConfig c, SettingsView v) {
        KitoutConfig result = c;
        if (v.workspaceRoots() != null) {
            result = result.withWorkspaceRoots(v.workspaceRoots().stream().map(String::strip).filter(s -> !s.isEmpty()).toList());
        }
        if (v.gitlab() != null) {
            String imagePath = v.gitlab().imagePath() == null || v.gitlab().imagePath().isBlank()
                    ? KitoutConfig.GitLabSettings.DEFAULT_IMAGE_PATH : v.gitlab().imagePath().strip();
            ImagePath.validate(imagePath);
            if (!org.kitout.domain.git.GitHost.valid(v.gitlab().provider())) {
                throw new IllegalArgumentException("The git host kind is gitlab, github, git or empty (guess from the host), not '"
                        + v.gitlab().provider() + "'");
            }
            result = result.withGitlab(new KitoutConfig.GitLabSettings(v.gitlab().host(), v.gitlab().cloneProtocol(),
                    v.gitlab().workspaceRoot(), v.gitlab().registryHost(), imagePath, v.gitlab().provider()));
        }
        if (v.build() != null) {
            if (v.build().profiles() != null) {
                for (String p : v.build().profiles()) {
                    if (p == null || !p.strip().matches("[a-zA-Z0-9_-]{1,32}")) {
                        throw new IllegalArgumentException("A build profile is up to 32 letters, digits, '-' or '_', not '" + p + "'");
                    }
                }
            }
            result = result.withBuild(new KitoutConfig.BuildSettings(v.build().nativeContainerBuild(),
                    v.build().nativeMinFreeMemoryMb(), v.build().profiles()));
        }
        if (v.doctor() != null) {
            result = result.withDoctor(new KitoutConfig.DoctorSettings(v.doctor().embeddedCredentialsAsError()));
        }
        if (v.docker() != null) {
            List<String> files = v.docker().composeFiles() != null ? v.docker().composeFiles() : c.docker().composeFiles();
            Integer keep = v.docker().keepImageVersions() != null ? v.docker().keepImageVersions() : c.docker().keepImageVersions();
            if (keep < 1 || keep > KitoutConfig.DockerSettings.MAX_KEEP_IMAGE_VERSIONS) {
                throw new IllegalArgumentException("Keep between 1 and " + KitoutConfig.DockerSettings.MAX_KEEP_IMAGE_VERSIONS + " versions of each image");
            }
            result = result.withDocker(new KitoutConfig.DockerSettings(v.docker().composeDirs(), files, keep));
        }
        return result;
    }
}
