package com.mefrp.mc.frpc;

import com.mefrp.mc.MeFrpConstants;
import com.mefrp.mc.platform.MeFrpPlatform;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

public class MefrpcManager {
    private final MeFrpPlatform platform;
    private Process process;
    private final Deque<String> recentOutput = new ArrayDeque<>();
    private volatile String startupFailure;
    private volatile boolean startupSuccess;

    public MefrpcManager(MeFrpPlatform platform) {
        this.platform = platform;
    }

    public synchronized void start(String frpToken, int proxyId) throws IOException, InterruptedException {
        stop();
        Path executable = ensureExecutable();
        recentOutput.clear();

        List<String> command = new ArrayList<>();
        command.add(executable.toAbsolutePath().toString());
        command.add("-t");
        command.add(frpToken);
        command.add("-p");
        command.add(String.valueOf(proxyId));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(executable.getParent().toFile());
        builder.redirectErrorStream(true);
        startupFailure = null;
        startupSuccess = false;
        process = builder.start();
        Thread reader = new Thread(() -> readOutput(process.getInputStream()), "MeFrp-MC-Mod mefrpc");
        reader.setDaemon(true);
        reader.start();

        Thread.sleep(8000L);
        if (!process.isAlive()) {
            throw new IOException("mefrpc 启动失败: " + lastOutput());
        }
        if (startupFailure != null) {
            stop();
            throw new IOException(startupFailure);
        }

        Process startedProcess = process;
        Thread monitor = new Thread(() -> monitorProcess(startedProcess), "MeFrp-MC-Mod mefrpc monitor");
        monitor.setDaemon(true);
        monitor.start();
    }

    public synchronized void stop() {
        if (process == null) {
            killExtractedMefrpcProcesses();
            return;
        }
        Process stopping = process;
        stopping.descendants().forEach(child -> {
            try {
                child.destroy();
            } catch (Exception ignored) {
            }
        });
        stopping.destroy();
        try {
            if (!stopping.waitFor(3, TimeUnit.SECONDS)) {
                stopping.descendants().forEach(child -> {
                    try {
                        child.destroyForcibly();
                    } catch (Exception ignored) {
                    }
                });
                stopping.destroyForcibly();
                stopping.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopping.destroyForcibly();
        }
        process = null;
        killExtractedMefrpcProcesses();
    }

    private void killExtractedMefrpcProcesses() {
        Path binDir = platform.gameDirectory().resolve("config").resolve("mefrp").resolve("bin").toAbsolutePath().normalize();
        ProcessHandle.allProcesses().forEach(handle -> {
            try {
                String command = handle.info().command().orElse("");
                if (command.isBlank()) {
                    return;
                }
                Path commandPath = Path.of(command).toAbsolutePath().normalize();
                if (!commandPath.startsWith(binDir) || !commandPath.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("mefrpc")) {
                    return;
                }
                handle.destroy();
                try {
                    handle.onExit().get(2, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    handle.destroyForcibly();
                }
                platform.logInfo("[MeFrp] killed mefrpc process: " + commandPath);
            } catch (Exception ignored) {
            }
        });
    }

    public synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    public synchronized ProcessStatus processStatus() {
        if (process == null || !process.isAlive()) {
            return new ProcessStatus(false, -1L, -1L);
        }
        long pid = process.pid();
        return new ProcessStatus(true, pid, queryMemoryBytes(pid));
    }

    public boolean hasStartupSuccess() {
        return startupSuccess;
    }

    public synchronized String lastOutput() {
        if (recentOutput.isEmpty()) {
            return "无输出";
        }
        return String.join(" | ", recentOutput);
    }

    private Path ensureExecutable() throws IOException {
        BinaryTarget target = detectTarget();
        Path binDir = platform.gameDirectory().resolve("config").resolve("mefrp").resolve("bin");
        Files.createDirectories(binDir);
        Path executable = binDir.resolve(target.fileName());
        if (Files.exists(executable) && Files.size(executable) > 0) {
            return executable;
        }

        try (InputStream input = MefrpcManager.class.getResourceAsStream(target.resourcePath())) {
            if (input == null) {
                throw new IOException("未找到内置 mefrpc: " + target.resourcePath());
            }
            try (GZIPInputStream gzip = new GZIPInputStream(input)) {
                Files.copy(gzip, executable, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        if (!target.windows()) {
            executable.toFile().setExecutable(true, true);
        }
        return executable;
    }

    private BinaryTarget detectTarget() throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean amd64 = arch.contains("amd64") || arch.contains("x86_64");
        boolean arm64 = arch.contains("aarch64") || arch.contains("arm64");
        if (os.contains("win") && amd64) {
            return new BinaryTarget("windows_amd64.gz", "mefrpc.exe", true);
        }
        if (os.contains("linux") && amd64) {
            return new BinaryTarget("linux_amd64.gz", "mefrpc", false);
        }
        if ((os.contains("mac") || os.contains("darwin")) && amd64) {
            return new BinaryTarget("darwin_amd64.gz", "mefrpc", false);
        }
        if ((os.contains("mac") || os.contains("darwin")) && arm64) {
            return new BinaryTarget("darwin_arm64.gz", "mefrpc", false);
        }
        throw new IOException("暂不支持当前系统: " + os + " " + arch);
    }

    private void readOutput(InputStream inputStream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = stripAnsi(line);
                platform.logInfo("[MeFrp] [mefrpc] " + line);
                if (isFailureLine(line)) {
                    startupFailure = line;
                }
                if (isSuccessLine(line)) {
                    startupSuccess = true;
                }
                synchronized (this) {
                    recentOutput.addLast(line);
                    while (recentOutput.size() > 5) {
                        recentOutput.removeFirst();
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private String stripAnsi(String line) {
        return line.replaceAll("\\u001B\\[[;\\d]*m", "");
    }

    private boolean isFailureLine(String line) {
        return line.contains("失败") || line.contains("错误") || line.toLowerCase(java.util.Locale.ROOT).contains("error") || line.toLowerCase(java.util.Locale.ROOT).contains("failed");
    }

    private boolean isSuccessLine(String line) {
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        return line.contains("登录节点成功") || line.contains("注册成功") || (lower.contains("login") && lower.contains("success"));
    }

    private void monitorProcess(Process monitoredProcess) {
        try {
            int exitCode = monitoredProcess.waitFor();
            synchronized (this) {
                if (process != monitoredProcess) {
                    return;
                }
                process = null;
            }
            String reason = lastOutput();
            platform.logWarn("[MeFrp] mefrpc exited with code " + exitCode + ": " + reason);
            platform.sendMessage("[MeFrp] mefrpc 已退出，退出码 " + exitCode + "，原因: " + reason);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long queryMemoryBytes(long pid) {
        if (pid <= 0L) {
            return -1L;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            ProcessBuilder builder;
            if (os.contains("win")) {
                builder = new ProcessBuilder("tasklist", "/FI", "PID eq " + pid, "/FO", "CSV", "/NH");
            } else {
                builder = new ProcessBuilder("ps", "-o", "rss=", "-p", String.valueOf(pid));
            }
            builder.redirectErrorStream(true);
            Process query = builder.start();
            if (!query.waitFor(3, TimeUnit.SECONDS)) {
                query.destroyForcibly();
                return -1L;
            }
            String output = readProcessOutput(query.getInputStream());
            if (os.contains("win")) {
                return parseWindowsTasklistMemory(output);
            }
            return parseRssKilobytes(output);
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private String readProcessOutput(InputStream inputStream) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(line);
            }
        }
        return builder.toString();
    }

    private long parseWindowsTasklistMemory(String output) {
        if (output == null || output.isBlank() || output.toLowerCase(Locale.ROOT).contains("no tasks")) {
            return -1L;
        }
        String[] fields = output.split("\",\"");
        String memoryField = fields.length == 0 ? output : fields[fields.length - 1];
        String digits = memoryField.replaceAll("[^0-9]", "");
        if (digits.isBlank()) {
            return -1L;
        }
        try {
            return Long.parseLong(digits) * 1024L;
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private long parseRssKilobytes(String output) {
        if (output == null || output.isBlank()) {
            return -1L;
        }
        String digits = output.trim().replaceAll("[^0-9].*$", "");
        if (digits.isBlank()) {
            return -1L;
        }
        try {
            return Long.parseLong(digits) * 1024L;
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    public static class ProcessStatus {
        public final boolean running;
        public final long pid;
        public final long memoryBytes;

        private ProcessStatus(boolean running, long pid, long memoryBytes) {
            this.running = running;
            this.pid = pid;
            this.memoryBytes = memoryBytes;
        }
    }

    private record BinaryTarget(String resourceName, String fileName, boolean windows) {
        String resourcePath() {
            return "/assets/" + MeFrpConstants.MOD_ID + "/mefrpc/" + resourceName;
        }
    }
}
