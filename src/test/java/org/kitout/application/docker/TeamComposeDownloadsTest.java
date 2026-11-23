package org.kitout.application.docker;

import org.kitout.application.KitoutPaths;
import org.kitout.application.config.KitoutConfig;
import org.kitout.application.git.CredentialService;
import org.kitout.application.git.GitCli;
import org.kitout.application.port.CommandRunner;
import org.kitout.application.port.GitLabApi;
import org.kitout.application.team.TeamService;
import org.kitout.domain.docker.ComposeFile;
import org.kitout.domain.team.TeamSetup;
import org.kitout.testsupport.Fakes;
import org.kitout.testsupport.MiniJson;
import org.kitout.testsupport.TeamFixtures;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamComposeDownloadsTest {

    private static final String HOST = "gitlab.example.com";

    /** Serves one file; counts the calls. */
    static final class OneFile implements GitLabApi {
        String text;
        int status = 200;
        final List<String> calls = new ArrayList<>();

        OneFile(String text) {
            this.text = text;
        }

        @Override
        public List<Project> groupProjects(String host, String groupPath, String token) {
            return List.of();
        }

        @Override
        public TokenInfo tokenInfo(String host, String token) {
            throw new UnsupportedOperationException();
        }

        @Override
        public FileContent file(String host, String projectPath, String filePath, String ref, String token) {
            calls.add(host + " " + projectPath + " " + filePath + "@" + ref + " " + token);
            if (status != 200) {
                throw new GitLabException(status, "404 File Not Found");
            }
            return new FileContent(text, "abc123");
        }

        @Override
        public void ping(String host) {
        }
    }

    private record Setup(ComposeService compose, OneFile gitlab, Path work) {
    }

    private static Setup setup(String composeText) throws Exception {
        Path work = Files.createTempDirectory("kitout-work");
        TeamSetup team = TeamFixtures.filled();
        Fakes.InMemoryConfig config = new Fakes.InMemoryConfig(KitoutConfig.defaults().withGitlab(
                new KitoutConfig.GitLabSettings(HOST, "https", work.toString(), "", "{project}")));
        KitoutPaths paths = new KitoutPaths(Map.of("KITOUT_HOME", work.resolve(".kitout").toString()));
        Fakes.InMemorySecrets secrets = new Fakes.InMemorySecrets();
        secrets.store(HOST, "glpat-secret");
        CommandRunner noHelper = c -> new CommandRunner.Result(1, "");
        CredentialService credentials = new CredentialService(new GitCli(noHelper), secrets);
        TeamService teamService = new TeamService(config, null, credentials, MiniJson.reader(), paths, "1.0.0", Optional.empty());
        OneFile gitlab = new OneFile(composeText);
        TeamComposeDownloads downloads = new TeamComposeDownloads(gitlab, credentials, config, teamService, paths);
        ComposeService compose = new ComposeService(config, paths, null, null, null, null, () -> Optional.of(team), null, downloads);
        return new Setup(compose, gitlab, work);
    }

    @Test
    void aComposeFileOfARepositoryThatIsNotClonedIsReadFromGitLab() throws Exception {
        Setup s = setup("services:\n  mongo:\n    image: mongo:7\n    volumes:\n      - mongo-data:/data/db\nvolumes:\n  mongo-data:\n");

        ComposeService.Environment dev = s.compose().environments().get(0);

        assertEquals(List.of(), dev.missing(), "nothing to clone: the file came from GitLab");
        assertTrue(dev.downloaded());
        assertTrue(Files.readString(dev.file()).contains("mongo:7"));
        assertEquals("compose", dev.file().getParent().getFileName().toString(),
                "same folder name as in a clone, so compose's project name (and the volumes) stay the same");
        assertEquals(List.of(HOST + " acme/platform/infrastructure compose/docker-compose-dev.yml@HEAD glpat-secret"), s.gitlab().calls,
                "another repository than the team file's: its default branch");

        s.compose().environments();
        assertEquals(1, s.gitlab().calls.size(), "the Docker page asks often; the copy is used until the team file is read again");
    }

    @Test
    void aComposeFileThatUsesFilesNextToItAsksForAClone() throws Exception {
        Setup s = setup("services:\n  mongo:\n    image: mongo:7\n    volumes:\n      - ./init:/docker-entrypoint-initdb.d\n");

        ComposeService.Environment dev = s.compose().environments().get(0);

        assertEquals(1, dev.missing().size());
        assertTrue(dev.missing().get(0).contains("./init") && dev.missing().get(0).contains("clone acme/platform/infrastructure"),
                dev.missing().toString());
    }

    @Test
    void aFileGitLabDoesNotHaveIsMissingAndNotAskedForOnEveryCall() throws Exception {
        Setup s = setup("services: {}\n");
        s.gitlab().status = 404;

        ComposeService.Environment dev = s.compose().environments().get(0);
        s.compose().environments();

        assertFalse(dev.downloaded());
        assertTrue(dev.missing().get(0).contains("check \"path\""), dev.missing().toString());
        assertEquals(1, s.gitlab().calls.size(), "a failure waits a little before the next try");
    }

    @Test
    void aCloneWinsOverTheDownload() throws Exception {
        Setup s = setup("services: {}\n");
        Path file = s.work().resolve("acme/platform/infrastructure/compose/docker-compose-dev.yml");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "services: {}\n");

        ComposeService.Environment dev = s.compose().environments().get(0);

        assertEquals(file, dev.file());
        assertFalse(dev.downloaded());
        assertEquals(List.of(), s.gitlab().calls);
    }

    @Test
    void findsWhatAComposeFileNamesNextToItself() {
        assertEquals(List.of(), ComposeFile.localReferences(
                "services:\n  mongo:\n    image: mongo:7\n    ports:\n      - \"27017:27017\"\n    volumes:\n      - mongo-data:/data/db\n      - /opt/x:/x\n"));
        assertEquals(List.of("./data", "../conf/app.env", ".", "mongo.env"), ComposeFile.localReferences(
                "services:\n  a:\n    volumes:\n      - ./data:/data\n    env_file:\n      - ../conf/app.env\n  b:\n    build: .\n  c:\n    env_file: mongo.env\n"));
        assertEquals(List.of("./init"), ComposeFile.localReferences("services:\n  a:\n    volumes:\n      - type: bind\n        source: ./init\n"));
    }
}
