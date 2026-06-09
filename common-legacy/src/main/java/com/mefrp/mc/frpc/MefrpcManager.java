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
    private final Deque<String> recentOutput = new ArrayDeque<String>();
    private volatile String startupFailure;
    private volatile boolean startupSuccess;

    public MefrpcManager(MeFrpPlatform platform) {
        this.platform = platform;
    }

    public synchronized void start(String frpToken, int proxyId) throws IOException, InterruptedException {
        stop();
        Path executable = ensureExecutable();
        recentOutput.clear();

        List<String> command = new ArrayList<String>();
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
        final Process started = process;
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                readOutput(started.getInputStream());
            }
        }, "MeFrp-MC-Mod mefrpc");
        reader.setDaemon(true);
        reader.start();

        Thread.sleep(8000L);
        if (!isAlive(process)) {
            throw new IOException("mefrpc 启动失败: " + lastOutput());
        }
        if (startupFailure != null) {
            stop();
            throw new IOException(startupFailure);
        }

        Thread monitor = new Thread(new Runnable() {
            @Override
            public void run() {
                monitorProcess(started);
            }
        }, "MeFrp-MC-Mod mefrpc monitor");
        monitor.setDaemon(true);
        monitor.start();
    }

    public synchronized void stop() {
        if (process != null) {
            Process stopping = process;
            stopping.destroy();
            try {
                if (!stopping.waitFor(3, TimeUnit.SECONDS)) {
                    stopping.destroy();
                    stopping.waitFor(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stopping.destroy();
            }
            process = null;
        }
        killExtractedMefrpcProcesses();
    }

    private void killExtractedMefrpcProcesses() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                new ProcessBuilder("taskkill", "/F", "/IM", "mefrpc.exe").redirectErrorStream(true).start().waitFor(3, TimeUnit.SECONDS);
            } else {
                new ProcessBuilder("pkill", "-f", "mefrpc").redirectErrorStream(true).start().waitFor(3, TimeUnit.SECONDS);
            }
        } catch (Exception ignored) {
        }
    }

    public synchronized boolean isRunning() {
        return process != null && isAlive(process);
    }

    public synchronized ProcessStatus processStatus() {
        if (process == null || !isAlive(process)) {
            return new ProcessStatus(false, -1L, -1L);
        }
        long pid = currentPid(process);
        return new ProcessStatus(true, pid, queryMemoryBytes(pid));
    }

    public boolean hasStartupSuccess() {
        return startupSuccess;
    }

    public synchronized String lastOutput() {
        if (recentOutput.isEmpty()) {
            return "无输出";
        }
        StringBuilder builder = new StringBuilder();
        for (String line : recentOutput) {
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append(line);
        }
        return builder.toString();
    }

    private Path ensureExecutable() throws IOException {
        BinaryTarget target = detectTarget();
        Path binDir = platform.gameDirectory().resolve("config").resolve("mefrp").resolve("bin");
        Files.createDirectories(binDir);
        Path executable = binDir.resolve(target.fileName);
        if (Files.exists(executable) && Files.size(executable) > 0) {
            return executable;
        }

        InputStream input = MefrpcManager.class.getResourceAsStream(target.resourcePath());
        if (input == null) {
            throw new IOException("未找到内置 mefrpc: " + target.resourcePath());
        }
        try {
            GZIPInputStream gzip = new GZIPInputStream(input);
            try {
                Files.copy(gzip, executable, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                gzip.close();
            }
        } finally {
            input.close();
        }
        if (!target.windows) {
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
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
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
        String lower = line.toLowerCase(Locale.ROOT);
        return line.contains("失败") || line.contains("错误") || lower.contains("error") || lower.contains("failed");
    }

    private boolean isSuccessLine(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
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

    private boolean isAlive(Process process) {
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException ignored) {
            return true;
        }
    }

    private long currentPid(Process process) {
        try {
            java.lang.reflect.Field field = process.getClass().getDeclaredField("pid");
            field.setAccessible(true);
            Object value = field.get(process);
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
        } catch (Exception ignored) {
        }
        return -1L;
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
                query.destroy();
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
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (builder.length() > 0) {
                    builder.append('\n');
                }
                builder.append(line);
            }
        } finally {
            reader.close();
        }
        return builder.toString();
    }

    private long parseWindowsTasklistMemory(String output) {
        if (output == null || output.trim().isEmpty() || output.toLowerCase(Locale.ROOT).contains("no tasks")) {
            return -1L;
        }
        String[] fields = output.split("\",\"");
        String memoryField = fields.length == 0 ? output : fields[fields.length - 1];
        String digits = memoryField.replaceAll("[^0-9]", "");
        if (digits.trim().isEmpty()) {
            return -1L;
        }
        try {
            return Long.parseLong(digits) * 1024L;
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private long parseRssKilobytes(String output) {
        if (output == null || output.trim().isEmpty()) {
            return -1L;
        }
        String digits = output.trim().replaceAll("[^0-9].*$", "");
        if (digits.trim().isEmpty()) {
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

    private static class BinaryTarget {
        private final String resourceName;
        private final String fileName;
        private final boolean windows;

        private BinaryTarget(String resourceName, String fileName, boolean windows) {
            this.resourceName = resourceName;
            this.fileName = fileName;
            this.windows = windows;
        }

        private String resourcePath() {
            return "/assets/" + MeFrpConstants.MOD_ID + "/mefrpc/" + resourceName;
        }
    }
}
