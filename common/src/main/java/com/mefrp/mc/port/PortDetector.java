package com.mefrp.mc.port;

import com.mefrp.mc.platform.MeFrpPlatform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;
import java.util.Properties;

public class PortDetector {
    private final MeFrpPlatform platform;

    public PortDetector(MeFrpPlatform platform) {
        this.platform = platform;
    }

    public boolean acceptChatMessage(String message) {
        return false;
    }

    public void markLanOpened() {
    }

    public OptionalInt detect(Integer explicitPort) {
        if (explicitPort != null) {
            return validPort(explicitPort) ? OptionalInt.of(explicitPort) : OptionalInt.empty();
        }

        if (platform.isDedicatedServer()) {
            OptionalInt serverPort = readServerPropertiesPort();
            if (serverPort.isPresent()) {
                return serverPort;
            }
        }

        if (platform.isClient()) {
            int apiPort = platform.getPublishedLanPort();
            if (validPort(apiPort)) {
                return OptionalInt.of(apiPort);
            }
        }

        return OptionalInt.empty();
    }

    private OptionalInt readServerPropertiesPort() {
        Path propertiesPath = platform.gameDirectory().resolve("server.properties");
        if (!Files.exists(propertiesPath)) {
            return OptionalInt.empty();
        }
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(propertiesPath)) {
            properties.load(reader);
            String value = properties.getProperty("server-port");
            if (value == null || value.isBlank()) {
                return OptionalInt.empty();
            }
            int port = Integer.parseInt(value.trim());
            return validPort(port) ? OptionalInt.of(port) : OptionalInt.empty();
        } catch (IOException | NumberFormatException ignored) {
            return OptionalInt.empty();
        }
    }

    private boolean validPort(int port) {
        return port >= 1 && port <= 65535;
    }
}
