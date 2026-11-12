package com.mefrp.mc.security;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a stable per-machine identifier. Windows uses MachineGuid, Linux uses
 * machine-id, macOS uses IOPlatformUUID. There is deliberately no fallback to
 * the user name, MAC address, or other weak values.
 */
public final class DefaultMachineIdProvider implements MachineIdProvider {
    private static final long QUERY_TIMEOUT_MILLIS = 2000L;
    private static final int MAX_OUTPUT_CHARS = 8192;
    private static final Pattern WINDOWS_MACHINE_GUID =
            Pattern.compile("MachineGuid\\s+REG_SZ\\s+(\\S+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MAC_PLATFORM_UUID =
            Pattern.compile("IOPlatformUUID\"\\s*=\\s*\"([^\"]+)\"");

    private volatile String cachedMachineId;

    @Override
    public String getMachineId() throws MachineIdException {
        String cached = cachedMachineId;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (cachedMachineId == null) {
                cachedMachineId = detectMachineId();
            }
            return cachedMachineId;
        }
    }

    private String detectMachineId() throws MachineIdException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return windowsMachineGuid();
        }
        if (os.contains("mac") || os.contains("darwin") || os.contains("osx")) {
            return macPlatformUuid();
        }
        if (os.contains("linux")) {
            return linuxMachineId();
        }
        throw new MachineIdException("unsupported operating system");
    }

    private String windowsMachineGuid() throws MachineIdException {
        long deadline = System.currentTimeMillis() + QUERY_TIMEOUT_MILLIS;
        String value = queryWindowsRegistry("64", remainingMillis(deadline));
        if (value == null) {
            value = queryWindowsRegistry("32", remainingMillis(deadline));
        }
        if (!isValid(value)) {
            throw new MachineIdException("MachineGuid is unavailable");
        }
        return value;
    }

    private long remainingMillis(long deadline) {
        return Math.max(0L, deadline - System.currentTimeMillis());
    }

    private String queryWindowsRegistry(String view, long timeoutMillis) {
        if (timeoutMillis <= 0L) {
            return null;
        }
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    "reg", "query", "HKLM\\SOFTWARE\\Microsoft\\Cryptography",
                    "/v", "MachineGuid", "/reg:" + view);
            builder.redirectErrorStream(true);
            process = builder.start();
            // On timeout return without touching the stream: a blocking read
            // must never extend the query deadline.
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                return null;
            }
            String output = readLimited(process.getInputStream());
            Matcher matcher = WINDOWS_MACHINE_GUID.matcher(output);
            if (matcher.find()) {
                return matcher.group(1).trim();
            }
            return null;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private String linuxMachineId() throws MachineIdException {
        String value = readMachineIdFile(Paths.get("/etc/machine-id"));
        if (isValid(value)) {
            return value;
        }
        value = readMachineIdFile(Paths.get("/var/lib/dbus/machine-id"));
        if (isValid(value)) {
            return value;
        }
        throw new MachineIdException("machine-id is unavailable");
    }

    private String readMachineIdFile(Path path) {
        try {
            if (!Files.isRegularFile(path) || Files.size(path) > 4096L) {
                return null;
            }
            return new String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim();
        } catch (IOException ignored) {
            return null;
        }
    }

    private String macPlatformUuid() throws MachineIdException {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder("ioreg", "-rd1", "-c", "IOPlatformExpertDevice");
            builder.redirectErrorStream(true);
            process = builder.start();
            // On timeout return without touching the stream: a blocking read
            // must never extend the query deadline.
            if (!process.waitFor(QUERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new MachineIdException("platform UUID query timed out");
            }
            String output = readLimited(process.getInputStream());
            Matcher matcher = MAC_PLATFORM_UUID.matcher(output);
            if (matcher.find() && isValid(matcher.group(1))) {
                return matcher.group(1).trim();
            }
            throw new MachineIdException("platform UUID is unavailable");
        } catch (MachineIdException e) {
            throw e;
        } catch (Exception e) {
            throw new MachineIdException("platform UUID query failed", e);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private String readLimited(InputStream input) throws IOException {
        StringBuilder builder = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        try {
            char[] buffer = new char[1024];
            int read;
            while ((read = reader.read(buffer)) >= 0 && builder.length() < MAX_OUTPUT_CHARS) {
                builder.append(buffer, 0, read);
            }
        } finally {
            reader.close();
        }
        if (builder.length() > MAX_OUTPUT_CHARS) {
            builder.setLength(MAX_OUTPUT_CHARS);
        }
        return builder.toString();
    }

    private boolean isValid(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.length() < 8 || trimmed.length() > 128) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                return false;
            }
        }
        return true;
    }
}
