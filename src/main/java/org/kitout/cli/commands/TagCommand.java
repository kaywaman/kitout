package org.kitout.cli.commands;

import org.kitout.application.registry.RegistryService;
import org.kitout.application.registry.RegistryService.TagList;
import org.kitout.cli.Console;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.util.concurrent.Callable;

@CommandLine.Command(name = "tag",
        description = {"Show or choose the image tag a service runs as 'Registry image'.",
                "Without TAG: list the tags in the registry (newest-looking first; 'latest' is never offered)."})
public class TagCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", paramLabel = "SERVICE")
    String service;

    @CommandLine.Parameters(index = "1", arity = "0..1", paramLabel = "TAG")
    String tag;

    @Inject
    RegistryService registry;

    @Override
    public Integer call() {
        if (tag != null) {
            registry.chooseTag(service, tag);
            Console.out(service + " runs " + tag + " from its next start.");
            return Console.OK;
        }
        TagList list = registry.tags(service);
        Console.out(list.repository() + (list.current() == null ? "  (no tag chosen yet)" : "  (now: " + list.current() + ")"));
        list.tags().stream().limit(50).forEach(t -> Console.out((t.equals(list.current()) ? "* " : "  ") + t));
        if (list.tags().size() > 50) {
            Console.out("  ... " + (list.tags().size() - 50) + " more");
        }
        Console.out("Choose one with: kitout tag " + service + " <tag>");
        return Console.OK;
    }
}
