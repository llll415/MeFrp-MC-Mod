package com.mefrp.mc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

public class MeFrpConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile Path gameDirectory;

    private volatile String token = "";
    private volatile String tunnelName = MeFrpConstants.TUNNEL_NAME;
    private volatile boolean loadFailed;

    private static MeFrpConfig instance;

    public static synchronized void init(Path gameDirectory) {
        MeFrpConfig.gameDirectory = gameDirectory;
        instance = null;
    }

    public static synchronized MeFrpConfig getInstance() {
        MeFrpConfig current = instance;
        if (current == null) {
            current = new MeFrpConfig();
            current.load();
            instance = current;
        }
        return current;
    }

    public synchronized void load() {
        loadFailed = false;
        File configFile = getConfigFile();
        if (!configFile.exists()) {
            if (!save()) {
                logFailure("failed to create default config");
            }
            return;
        }

        try (Reader reader = Files.newBufferedReader(configFile.toPath(), StandardCharsets.UTF_8)) {
            JsonReader jsonReader = new JsonReader(reader);
            JsonElement root = new JsonParser().parse(jsonReader);
            if (root == null || !root.isJsonObject() || jsonReader.peek() != JsonToken.END_DOCUMENT) {
                markLoadFailed(null);
                return;
            }
            JsonObject json = root.getAsJsonObject();
            String loadedToken = token;
            String loadedTunnelName = tunnelName;
            if (json.has("token")) {
                loadedToken = readString(json, "token");
            }
            if (json.has("tunnelName")) {
                loadedTunnelName = readString(json, "tunnelName");
                if (loadedTunnelName.trim().isEmpty()) {
                    loadedTunnelName = MeFrpConstants.TUNNEL_NAME;
                }
            }
            token = loadedToken;
            tunnelName = loadedTunnelName;
        } catch (IOException | JsonParseException | IllegalStateException | UnsupportedOperationException
                 | InvalidConfigException e) {
            markLoadFailed(e);
        }
    }

    public synchronized boolean save() {
        File configFile = getConfigFile();
        File parent = configFile.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())) {
            logFailure("failed to create config directory");
            return false;
        }

        JsonObject json = new JsonObject();
        json.addProperty("token", token);
        json.addProperty("tunnelName", tunnelName);

        File tempFile = new File(parent, configFile.getName() + ".tmp");
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(tempFile), StandardCharsets.UTF_8)) {
            GSON.toJson(json, writer);
        } catch (IOException e) {
            logFailure("failed to write config: " + e.getClass().getSimpleName());
            deleteQuietly(tempFile);
            return false;
        }

        try {
            moveIntoPlace(tempFile, configFile);
            return true;
        } catch (IOException e) {
            logFailure("failed to replace config: " + e.getClass().getSimpleName());
            deleteQuietly(tempFile);
            return false;
        }
    }

    public String getToken() {
        return token;
    }

    public synchronized boolean setToken(String token) {
        String previous = this.token;
        this.token = token;
        try {
            if (save()) {
                return true;
            }
        } catch (RuntimeException e) {
            this.token = previous;
            throw e;
        }
        this.token = previous;
        return false;
    }

    public String getTunnelName() {
        return tunnelName;
    }

    public boolean hasToken() {
        return token != null && !token.trim().isEmpty();
    }

    public synchronized boolean consumeLoadFailure() {
        if (!loadFailed) {
            return false;
        }
        loadFailed = false;
        return true;
    }

    private String readString(JsonObject json, String key) throws InvalidConfigException {
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new InvalidConfigException();
        }
        return element.getAsString();
    }

    private void markLoadFailed(Exception error) {
        loadFailed = true;
        logFailure("failed to read config: " + (error == null ? "invalid content" : error.getClass().getSimpleName()));
    }

    private void moveIntoPlace(File tempFile, File configFile) throws IOException {
        try {
            Files.move(tempFile.toPath(), configFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tempFile.toPath(), configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteQuietly(File file) {
        if (file.exists() && !file.delete()) {
            logFailure("failed to delete temporary config");
        }
    }

    private void logFailure(String message) {
        System.err.println("[MeFrp] " + message);
    }

    private File getConfigFile() {
        Path base = gameDirectory == null ? Paths.get(".") : gameDirectory;
        return base.resolve("config").resolve("mefrp.json").toFile();
    }

    private static final class InvalidConfigException extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
