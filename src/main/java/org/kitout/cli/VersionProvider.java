package org.kitout.cli;

import org.eclipse.microprofile.config.ConfigProvider;
import picocli.CommandLine.IVersionProvider;

public class VersionProvider implements IVersionProvider {

    @Override
    public String[] getVersion() {
        String version = ConfigProvider.getConfig().getOptionalValue("quarkus.application.version", String.class).orElse("dev");
        return new String[] {"kitout " + version};
    }
}
