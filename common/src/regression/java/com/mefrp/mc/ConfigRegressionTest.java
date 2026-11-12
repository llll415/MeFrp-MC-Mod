package com.mefrp.mc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mefrp.mc.command.MeFrpCommandHandler;
import com.mefrp.mc.frpc.MefrpcManager;
import com.mefrp.mc.platform.MeFrpPlatform;
import com.mefrp.mc.security.MachineIdException;
import com.mefrp.mc.security.MachineIdProvider;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Dependency-free regression harness. Run with :common:regressionTest and
 * :common-legacy:regressionTest. The legacy task deliberately runs with Gson
 * 2.8.0 to catch accidental use of newer Gson APIs.
 */
public final class ConfigRegressionTest {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static int passed;
    private static int failed;

    private ConfigRegressionTest() {
    }

    public static void main(String[] args) {
        run("save randomizes salt and iv", () -> {
            Path dir = newTempDir();
            try {
                init(dir, machine("machine-A"));
                MeFrpConfig config = MeFrpConfig.getInstance();
                check(config.setToken("token-1") == MeFrpConfig.SaveResult.SUCCESS, "first save");
                JsonObject first = readJson(configFile(dir));
                check(!first.has("token"), "plaintext token must not be written");
                check(!first.has("configVersion"), "configVersion must not be added");
                checkEquals(MeFrpConstants.TUNNEL_NAME, first.get("tunnelName").getAsString(),
                        "tunnel name stays plaintext");
                JsonObject firstEncryption = first.getAsJsonObject("tokenEncryption");
                check(firstEncryption != null, "encrypted token object");
                checkEquals(MeFrpConstants.VERSION, first.get("modVersion").getAsString(), "modVersion");

                check(config.setToken("token-1") == MeFrpConfig.SaveResult.SUCCESS, "second save");
                JsonObject secondEncryption = readJson(configFile(dir)).getAsJsonObject("tokenEncryption");
                checkNotEquals(firstEncryption.get("salt").getAsString(),
                        secondEncryption.get("salt").getAsString(), "salt must change per save");
                checkNotEquals(firstEncryption.get("iv").getAsString(),
                        secondEncryption.get("iv").getAsString(), "iv must change per save");
                checkNotEquals(firstEncryption.get("ciphertext").getAsString(),
                        secondEncryption.get("ciphertext").getAsString(), "ciphertext must change per save");
            } finally {
                deleteRecursively(dir);
            }
        });

        run("same-machine roundtrip", () -> {
            Path dir = newTempDir();
            try {
                init(dir, machine("machine-A"));
                check(MeFrpConfig.getInstance().setToken("roundtrip-token") == MeFrpConfig.SaveResult.SUCCESS,
                        "save token");
                init(dir, machine("machine-A"));
                MeFrpConfig reloaded = MeFrpConfig.getInstance();
                checkEquals("roundtrip-token", reloaded.getToken(), "token after reload");
                check(reloaded.hasToken(), "hasToken after reload");
                check(reloaded.consumeNotifications().isEmpty(), "no notifications after clean reload");
            } finally {
                deleteRecursively(dir);
            }
        });

        run("cross-machine decryption fails without exposing ciphertext", () -> {
            Path dir = newTempDir();
            try {
                init(dir, machine("machine-A"));
                check(MeFrpConfig.getInstance().setToken("secret-token") == MeFrpConfig.SaveResult.SUCCESS,
                        "save token");
                init(dir, machine("machine-B"));
                MeFrpConfig reloaded = MeFrpConfig.getInstance();
                check(!reloaded.hasToken(), "hasToken must be false");
                checkEquals("", reloaded.getToken(), "ciphertext must not become the token");
                List<String> messages = reloaded.consumeNotifications();
                check(messages.contains(MeFrpMessages.TOKEN_DECRYPT_FAILED), "decrypt failure message");
                check(messages.contains(MeFrpMessages.RESET_HINT), "reset hint");
            } finally {
                deleteRecursively(dir);
            }
        });

        run("tampered ciphertext fails", () -> {
            Path dir = newTempDir();
            try {
                init(dir, machine("machine-A"));
                check(MeFrpConfig.getInstance().setToken("tamper-token") == MeFrpConfig.SaveResult.SUCCESS,
                        "save token");
                JsonObject json = readJson(configFile(dir));
                JsonObject encryption = json.getAsJsonObject("tokenEncryption");
                encryption.addProperty("ciphertext", flipCiphertext(encryption.get("ciphertext").getAsString()));
                writeJson(configFile(dir), json);

                init(dir, machine("machine-A"));
                MeFrpConfig reloaded = MeFrpConfig.getInstance();
                check(!reloaded.hasToken(), "tampered token must not load");
                check(reloaded.consumeNotifications().contains(MeFrpMessages.TOKEN_DECRYPT_FAILED),
                        "tamper failure message");
            } finally {
                deleteRecursively(dir);
            }
        });

        run("missing machine id blocks token save without plaintext fallback", () -> {
            Path dir = newTempDir();
            try {
                init(dir, failingMachine());
                MeFrpConfig config = MeFrpConfig.getInstance();
                check(config.setToken("no-machine-token") == MeFrpConfig.SaveResult.MACHINE_ID_UNAVAILABLE,
                        "machine id failure result");
                check(!config.hasToken(), "memory token must not change");
                JsonObject json = readJson(configFile(dir));
                check(!json.has("token"), "no plaintext token");
                check(!json.has("tokenEncryption"), "no partial encryption");
            } finally {
                deleteRecursively(dir);
            }
        });

        run("missing machine id on encrypted load is reported without reset hint", () -> {
            Path dir = newTempDir();
            try {
                init(dir, machine("machine-A"));
                check(MeFrpConfig.getInstance().setToken("secret") == MeFrpConfig.SaveResult.SUCCESS,
                        "save token");
                init(dir, failingMachine());
                MeFrpConfig reloaded = MeFrpConfig.getInstance();
                check(!reloaded.hasToken(), "token unavailable");
                List<String> messages = reloaded.consumeNotifications();
                check(messages.contains(MeFrpMessages.MACHINE_ID_UNAVAILABLE), "machine id message");
                check(!messages.contains(MeFrpMessages.RESET_HINT), "machine id failure must not suggest reset");
            } finally {
                deleteRecursively(dir);
            }
        });

        run("legacy plaintext token migrates and reports both changes", () -> {
            Path dir = newTempDir();
            try {
                writeJson(configFile(dir), legacyConfig("legacy-token", "MyTunnel"));
                init(dir, machine("machine-A"));
                MeFrpConfig config = MeFrpConfig.getInstance();
                checkEquals("legacy-token", config.getToken(), "legacy token in memory");
                checkEquals("MyTunnel", config.getTunnelName(), "tunnel name preserved");
                List<String> messages = config.consumeNotifications();
                check(messages.contains(MeFrpMessages.configUpgraded()), "config upgrade message");
                check(messages.contains(MeFrpMessages.LEGACY_TOKEN_MIGRATED), "token migration message");
                JsonObject json = readJson(configFile(dir));
                check(!json.has("token"), "plaintext token removed");
                check(json.has("tokenEncryption"), "encrypted token present");
                checkEquals(MeFrpConstants.VERSION, json.get("modVersion").getAsString(), "version written");
            } finally {
                deleteRecursively(dir);
            }
        });

        run("same-version plaintext token still migrates once", () -> {
            Path dir = newTempDir();
            try {
                JsonObject json = legacyConfig("same-version-token", "Tunnel");
                json.addProperty("modVersion", MeFrpConstants.VERSION);
                writeJson(configFile(dir), json);
                init(dir, machine("machine-A"));
                MeFrpConfig config = MeFrpConfig.getInstance();
                List<String> messages = config.consumeNotifications();
                check(messages.contains(MeFrpMessages.LEGACY_TOKEN_MIGRATED), "migration message");
                check(!messages.contains(MeFrpMessages.configUpgraded()), "no false version upgrade message");
                check(readJson(configFile(dir)).has("tokenEncryption"), "encrypted after migration");
            } finally {
                deleteRecursively(dir);
            }
        });

        run("version-only upgrade does not need a token", () -> {
            Path dir = newTempDir();
            try {
                JsonObject json = new JsonObject();
                json.addProperty("modVersion", "0.0.1");
                json.addProperty("tunnelName", "OldTunnel");
                writeJson(configFile(dir), json);
                init(dir, machine("machine-A"));
                MeFrpConfig config = MeFrpConfig.getInstance();
                List<String> messages = config.consumeNotifications();
                check(messages.contains(MeFrpMessages.configUpgraded()), "upgrade message");
                check(!messages.contains(MeFrpMessages.LEGACY_TOKEN_MIGRATED), "no token migration message");
                checkEquals(MeFrpConstants.VERSION, readJson(configFile(dir)).get("modVersion").getAsString(),
                        "version written");
            } finally {
                deleteRecursively(dir);
            }
        });

        run("legacy migration write failure keeps the plaintext file intact", () -> {
            Path dir = newTempDir();
            try {
                JsonObject json = legacyConfig("legacy-token", "Tunnel");
                writeJson(configFile(dir), json);
                byte[] before = Files.readAllBytes(configFile(dir));
                Files.createDirectory(configFile(dir).resolveSibling("mefrp.json.tmp"));

                init(dir, machine("machine-A"));
                MeFrpConfig config = MeFrpConfig.getInstance();
                checkEquals("legacy-token", config.getToken(), "legacy token stays usable in memory");
                List<String> messages = config.consumeNotifications();
                check(messages.contains(MeFrpMessages.LEGACY_TOKEN_MIGRATION_FAILED), "migration failure message");
                check(messages.contains(MeFrpMessages.CONFIG_UPGRADE_FAILED), "upgrade failure message");
                check(messages.contains(MeFrpMessages.RESET_HINT), "upgrade failure reset hint");
                check(!messages.contains(MeFrpMessages.LEGACY_TOKEN_MIGRATED), "must not claim success");
                check(java.util.Arrays.equals(before, Files.readAllBytes(configFile(dir))),
                        "original plaintext file unchanged");
                JsonObject after = readJson(configFile(dir));
                check(after.has("token"), "original plaintext token remains");
                check(!after.has("tokenEncryption"), "no partial encrypted object");
            } finally {
                deleteRecursively(dir);
            }
        });

        run("legacy migration without machine id leaves the file untouched", () -> {
            Path dir = newTempDir();
            try {
                JsonObject json = legacyConfig("legacy-token", "Tunnel");
                writeJson(configFile(dir), json);
                byte[] before = Files.readAllBytes(configFile(dir));
                init(dir, failingMachine());
                MeFrpConfig config = MeFrpConfig.getInstance();
                checkEquals("legacy-token", config.getToken(), "legacy token stays usable in memory");
                List<String> messages = config.consumeNotifications();
                check(messages.contains(MeFrpMessages.MACHINE_ID_UNAVAILABLE), "machine id message");
                check(messages.contains(MeFrpMessages.LEGACY_TOKEN_MIGRATION_FAILED),
                        "migration failure explains plaintext remains");
                check(!messages.contains(MeFrpMessages.RESET_HINT), "machine id failure must not suggest reset");
                check(java.util.Arrays.equals(before, Files.readAllBytes(configFile(dir))),
                        "original plaintext file unchanged");
            } finally {
                deleteRecursively(dir);
            }
        });

        run("config upgrade write failure appends reset hint", () -> {
            Path dir = newTempDir();
            try {
                JsonObject json = new JsonObject();
                json.addProperty("modVersion", "0.0.1");
                json.addProperty("tunnelName", "OldTunnel");
                writeJson(configFile(dir), json);
                byte[] before = Files.readAllBytes(configFile(dir));
                Files.createDirectory(configFile(dir).resolveSibling("mefrp.json.tmp"));

                init(dir, machine("machine-A"));
                List<String> messages = MeFrpConfig.getInstance().consumeNotifications();
                check(messages.contains(MeFrpMessages.CONFIG_UPGRADE_FAILED), "upgrade failure message");
                check(messages.contains(MeFrpMessages.RESET_HINT), "reset hint");
                check(java.util.Arrays.equals(before, Files.readAllBytes(configFile(dir))),
                        "original file unchanged");
            } finally {
                deleteRecursively(dir);
            }
        });

        run("atomic write failure keeps file and memory unchanged", () -> {
            Path dir = newTempDir();
            try {
                init(dir, machine("machine-A"));
                MeFrpConfig config = MeFrpConfig.getInstance();
                check(config.setToken("old-token") == MeFrpConfig.SaveResult.SUCCESS, "initial save");
                byte[] before = Files.readAllBytes(configFile(dir));
                Files.createDirectory(configFile(dir).resolveSibling("mefrp.json.tmp"));

                check(config.setToken("new-token") == MeFrpConfig.SaveResult.IO_FAILURE, "write failure");
                checkEquals("old-token", config.getToken(), "memory rolled back");
                check(java.util.Arrays.equals(before, Files.readAllBytes(configFile(dir))),
                        "original file unchanged");
            } finally {
                deleteRecursively(dir);
            }
        });

        run("future config version is never overwritten", () -> {
            Path dir = newTempDir();
            try {
                JsonObject json = legacyConfig("future-token", "FutureTunnel");
                json.addProperty("modVersion", "999.0.0");
                writeJson(configFile(dir), json);
                byte[] before = Files.readAllBytes(configFile(dir));
                init(dir, machine("machine-A"));
                MeFrpConfig config = MeFrpConfig.getInstance();
                check(!config.hasToken(), "future token not used");
                check(config.setToken("replacement") == MeFrpConfig.SaveResult.CONFIG_READ_ONLY,
                        "explicit token must not overwrite a future config");
                check(!config.save(), "plain save must not overwrite a future config");
                check(java.util.Arrays.equals(before, Files.readAllBytes(configFile(dir))), "file unchanged");
                List<String> messages = config.consumeNotifications();
                check(messages.contains(MeFrpMessages.CONFIG_READ_FAILED), "read failure message");
                check(!messages.contains(MeFrpMessages.RESET_HINT), "future version must not suggest reset on load");

                TestPlatform platform = new TestPlatform(dir);
                MeFrpCommandHandler handler = new MeFrpCommandHandler(platform,
                        new FakeMefrpcManager(platform, false));
                handler.handleCommand(new String[]{"token", "replacement"});
                check(platform.messages.contains(MeFrpMessages.CONFIG_READ_FAILED), "handler read-only message");
                check(platform.messages.contains(MeFrpMessages.RESET_HINT), "handler points to reset");
                check(java.util.Arrays.equals(before, Files.readAllBytes(configFile(dir))), "handler file unchanged");

                check(MeFrpConfig.reset(), "explicit reset may rebuild a future config");
                JsonObject reset = readJson(configFile(dir));
                checkEquals(MeFrpConstants.VERSION, reset.get("modVersion").getAsString(), "reset version");
                check(!reset.has("tokenEncryption"), "reset removes encrypted token");
            } finally {
                deleteRecursively(dir);
            }
        });

        run("future empty-token config stays read-only", () -> {
            Path dir = newTempDir();
            try {
                JsonObject json = new JsonObject();
                json.addProperty("modVersion", "999.0.0");
                json.addProperty("tunnelName", "FutureEmpty");
                writeJson(configFile(dir), json);
                byte[] before = Files.readAllBytes(configFile(dir));
                init(dir, machine("machine-A"));
                MeFrpConfig config = MeFrpConfig.getInstance();
                check(!config.hasToken(), "future empty config has no token");
                check(config.setToken("replacement") == MeFrpConfig.SaveResult.CONFIG_READ_ONLY,
                        "explicit token must be rejected");
                check(!config.save(), "plain save must not overwrite future empty config");
                check(java.util.Arrays.equals(before, Files.readAllBytes(configFile(dir))), "file unchanged");
            } finally {
                deleteRecursively(dir);
            }
        });

        run("unknown encryption algorithm is never overwritten", () -> {
            Path dir = newTempDir();
            try {
                JsonObject json = new JsonObject();
                json.addProperty("modVersion", MeFrpConstants.VERSION);
                JsonObject encryption = new JsonObject();
                encryption.addProperty("algorithm", "AES-FUTURE");
                encryption.addProperty("salt", Base64.getEncoder().encodeToString(new byte[16]));
                encryption.addProperty("iv", Base64.getEncoder().encodeToString(new byte[12]));
                encryption.addProperty("ciphertext", Base64.getEncoder().encodeToString(new byte[16]));
                json.add("tokenEncryption", encryption);
                writeJson(configFile(dir), json);
                byte[] before = Files.readAllBytes(configFile(dir));
                init(dir, machine("machine-A"));
                MeFrpConfig config = MeFrpConfig.getInstance();
                check(!config.hasToken(), "unknown algorithm not used");
                check(config.setToken("replacement") == MeFrpConfig.SaveResult.CONFIG_READ_ONLY,
                        "explicit token must not overwrite an unknown algorithm");
                check(!config.save(), "plain save must not overwrite an unknown algorithm");
                check(java.util.Arrays.equals(before, Files.readAllBytes(configFile(dir))), "file unchanged");
                check(config.consumeNotifications().contains(MeFrpMessages.CONFIG_READ_FAILED),
                        "read failure message");
            } finally {
                deleteRecursively(dir);
            }
        });

        run("corrupt config reports reset hint", () -> {
            Path dir = newTempDir();
            try {
                writeText(configFile(dir), "{");
                init(dir, machine("machine-A"));
                List<String> messages = MeFrpConfig.getInstance().consumeNotifications();
                check(messages.contains(MeFrpMessages.CONFIG_READ_FAILED), "read failure message");
                check(messages.contains(MeFrpMessages.RESET_HINT), "reset hint");
            } finally {
                deleteRecursively(dir);
            }
        });

        run("non-strict JSON is rejected without migration", () -> {
            String[] payloads = {
                    "{'token':'x'}",
                    "{token:\"x\"}",
                    "{\"token\":\"x\", // comment\n\"tunnelName\":\"y\"}",
                    "{\"token\":\"x\", /* comment */ \"tunnelName\":\"y\"}"
            };
            for (String payload : payloads) {
                assertRejectedJson(payload);
            }
        });

        run("reset works without a machine id and writes no token", () -> {
            Path dir = newTempDir();
            try {
                init(dir, failingMachine());
                check(MeFrpConfig.reset(), "reset without machine id");
                JsonObject json = readJson(configFile(dir));
                check(!json.has("token"), "no token field");
                check(!json.has("tokenEncryption"), "no encryption object");
                checkEquals(MeFrpConstants.VERSION, json.get("modVersion").getAsString(), "current version");
                checkEquals(MeFrpConstants.TUNNEL_NAME, json.get("tunnelName").getAsString(), "default tunnel");
            } finally {
                deleteRecursively(dir);
            }
        });

        run("reset command rejects a busy tunnel", () -> {
            Path dir = newTempDir();
            try {
                init(dir, machine("machine-A"));
                TestPlatform platform = new TestPlatform(dir);
                MeFrpCommandHandler handler = new MeFrpCommandHandler(platform,
                        new FakeMefrpcManager(platform, true));
                handler.handleCommand(new String[]{"config", "reset"});
                check(platform.messages.contains(MeFrpMessages.RESET_BUSY), "busy message");
                check(!Files.exists(configFile(dir)), "config not touched");
            } finally {
                deleteRecursively(dir);
            }
        });

        run("mefrpc busy includes startup in progress", () -> {
            Path dir = newTempDir();
            try {
                TestPlatform platform = new TestPlatform(dir);
                MefrpcManager manager = new MefrpcManager(platform);
                Field starting = MefrpcManager.class.getDeclaredField("starting");
                starting.setAccessible(true);
                starting.setBoolean(manager, true);
                check(manager.isStarting(), "isStarting reflects startup");
                check(manager.isBusy(), "isBusy must include startup so reset is rejected");
            } finally {
                deleteRecursively(dir);
            }
        });

        run("reset command rejects queued operations", () -> {
            Path dir = newTempDir();
            try {
                init(dir, machine("machine-A"));
                TestPlatform platform = new TestPlatform(dir);
                MeFrpCommandHandler handler = new MeFrpCommandHandler(platform,
                        new FakeMefrpcManager(platform, false));
                Field pending = MeFrpCommandHandler.class.getDeclaredField("pendingOperations");
                pending.setAccessible(true);
                pending.setInt(handler, 1);
                handler.handleCommand(new String[]{"config", "reset"});
                check(platform.messages.contains(MeFrpMessages.RESET_BUSY), "queued operation message");
                check(!Files.exists(configFile(dir)), "config not touched");
            } finally {
                deleteRecursively(dir);
            }
        });

        run("reset command succeeds without a machine id", () -> {
            Path dir = newTempDir();
            try {
                init(dir, failingMachine());
                TestPlatform platform = new TestPlatform(dir);
                MeFrpCommandHandler handler = new MeFrpCommandHandler(platform,
                        new FakeMefrpcManager(platform, false));
                handler.handleCommand(new String[]{"config", "reset"});
                check(platform.messages.contains(MeFrpMessages.RESET_SUCCESS), "reset success message");
                check(readJson(configFile(dir)).get("tokenEncryption") == null, "no encrypted token");
            } finally {
                deleteRecursively(dir);
            }
        });

        run("reset command reports write failure", () -> {
            Path dir = newTempDir();
            try {
                Files.createDirectories(dir);
                Files.write(dir.resolve("config"), "not a directory".getBytes(StandardCharsets.UTF_8));
                init(dir, machine("machine-A"));
                TestPlatform platform = new TestPlatform(dir);
                MeFrpCommandHandler handler = new MeFrpCommandHandler(platform,
                        new FakeMefrpcManager(platform, false));
                handler.handleCommand(new String[]{"config", "reset"});
                check(platform.messages.contains(MeFrpMessages.RESET_FAILED), "reset failure message");
            } finally {
                deleteRecursively(dir);
            }
        });

        run("explicit token replaces a config that failed to decrypt", () -> {
            Path dir = newTempDir();
            try {
                init(dir, machine("machine-A"));
                check(MeFrpConfig.getInstance().setToken("old-token") == MeFrpConfig.SaveResult.SUCCESS,
                        "initial save");
                init(dir, machine("machine-B"));
                check(!MeFrpConfig.getInstance().hasToken(), "failed decrypt before replacement");

                TestPlatform platform = new TestPlatform(dir);
                MeFrpCommandHandler handler = new MeFrpCommandHandler(platform,
                        new FakeMefrpcManager(platform, false));
                handler.handleCommand(new String[]{"token", "new-token"});
                check(platform.messages.contains("[MeFrp] 访问令牌已保存。现在可以打开局域网后输入 /mefrp start 开始联机。"),
                        "token saved message");

                init(dir, machine("machine-B"));
                checkEquals("new-token", MeFrpConfig.getInstance().getToken(), "replacement token roundtrip");
            } finally {
                deleteRecursively(dir);
            }
        });

        run("help includes config reset", () -> {
            Path dir = newTempDir();
            try {
                init(dir, machine("machine-A"));
                TestPlatform platform = new TestPlatform(dir);
                MeFrpCommandHandler handler = new MeFrpCommandHandler(platform,
                        new FakeMefrpcManager(platform, false));
                handler.showHelp();
                check(platform.messages.contains(MeFrpMessages.HELP_RESET), "help line");
            } finally {
                deleteRecursively(dir);
            }
        });

        System.out.println("[MeFrp] config regression: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            throw new AssertionError(failed + " config regression test(s) failed");
        }
    }

    private static void run(String name, Test test) {
        try {
            test.run();
            passed++;
            System.out.println("[PASS] " + name);
        } catch (Throwable error) {
            failed++;
            System.out.println("[FAIL] " + name + ": " + error);
            error.printStackTrace(System.out);
        }
    }

    private static void init(Path dir, MachineIdProvider provider) {
        MeFrpConfig.init(dir);
        MeFrpConfig.setMachineIdProviderForTesting(provider);
    }

    private static void assertRejectedJson(String text) throws Exception {
        Path dir = newTempDir();
        try {
            writeText(configFile(dir), text);
            byte[] before = Files.readAllBytes(configFile(dir));
            init(dir, machine("machine-A"));
            MeFrpConfig config = MeFrpConfig.getInstance();
            List<String> messages = config.consumeNotifications();
            check(messages.contains(MeFrpMessages.CONFIG_READ_FAILED), "strict JSON read failure: " + text);
            check(messages.contains(MeFrpMessages.RESET_HINT), "strict JSON reset hint: " + text);
            check(!config.hasToken(), "strict JSON must not load a token: " + text);
            check(java.util.Arrays.equals(before, Files.readAllBytes(configFile(dir))),
                    "strict JSON must not rewrite the file: " + text);
        } finally {
            deleteRecursively(dir);
        }
    }

    private static MachineIdProvider machine(String id) {
        return () -> id;
    }

    private static MachineIdProvider failingMachine() {
        return () -> {
            throw new MachineIdException("machine id unavailable");
        };
    }

    private static JsonObject legacyConfig(String token, String tunnelName) {
        JsonObject json = new JsonObject();
        json.addProperty("token", token);
        json.addProperty("tunnelName", tunnelName);
        return json;
    }

    private static String flipCiphertext(String value) {
        byte[] bytes = Base64.getDecoder().decode(value);
        bytes[0] ^= 0x01;
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static Path configFile(Path dir) {
        return dir.resolve("config").resolve("mefrp.json");
    }

    private static Path newTempDir() throws IOException {
        return Files.createTempDirectory("mefrp-config-regression-");
    }

    private static JsonObject readJson(Path file) throws IOException {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        return new JsonParser().parse(text).getAsJsonObject();
    }

    private static void writeJson(Path file, JsonObject json) throws IOException {
        writeText(file, GSON.toJson(json));
    }

    private static void writeText(Path file, String text) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            if (Files.isDirectory(path)) {
                try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
                    for (Path child : children) {
                        deleteRecursively(child);
                    }
                }
            }
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void checkEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }

    private static void checkNotEquals(Object first, Object second, String message) {
        if (Objects.equals(first, second)) {
            throw new AssertionError(message + " (both=" + first + ")");
        }
    }

    private interface Test {
        void run() throws Exception;
    }

    private static final class TestPlatform implements MeFrpPlatform {
        private final Path directory;
        private final List<String> messages = new ArrayList<>();

        private TestPlatform(Path directory) {
            this.directory = directory;
        }

        @Override
        public Path gameDirectory() {
            return directory;
        }

        @Override
        public boolean isClient() {
            return false;
        }

        @Override
        public boolean isDedicatedServer() {
            return false;
        }

        @Override
        public boolean isModLoaded(String modId) {
            return false;
        }

        @Override
        public void runOnMainThread(Runnable runnable) {
            runnable.run();
        }

        @Override
        public void sendMessage(String message) {
            messages.add(message);
        }

        @Override
        public void sendClickableCommand(String text, String command, String hoverText) {
            messages.add(text);
        }

        @Override
        public void sendClickableCommand(String label, String text, String command, String hoverText) {
            messages.add(label + text);
        }

        @Override
        public void sendCopyableText(String label, String value, String hoverText) {
            messages.add(label + value);
        }
    }

    private static final class FakeMefrpcManager extends MefrpcManager {
        private final boolean busy;

        private FakeMefrpcManager(MeFrpPlatform platform, boolean busy) {
            super(platform);
            this.busy = busy;
        }

        @Override
        public boolean isBusy() {
            return busy;
        }
    }
}
