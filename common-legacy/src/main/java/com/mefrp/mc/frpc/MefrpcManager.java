package com.mefrp.mc.frpc;

import com.mefrp.mc.MeFrpConstants;
import com.mefrp.mc.platform.MeFrpPlatform;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

public class MefrpcManager {
    private static final long STARTUP_TIMEOUT_MILLIS = 8000L;
    private static final long STARTUP_POLL_MILLIS = 250L;
    private static final Pattern TUNNEL_READY_PATTERN = Pattern.compile("启动 \\[[^\\]]*\\] 隧道 \\[.*\\] 成功");

    private final MeFrpPlatform platform;
    private final Object stateLock = new Object();
    private final Object executableLock = new Object();
    private final Object pidLock = new Object();
    private static final Method PROCESS_PID_METHOD = lookupProcessPidMethod();
    private Process process;
    private Process cachedPidProcess;
    private long cachedPid = -1L;
    private boolean cachedPidValid;
    private Object cachedGetProcessIdFunction;
    private boolean getProcessIdUnavailable;
    private final Deque<String> recentOutput = new ArrayDeque<String>();
    private long launchId;
    private volatile String startupFailure;
    private volatile boolean startupSuccess;
    private volatile boolean starting;

    public MefrpcManager(MeFrpPlatform platform) {
        this.platform = platform;
    }

    public long start(String frpToken, int proxyId) throws IOException, InterruptedException {
        starting = true;
        try {
            return startInternal(frpToken, proxyId);
        } finally {
            starting = false;
        }
    }

    private long startInternal(String frpToken, int proxyId) throws IOException, InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }
        Process previous;
        final long launch;
        synchronized (stateLock) {
            previous = process;
            process = null;
            launch = ++launchId;
            recentOutput.clear();
            startupFailure = null;
            startupSuccess = false;
        }
        terminate(previous);

        Path executable = ensureExecutable();
        if (isLaunchCancelled(launch) || Thread.currentThread().isInterrupted()) {
            throw new InterruptedException();
        }

        List<String> command = new ArrayList<String>();
        command.add(executable.toAbsolutePath().toString());
        command.add("-t");
        command.add(frpToken);
        command.add("-p");
        command.add(String.valueOf(proxyId));

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(executable.getParent().toFile());
        builder.redirectErrorStream(true);
        final Process started = builder.start();
        synchronized (stateLock) {
            if (launchId == launch) {
                process = started;
            }
        }
        if (isLaunchCancelled(launch)) {
            terminate(started);
            throw new InterruptedException();
        }

        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                readOutput(started, launch);
            }
        }, "MeFrp-MC-Mod mefrpc");
        reader.setDaemon(true);
        reader.start();

        boolean ready;
        try {
            ready = awaitStartup(started, launch);
        } catch (InterruptedException e) {
            discardStartedProcess(started);
            throw e;
        }
        if (!ready) {
            final boolean cancelled = isLaunchCancelled(launch);
            String failure;
            synchronized (stateLock) {
                failure = startupFailure;
            }
            discardStartedProcess(started);
            if (cancelled) {
                throw new InterruptedException();
            }
            if (failure != null) {
                throw new IOException(failure);
            }
            throw new IOException("mefrpc 启动失败: " + lastOutput());
        }

        Thread monitor = new Thread(new Runnable() {
            @Override
            public void run() {
                monitorProcess(started, launch);
            }
        }, "MeFrp-MC-Mod mefrpc monitor");
        monitor.setDaemon(true);
        monitor.start();
        return launch;
    }

    public void stop() {
        Process stopping;
        synchronized (stateLock) {
            launchId++;
            stopping = process;
            process = null;
            stateLock.notifyAll();
        }
        terminate(stopping);
    }

    /**
     * Stops the process only when it still belongs to the given launch. A stale
     * task must not terminate a process that a newer launch already started.
     */
    public void stop(long launch) {
        Process stopping;
        synchronized (stateLock) {
            if (launchId != launch) {
                return;
            }
            launchId++;
            stopping = process;
            process = null;
            stateLock.notifyAll();
        }
        terminate(stopping);
    }

    private boolean awaitStartup(Process started, long launch) throws InterruptedException {
        long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MILLIS;
        synchronized (stateLock) {
            while (true) {
                if (launchId != launch) {
                    return false;
                }
                if (startupFailure != null) {
                    return false;
                }
                if (startupSuccess) {
                    return true;
                }
                if (!isAlive(started)) {
                    return false;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0L) {
                    return false;
                }
                stateLock.wait(Math.min(remaining, STARTUP_POLL_MILLIS));
            }
        }
    }

    private boolean isLaunchCancelled(long launch) {
        synchronized (stateLock) {
            return launchId != launch;
        }
    }

    private void discardStartedProcess(Process started) {
        synchronized (stateLock) {
            if (process == started) {
                process = null;
            }
        }
        terminate(started);
    }

    private void terminate(Process target) {
        if (target == null) {
            return;
        }
        List<Object> descendants = descendantsOf(target);
        for (Object child : descendants) {
            destroyHandle(child, false);
        }
        try {
            target.destroy();
        } catch (Exception ignored) {
        }
        boolean exited;
        try {
            exited = target.waitFor(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exited = false;
        }
        for (Object child : descendants) {
            if (isHandleAlive(child)) {
                destroyHandle(child, true);
            }
        }
        if (exited) {
            return;
        }
        try {
            target.destroyForcibly();
        } catch (Exception ignored) {
        }
        try {
            target.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<Object> descendantsOf(Process target) {
        try {
            Object stream = Process.class.getMethod("descendants").invoke(target);
            if (stream == null) {
                return Collections.emptyList();
            }
            Method iteratorMethod = Class.forName("java.util.stream.Stream").getMethod("iterator");
            Iterator<?> iterator = (Iterator<?>) iteratorMethod.invoke(stream);
            List<Object> result = new ArrayList<Object>();
            while (iterator.hasNext()) {
                result.add(iterator.next());
            }
            return result;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private void destroyHandle(Object handle, boolean force) {
        try {
            Method method = Class.forName("java.lang.ProcessHandle").getMethod(force ? "destroyForcibly" : "destroy");
            method.invoke(handle);
        } catch (Exception ignored) {
        }
    }

    private boolean isHandleAlive(Object handle) {
        try {
            Object result = Class.forName("java.lang.ProcessHandle").getMethod("isAlive").invoke(handle);
            return Boolean.TRUE.equals(result);
        } catch (Exception ignored) {
            return true;
        }
    }

    public boolean isRunning() {
        synchronized (stateLock) {
            return process != null && isAlive(process);
        }
    }

    public boolean isStarting() {
        return starting;
    }

    public boolean isBusy() {
        return starting || isRunning();
    }

    public ProcessStatus processStatus() {
        Process current;
        synchronized (stateLock) {
            current = process;
        }
        if (current == null || !isAlive(current)) {
            return new ProcessStatus(false, -1L, -1L);
        }
        long pid = currentPid(current);
        return new ProcessStatus(true, pid, queryMemoryBytes(pid));
    }

    public boolean hasStartupSuccess() {
        return startupSuccess;
    }

    public String lastOutput() {
        synchronized (stateLock) {
            return lastOutputLocked();
        }
    }

    private String lastOutputLocked() {
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
        synchronized (executableLock) {
            BinaryTarget target = detectTarget();
            Path binDir = platform.gameDirectory().resolve("config").resolve("mefrp").resolve("bin");
            Files.createDirectories(binDir);
            Path executable = binDir.resolve(target.fileName);
            Path versionMarker = binDir.resolve("mefrpc.version");
            if (Files.exists(executable) && Files.size(executable) > 0 && markerMatches(versionMarker)) {
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
            Files.write(versionMarker, MeFrpConstants.MEFRPC_VERSION.getBytes(StandardCharsets.UTF_8));
            return executable;
        }
    }

    private boolean markerMatches(Path versionMarker) {
        try {
            return MeFrpConstants.MEFRPC_VERSION.equals(new String(Files.readAllBytes(versionMarker), StandardCharsets.UTF_8).trim());
        } catch (IOException ignored) {
            return false;
        }
    }

    private BinaryTarget detectTarget() throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean amd64 = arch.contains("amd64") || arch.contains("x86_64");
        boolean arm64 = arch.contains("aarch64") || arch.contains("arm64");
        boolean mac = os.contains("mac") || os.contains("darwin") || os.contains("osx");
        boolean windows = !mac && (os.contains("windows") || os.startsWith("win"));
        if (mac && amd64) {
            return new BinaryTarget("darwin_amd64.gz", "mefrpc", false);
        }
        if (mac && arm64) {
            return new BinaryTarget("darwin_arm64.gz", "mefrpc", false);
        }
        if (windows && amd64) {
            return new BinaryTarget("windows_amd64.gz", "mefrpc.exe", true);
        }
        if (os.contains("linux") && amd64) {
            return new BinaryTarget("linux_amd64.gz", "mefrpc", false);
        }
        if (os.contains("linux") && arm64) {
            return new BinaryTarget("linux_arm64.gz", "mefrpc", false);
        }
        throw new IOException("暂不支持当前系统: " + os + " " + arch);
    }

    private void readOutput(Process owner, long launch) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(owner.getInputStream(), StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = stripAnsi(line);
                    platform.logInfo("[MeFrp] [mefrpc] " + line);
                    synchronized (stateLock) {
                        if (launchId != launch) {
                            continue;
                        }
                        if (isFailureLine(line)) {
                            if (startupFailure == null) {
                                startupFailure = line;
                            }
                            startupSuccess = false;
                        } else if (TUNNEL_READY_PATTERN.matcher(line).find() && startupFailure == null) {
                            startupSuccess = true;
                        }
                        recentOutput.addLast(line);
                        while (recentOutput.size() > 5) {
                            recentOutput.removeFirst();
                        }
                        stateLock.notifyAll();
                    }
                }
            } finally {
                reader.close();
            }
        } catch (IOException ignored) {
        }
    }

    private String stripAnsi(String line) {
        return line.replaceAll("\\u001B\\[[;\\d]*m", "");
    }

    private boolean isFailureLine(String line) {
        if (TUNNEL_READY_PATTERN.matcher(line).find()) {
            return false;
        }
        if (line.contains("登录节点失败")) {
            return true;
        }
        if (line.contains("启动隧道 [") && line.contains("失败")) {
            return true;
        }
        if (line.contains("配置失败")) {
            return true;
        }
        return line.contains("未找到隧道")
                || line.contains("proxyId 格式错误")
                || line.contains("不支持的隧道类型")
                || line.contains("无法解析隧道")
                || line.contains("的角色应为")
                || line.contains("解析配置文件失败");
    }

    private void monitorProcess(Process monitoredProcess, long launch) {
        try {
            int exitCode = monitoredProcess.waitFor();
            String reason;
            synchronized (stateLock) {
                if (launchId != launch) {
                    return;
                }
                if (process == monitoredProcess) {
                    process = null;
                }
                reason = lastOutputLocked();
                stateLock.notifyAll();
            }
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

    private static Method lookupProcessPidMethod() {
        try {
            return Process.class.getMethod("pid");
        } catch (Exception ignored) {
            return null;
        }
    }

    private long currentPid(Process process) {
        // Cache by Process identity; a restart creates a new Process and refreshes the pid.
        synchronized (pidLock) {
            if (cachedPidValid && cachedPidProcess == process) {
                return cachedPid;
            }
        }
        long pid = resolvePid(process);
        if (pid > 0L) {
            synchronized (pidLock) {
                cachedPidProcess = process;
                cachedPid = pid;
                cachedPidValid = true;
            }
        }
        return pid;
    }

    private long resolvePid(Process process) {
        long pid = pidFromProcessMethod(process);
        if (pid > 0L) {
            return pid;
        }
        pid = pidFromProcessField(process);
        if (pid > 0L) {
            return pid;
        }
        return pidFromWindowsHandle(process);
    }

    private long pidFromProcessMethod(Process process) {
        Method method = PROCESS_PID_METHOD;
        if (method == null) {
            return -1L;
        }
        try {
            return positivePid(method.invoke(process));
        } catch (Exception ignored) {
            return -1L;
        }
    }

    private long pidFromProcessField(Process process) {
        try {
            Field field = process.getClass().getDeclaredField("pid");
            field.setAccessible(true);
            return positivePid(field.get(process));
        } catch (Exception ignored) {
            return -1L;
        } catch (LinkageError ignored) {
            return -1L;
        }
    }

    /**
     * Java 8 on Windows exposes only the native process handle on ProcessImpl.
     * JNA is shipped with the supported Minecraft versions, so call
     * GetProcessId reflectively without adding a compile-time dependency.
     */
    private long pidFromWindowsHandle(Process process) {
        try {
            Field field = process.getClass().getDeclaredField("handle");
            field.setAccessible(true);
            Object value = field.get(process);
            if (!(value instanceof Number)) {
                return -1L;
            }
            long handle = ((Number) value).longValue();
            if (handle == 0L) {
                return -1L;
            }
            Object function = getProcessIdFunction();
            if (function == null) {
                return -1L;
            }
            Object pointer = Class.forName("com.sun.jna.Pointer")
                    .getConstructor(long.class)
                    .newInstance(Long.valueOf(handle));
            Object pid = function.getClass()
                    .getMethod("invokeInt", Object[].class)
                    .invoke(function, (Object) new Object[]{pointer});
            return positivePid(pid);
        } catch (Exception ignored) {
            return -1L;
        } catch (LinkageError ignored) {
            return -1L;
        }
    }

    private Object getProcessIdFunction() {
        synchronized (pidLock) {
            if (cachedGetProcessIdFunction != null) {
                return cachedGetProcessIdFunction;
            }
            if (getProcessIdUnavailable) {
                return null;
            }
            try {
                Class<?> nativeLibraryClass = Class.forName("com.sun.jna.NativeLibrary");
                Object kernel32 = nativeLibraryClass.getMethod("getInstance", String.class).invoke(null, "kernel32");
                Object function = kernel32.getClass().getMethod("getFunction", String.class).invoke(kernel32, "GetProcessId");
                cachedGetProcessIdFunction = function;
                return function;
            } catch (Exception ignored) {
                getProcessIdUnavailable = true;
                return null;
            } catch (LinkageError ignored) {
                getProcessIdUnavailable = true;
                return null;
            }
        }
    }

    private long positivePid(Object value) {
        if (value instanceof Number) {
            long pid = ((Number) value).longValue();
            return pid > 0L ? pid : -1L;
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
