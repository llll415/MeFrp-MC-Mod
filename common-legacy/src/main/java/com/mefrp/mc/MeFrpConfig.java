package com.mefrp.mc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.mefrp.mc.security.ConfigCrypto;
import com.mefrp.mc.security.ConfigCryptoException;
import com.mefrp.mc.security.DefaultMachineIdProvider;
import com.mefrp.mc.security.EncryptedToken;
import com.mefrp.mc.security.MachineIdException;
import com.mefrp.mc.security.MachineIdProvider;

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
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MeFrpConfig {
    public enum SaveResult {
        SUCCESS,
        MACHINE_ID_UNAVAILABLE,
        CONFIG_READ_ONLY,
        IO_FAILURE
    }

    private enum VersionState {
        OLDER,
        SAME,
        NEWER,
        UNKNOWN
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    // Lock order is INIT_LOCK -> instance lock. Instance methods never acquire
    // INIT_LOCK, so init/reset and config reads cannot deadlock each other.
    private static final Object INIT_LOCK = new Object();
    private static volatile Path gameDirectory;
    private static volatile MachineIdProvider defaultMachineIdProvider = new DefaultMachineIdProvider();
    private static volatile MeFrpConfig instance;

    private final Path baseDirectory;
    private final MachineIdProvider machineIdProvider;

    private volatile String token = "";
    private volatile String tunnelName = MeFrpConstants.TUNNEL_NAME;
    private volatile EncryptedToken encryptedToken;
    private volatile boolean loadFailed;
    private volatile boolean tokenDecryptFailed;
    private volatile boolean readOnlyConfig;
    private final List<String> notifications = new ArrayList<>();

    private MeFrpConfig(Path baseDirectory, MachineIdProvider machineIdProvider) {
        this.baseDirectory = baseDirectory;
        this.machineIdProvider = machineIdProvider;
    }

    public static void init(Path gameDirectory) {
        synchronized (INIT_LOCK) {
            MeFrpConfig.gameDirectory = gameDirectory;
            instance = null;
        }
    }

    /**
     * Warms the machine-id cache on a daemon thread so the first command does
     * not have to wait for a platform query. Failures are deliberately ignored;
     * the normal save path reports them later.
     */
    public static void prefetchMachineId() {
        final MachineIdProvider provider;
        synchronized (INIT_LOCK) {
            provider = defaultMachineIdProvider;
        }
        Thread thread = new Thread(() -> {
            try {
                provider.getMachineId();
            } catch (MachineIdException | RuntimeException ignored) {
            }
        }, "MeFrp-MC-Mod machine id");
        thread.setDaemon(true);
        thread.start();
    }

    static void setMachineIdProviderForTesting(MachineIdProvider provider) {
        synchronized (INIT_LOCK) {
            defaultMachineIdProvider = provider == null ? new DefaultMachineIdProvider() : provider;
            instance = null;
        }
    }

    public static MeFrpConfig getInstance() {
        MeFrpConfig current = instance;
        if (current == null) {
            synchronized (INIT_LOCK) {
                current = instance;
                if (current == null) {
                    current = new MeFrpConfig(gameDirectory, defaultMachineIdProvider);
                    current.load();
                    instance = current;
                }
            }
        }
        return current;
    }

    /**
     * Rebuilds the config without reading the old file, so a reset does not
     * depend on the machine id and cannot overwrite a future-version config
     * with stale in-memory state.
     */
    public static boolean reset() {
        synchronized (INIT_LOCK) {
            MeFrpConfig current = instance;
            if (current == null) {
                current = new MeFrpConfig(gameDirectory, defaultMachineIdProvider);
                if (!current.resetToDefaults()) {
                    return false;
                }
                instance = current;
                return true;
            }
            return current.resetToDefaults();
        }
    }

    public synchronized void load() {
        loadFailed = false;
        tokenDecryptFailed = false;
        readOnlyConfig = false;
        notifications.clear();
        File configFile = getConfigFile();
        if (!configFile.exists()) {
            if (writeConfigSafely() != SaveResult.SUCCESS) {
                logFailure("failed to create default config");
            }
            return;
        }

        try {
            JsonObject json = readJson(configFile);
            String loadedTunnelName = readTunnelName(json);
            VersionState versionState = compareVersion(readOptionalString(json, "modVersion"));
            if (versionState == VersionState.NEWER || versionState == VersionState.UNKNOWN) {
                markUnsupportedConfig();
                tunnelName = loadedTunnelName;
                return;
            }

            boolean hasPlaintextToken = json.has("token");
            String plaintextToken = hasPlaintextToken ? readString(json, "token") : "";
            JsonObject encryptionJson = null;
            if (json.has("tokenEncryption")) {
                JsonElement element = json.get("tokenEncryption");
                if (element == null || !element.isJsonObject()) {
                    throw new InvalidConfigException();
                }
                encryptionJson = element.getAsJsonObject();
            }
            if (hasPlaintextToken && encryptionJson != null) {
                throw new InvalidConfigException();
            }

            if (encryptionJson != null) {
                EncryptedToken encrypted = EncryptedToken.fromJson(encryptionJson);
                if (!ConfigCrypto.isSupportedAlgorithm(encrypted.getAlgorithm())) {
                    markUnsupportedConfig();
                    tunnelName = loadedTunnelName;
                    return;
                }
                encryptedToken = encrypted;
                tunnelName = loadedTunnelName;
                String machineId;
                try {
                    machineId = requireMachineId();
                } catch (MachineIdException e) {
                    token = "";
                    notifications.add(MeFrpMessages.MACHINE_ID_UNAVAILABLE);
                    return;
                }
                try {
                    token = ConfigCrypto.decrypt(encrypted, machineId);
                } catch (ConfigCryptoException e) {
                    token = "";
                    tokenDecryptFailed = true;
                    notifications.add(MeFrpMessages.TOKEN_DECRYPT_FAILED);
                    notifications.add(MeFrpMessages.RESET_HINT);
                    return;
                }
                if (versionState == VersionState.OLDER) {
                    MeFrpConfig.SaveResult upgradeResult = writeConfigSafely();
                    if (upgradeResult == MeFrpConfig.SaveResult.MACHINE_ID_UNAVAILABLE) {
                        notifications.add(MeFrpMessages.MACHINE_ID_UNAVAILABLE);
                        return;
                    }
                    if (upgradeResult == MeFrpConfig.SaveResult.SUCCESS) {
                        notifications.add(MeFrpMessages.configUpgraded());
                    } else {
                        notifications.add(MeFrpMessages.CONFIG_UPGRADE_FAILED);
                        notifications.add(MeFrpMessages.RESET_HINT);
                    }
                }
                return;
            }

            tunnelName = loadedTunnelName;
            if (hasPlaintextToken && !plaintextToken.isEmpty()) {
                token = plaintextToken;
                encryptedToken = null;
                try {
                    requireMachineId();
                } catch (MachineIdException e) {
                    notifications.add(MeFrpMessages.MACHINE_ID_UNAVAILABLE);
                    notifications.add(MeFrpMessages.LEGACY_TOKEN_MIGRATION_FAILED);
                    return;
                }
                MeFrpConfig.SaveResult saveResult = writeConfigSafely();
                if (saveResult == MeFrpConfig.SaveResult.MACHINE_ID_UNAVAILABLE) {
                    notifications.add(MeFrpMessages.MACHINE_ID_UNAVAILABLE);
                    notifications.add(MeFrpMessages.LEGACY_TOKEN_MIGRATION_FAILED);
                    return;
                }
                if (saveResult == MeFrpConfig.SaveResult.SUCCESS) {
                    if (versionState == VersionState.OLDER) {
                        notifications.add(MeFrpMessages.configUpgraded());
                    }
                    notifications.add(MeFrpMessages.LEGACY_TOKEN_MIGRATED);
                } else {
                    if (versionState == VersionState.OLDER) {
                        notifications.add(MeFrpMessages.CONFIG_UPGRADE_FAILED);
                        notifications.add(MeFrpMessages.RESET_HINT);
                    }
                    notifications.add(MeFrpMessages.LEGACY_TOKEN_MIGRATION_FAILED);
                }
                return;
            }

            token = "";
            encryptedToken = null;
            if (versionState == VersionState.OLDER) {
                if (writeConfigSafely() == SaveResult.SUCCESS) {
                    notifications.add(MeFrpMessages.configUpgraded());
                } else {
                    notifications.add(MeFrpMessages.CONFIG_UPGRADE_FAILED);
                    notifications.add(MeFrpMessages.RESET_HINT);
                }
            }
        } catch (IOException | InvalidConfigException | ConfigCryptoException | RuntimeException e) {
            markLoadFailed();
        }
    }

    public synchronized SaveResult setToken(String token) {
        if (readOnlyConfig) {
            return SaveResult.CONFIG_READ_ONLY;
        }
        String previousToken = this.token;
        EncryptedToken previousEncryptedToken = this.encryptedToken;
        boolean previousLoadFailed = this.loadFailed;
        boolean previousTokenDecryptFailed = this.tokenDecryptFailed;
        this.token = token == null ? "" : token;
        SaveResult result = writeConfigSafely();
        if (result != SaveResult.SUCCESS) {
            this.token = previousToken;
            this.encryptedToken = previousEncryptedToken;
            this.loadFailed = previousLoadFailed;
            this.tokenDecryptFailed = previousTokenDecryptFailed;
            return result;
        }
        this.loadFailed = false;
        this.tokenDecryptFailed = false;
        this.readOnlyConfig = false;
        return SaveResult.SUCCESS;
    }

    public synchronized boolean save() {
        if (readOnlyConfig) {
            return false;
        }
        if ((token == null || token.isEmpty()) && encryptedToken != null) {
            // Never overwrite an encrypted token that could not be decrypted.
            return false;
        }
        return writeConfigSafely() == SaveResult.SUCCESS;
    }

    public synchronized boolean resetToDefaults() {
        String previousToken = token;
        String previousTunnelName = tunnelName;
        EncryptedToken previousEncryptedToken = encryptedToken;
        boolean previousLoadFailed = loadFailed;
        boolean previousTokenDecryptFailed = tokenDecryptFailed;
        boolean previousReadOnlyConfig = readOnlyConfig;
        List<String> previousNotifications = new ArrayList<>(notifications);
        token = "";
        tunnelName = MeFrpConstants.TUNNEL_NAME;
        encryptedToken = null;
        if (writeConfigSafely() != SaveResult.SUCCESS) {
            token = previousToken;
            tunnelName = previousTunnelName;
            encryptedToken = previousEncryptedToken;
            loadFailed = previousLoadFailed;
            tokenDecryptFailed = previousTokenDecryptFailed;
            readOnlyConfig = previousReadOnlyConfig;
            notifications.clear();
            notifications.addAll(previousNotifications);
            return false;
        }
        loadFailed = false;
        tokenDecryptFailed = false;
        readOnlyConfig = false;
        notifications.clear();
        return true;
    }

    public String getToken() {
        return token;
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

    public synchronized List<String> consumeNotifications() {
        if (notifications.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> copy = new ArrayList<>(notifications);
        notifications.clear();
        return copy;
    }

    private JsonObject readJson(File configFile) throws IOException, InvalidConfigException {
        try (Reader reader = Files.newBufferedReader(configFile.toPath(), StandardCharsets.UTF_8)) {
            JsonReader jsonReader = new JsonReader(reader);
            jsonReader.setLenient(false);
            JsonElement root = GSON.getAdapter(JsonElement.class).read(jsonReader);
            if (root == null || !root.isJsonObject() || jsonReader.peek() != JsonToken.END_DOCUMENT) {
                throw new InvalidConfigException();
            }
            return root.getAsJsonObject();
        }
    }

    private String readTunnelName(JsonObject json) throws InvalidConfigException {
        if (!json.has("tunnelName")) {
            return MeFrpConstants.TUNNEL_NAME;
        }
        String value = readString(json, "tunnelName");
        return value.trim().isEmpty() ? MeFrpConstants.TUNNEL_NAME : value;
    }

    private String readOptionalString(JsonObject json, String key) throws InvalidConfigException {
        return json.has(key) ? readString(json, key) : "";
    }

    private String readString(JsonObject json, String key) throws InvalidConfigException {
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new InvalidConfigException();
        }
        return element.getAsString();
    }

    private VersionState compareVersion(String existing) {
        String trimmed = existing == null ? "" : existing.trim();
        if (trimmed.isEmpty()) {
            return VersionState.OLDER;
        }
        if (trimmed.equals(MeFrpConstants.VERSION)) {
            return VersionState.SAME;
        }
        int[] existingParts = parseVersion(trimmed);
        int[] currentParts = parseVersion(MeFrpConstants.VERSION);
        if (existingParts == null || currentParts == null) {
            return VersionState.UNKNOWN;
        }
        int length = Math.max(existingParts.length, currentParts.length);
        for (int i = 0; i < length; i++) {
            int existingPart = i < existingParts.length ? existingParts[i] : 0;
            int currentPart = i < currentParts.length ? currentParts[i] : 0;
            if (existingPart < currentPart) {
                return VersionState.OLDER;
            }
            if (existingPart > currentPart) {
                return VersionState.NEWER;
            }
        }
        return VersionState.SAME;
    }

    private int[] parseVersion(String value) {
        String[] parts = value.split("\\.");
        int[] parsed = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) {
                return null;
            }
            try {
                parsed[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return parsed;
    }

    private void markLoadFailed() {
        loadFailed = true;
        readOnlyConfig = false;
        token = "";
        tunnelName = MeFrpConstants.TUNNEL_NAME;
        encryptedToken = null;
        notifications.add(MeFrpMessages.CONFIG_READ_FAILED);
        notifications.add(MeFrpMessages.RESET_HINT);
        logFailure("failed to read config: invalid content");
    }

    private void markUnsupportedConfig() {
        loadFailed = true;
        readOnlyConfig = true;
        token = "";
        encryptedToken = null;
        notifications.add(MeFrpMessages.CONFIG_READ_FAILED);
        logFailure("failed to read config: unsupported version or algorithm");
    }

    private SaveResult writeConfigSafely() {
        try {
            return writeConfig();
        } catch (RuntimeException e) {
            logFailure("failed to write config: " + e.getClass().getSimpleName());
            return SaveResult.IO_FAILURE;
        }
    }

    private SaveResult writeConfig() {
        File configFile = getConfigFile();
        File parent = configFile.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())) {
            logFailure("failed to create config directory");
            return SaveResult.IO_FAILURE;
        }

        JsonObject json = new JsonObject();
        json.addProperty("modVersion", MeFrpConstants.VERSION);
        json.addProperty("tunnelName", tunnelName);

        EncryptedToken newEncryptedToken = null;
        if (token != null && !token.isEmpty()) {
            String machineId;
            try {
                machineId = requireMachineId();
            } catch (MachineIdException e) {
                return SaveResult.MACHINE_ID_UNAVAILABLE;
            }
            try {
                newEncryptedToken = ConfigCrypto.encrypt(token, machineId);
            } catch (GeneralSecurityException e) {
                logFailure("failed to encrypt token: " + e.getClass().getSimpleName());
                return SaveResult.IO_FAILURE;
            }
            json.add("tokenEncryption", newEncryptedToken.toJson());
        }

        File tempFile = new File(parent, configFile.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(tempFile)) {
            Writer writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
            GSON.toJson(json, writer);
            writer.flush();
            output.getFD().sync();
        } catch (IOException e) {
            logFailure("failed to write config: " + e.getClass().getSimpleName());
            deleteQuietly(tempFile);
            return SaveResult.IO_FAILURE;
        }

        try {
            moveIntoPlace(tempFile, configFile);
        } catch (IOException e) {
            logFailure("failed to replace config: " + e.getClass().getSimpleName());
            deleteQuietly(tempFile);
            return SaveResult.IO_FAILURE;
        }
        encryptedToken = newEncryptedToken;
        return SaveResult.SUCCESS;
    }

    private String requireMachineId() throws MachineIdException {
        String machineId = machineIdProvider.getMachineId();
        if (machineId == null || machineId.trim().isEmpty()) {
            throw new MachineIdException("machine id is empty");
        }
        return machineId;
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
        Path base = baseDirectory == null ? Paths.get(".") : baseDirectory;
        return base.resolve("config").resolve("mefrp.json").toFile();
    }

    private static final class InvalidConfigException extends Exception {
        private static final long serialVersionUID = 1L;
    }
}
