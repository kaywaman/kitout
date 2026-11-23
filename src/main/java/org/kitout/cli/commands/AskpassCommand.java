package org.kitout.cli.commands;

import org.kitout.application.git.CredentialService;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Called by git through the askpass script, never by people. Prints exactly the answer on stdout
 * (no redaction, no extra text: git reads it) and exits 1 if there is none.
 */
@CommandLine.Command(name = "askpass", hidden = true, description = "Internal: answers git's credential prompt.")
public class AskpassCommand implements Callable<Integer> {

    private static final Pattern URL_IN_PROMPT = Pattern.compile("'(https?://[^']+)'");

    @CommandLine.Parameters(arity = "0..1", paramLabel = "PROMPT")
    String prompt;

    @Inject
    CredentialService credentials;

    @Override
    public Integer call() {
        Optional<String> answer = host().flatMap(h -> credentials.askpassAnswer(prompt, h));
        if (answer.isEmpty()) {
            return 1;
        }
        System.out.println(answer.get());
        return 0;
    }

    /** From kitout's environment variable, or the URL in git's prompt ("Password for 'https://gitlab...': "). */
    private Optional<String> host() {
        String fromEnv = System.getenv("KITOUT_ASKPASS_HOST");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Optional.of(fromEnv);
        }
        if (prompt == null) {
            return Optional.empty();
        }
        Matcher m = URL_IN_PROMPT.matcher(prompt);
        if (!m.find()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(URI.create(m.group(1)).getHost());
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
