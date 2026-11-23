package org.kitout.cli;

import org.kitout.cli.commands.AskpassCommand;
import org.kitout.cli.commands.CheckCommand;
import org.kitout.cli.commands.TeamCommand;
import org.kitout.cli.commands.BuildCommand;
import org.kitout.cli.commands.CleanRemotesCommand;
import org.kitout.cli.commands.ComposeCommand;
import org.kitout.cli.commands.PullCommand;
import org.kitout.cli.commands.RegistryCommand;
import org.kitout.cli.commands.ResetCommand;
import org.kitout.cli.commands.TagCommand;
import org.kitout.cli.commands.CloneCommand;
import org.kitout.cli.commands.DoctorCommand;
import org.kitout.cli.commands.DownCommand;
import org.kitout.cli.commands.EnvCommand;
import org.kitout.cli.commands.LogsCommand;
import org.kitout.cli.commands.ModeCommand;
import org.kitout.cli.commands.RemoveCommand;
import org.kitout.cli.commands.ScanCommand;
import org.kitout.cli.commands.ServeCommand;
import org.kitout.cli.commands.ServiceCommands;
import org.kitout.cli.commands.StatusCommand;
import org.kitout.cli.commands.TokenCommand;
import org.kitout.cli.commands.OptionsCommand;
import org.kitout.cli.commands.UpCommand;
import picocli.CommandLine.Command;

/** {@code kitout}: the root command. Each subcommand is a thin client of the same core as the UI. */
@Command(name = "kitout", mixinStandardHelpOptions = true, versionProvider = VersionProvider.class,
        description = "Gets your Quarkus microservice environment running, so you can work on the one service you change.",
        subcommands = {
                ServeCommand.class,
                ScanCommand.class,
                DoctorCommand.class,
                UpCommand.class,
                DownCommand.class,
                StatusCommand.class,
                ServiceCommands.Start.class,
                ServiceCommands.Stop.class,
                ServiceCommands.Restart.class,
                LogsCommand.class,
                ModeCommand.class,
                OptionsCommand.class,
                RemoveCommand.class,
                PullCommand.class,
                ComposeCommand.class,
                TagCommand.class,
                RegistryCommand.class,
                EnvCommand.class,
                TeamCommand.class,
                CheckCommand.class,
                ResetCommand.class,
                BuildCommand.class,
                CloneCommand.class,
                CleanRemotesCommand.class,
                TokenCommand.class,
                AskpassCommand.class
        })
public class KitoutCommand {
}
