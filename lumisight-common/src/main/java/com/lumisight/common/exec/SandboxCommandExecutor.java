package com.lumisight.common.exec;

import com.lumisight.common.concurrent.NamedExecutors;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public class SandboxCommandExecutor {

    private static final ExecutorService OUTPUT_COLLECTOR_POOL = NamedExecutors.newFixedPool(
            "sandbox-output-collector",
            Math.max(2, Runtime.getRuntime().availableProcessors())
    );

    public CommandExecutionResult run(CommandExecutionRequest request) {
        if (request == null || request.command() == null || request.command().isEmpty()) {
            return new CommandExecutionResult(false, -1, false, "empty command");
        }
        CommandExecutionPolicy policy = request.policy() == null
                ? new CommandExecutionPolicy("local", false, 20_000L, 200_000, 512L, 1.0d, "docker.io/library/openjdk:21-jdk", List.of(), List.of(), List.of())
                : request.policy();
        if (!policy.networkEnabled() && looksLikeNetworkCommand(request.command())) {
            return new CommandExecutionResult(
                    false,
                    -1,
                    false,
                    "网络策略禁止该命令: " + String.join(" ", request.command())
            );
        }
        Path workingDir = request.workingDir() == null
                ? Path.of("").toAbsolutePath().normalize()
                : request.workingDir().toAbsolutePath().normalize();
        String mode = policy.mode().trim().toLowerCase(Locale.ROOT);
        if ("docker".equals(mode)) {
            return runDocker(workingDir, request, policy);
        }
        if ("mac-seatbelt".equals(mode)) {
            return runMacSeatbelt(workingDir, request, policy);
        }
        return runProcess(workingDir, request.command(), request.stdin(), policy);
    }

    private CommandExecutionResult runDocker(Path workingDir, CommandExecutionRequest request, CommandExecutionPolicy policy) {
        List<String> dockerCmd = new ArrayList<>();
        dockerCmd.add("docker");
        dockerCmd.add("run");
        dockerCmd.add("--rm");
        dockerCmd.add("--workdir");
        dockerCmd.add("/workspace");
        dockerCmd.add("-v");
        dockerCmd.add(workingDir + ":/workspace");
        dockerCmd.add("--cpus");
        dockerCmd.add(String.valueOf(policy.cpuLimit()));
        dockerCmd.add("--memory");
        dockerCmd.add(policy.memoryMb() + "m");
        dockerCmd.add("--pids-limit");
        dockerCmd.add("128");
        dockerCmd.add("--security-opt");
        dockerCmd.add("no-new-privileges:true");
        dockerCmd.add("--network");
        dockerCmd.add(policy.networkEnabled() ? "bridge" : "none");
        dockerCmd.add(policy.containerImage());
        dockerCmd.addAll(request.command());
        return runProcess(workingDir, dockerCmd, request.stdin(), policy);
    }

    private CommandExecutionResult runMacSeatbelt(Path workingDir, CommandExecutionRequest request, CommandExecutionPolicy policy) {
        Path profileFile = null;
        try {
            profileFile = Files.createTempFile("lumisight-seatbelt-", ".sb");
            Files.writeString(profileFile, buildSeatbeltProfile(workingDir, request.command(), policy), StandardCharsets.UTF_8);
            List<String> wrapped = new ArrayList<>();
            wrapped.add("sandbox-exec");
            wrapped.add("-f");
            wrapped.add(profileFile.toString());
            wrapped.addAll(request.command());
            return runProcess(workingDir, wrapped, request.stdin(), policy);
        } catch (Exception e) {
            throw new IllegalStateException("seatbelt 沙箱执行失败: " + e.getMessage(), e);
        } finally {
            if (profileFile != null) {
                try {
                    Files.deleteIfExists(profileFile);
                } catch (Exception ignored) {
                    // profile cleanup failure should not affect command result.
                }
            }
        }
    }

    private CommandExecutionResult runProcess(Path workingDir, List<String> command, String stdin, CommandExecutionPolicy policy) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workingDir.toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            OutputCollector collector = new OutputCollector(process.getInputStream(), policy.maxOutputBytes());
            CompletableFuture<Void> outputFuture = CompletableFuture.runAsync(collector, OUTPUT_COLLECTOR_POOL);
            if (stdin != null) {
                try (OutputStream os = process.getOutputStream()) {
                    os.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                process.getOutputStream().close();
            }
            boolean finished = process.waitFor(policy.timeoutMs(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                waitForCollector(outputFuture, 500L);
                return new CommandExecutionResult(false, -1, true, collector.output());
            }
            waitForCollector(outputFuture, 500L);
            int exitCode = process.exitValue();
            return new CommandExecutionResult(exitCode == 0, exitCode, false, collector.output());
        } catch (Exception e) {
            throw new IllegalStateException("命令执行异常: " + String.join(" ", command) + " | " + e.getMessage(), e);
        }
    }

    private void waitForCollector(CompletableFuture<Void> outputFuture, long timeoutMs) {
        try {
            outputFuture.orTimeout(timeoutMs, TimeUnit.MILLISECONDS).join();
        } catch (Exception ignored) {
            // output collection timeout should not override process result.
        }
    }

    private String buildSeatbeltProfile(Path workingDir, List<String> command, CommandExecutionPolicy policy) {
        Set<String> readPaths = new LinkedHashSet<>();
        Set<String> writePaths = new LinkedHashSet<>();
        addBaseSeatbeltPaths(readPaths, writePaths);
        readPaths.add(workingDir.toString());
        readPaths.addAll(policy.readablePaths());
        writePaths.addAll(policy.writablePaths());
        Path executablePath = resolveExecutablePath(command);
        if (executablePath != null) {
            Path executableParent = executablePath.getParent();
            if (executableParent != null) {
                readPaths.add(executableParent.toString());
            }
        }
        readPaths.addAll(policy.executablePaths());
        StringBuilder builder = new StringBuilder();
        builder.append("(version 1)\n");
        builder.append("(deny default)\n");
        builder.append("(import \"system.sb\")\n");
        builder.append("(allow signal (target self))\n");
        builder.append("(allow process-fork)\n");
        builder.append("(allow file-read-metadata)\n");
        builder.append("(allow file-map-executable\n");
        appendSubpaths(builder, executablePaths(executablePath, policy));
        builder.append(")\n");
        builder.append("(allow process-exec\n");
        appendSubpaths(builder, executablePaths(executablePath, policy));
        builder.append(")\n");
        builder.append("(allow file-read*\n");
        appendSubpaths(builder, readPaths);
        builder.append(")\n");
        if (!writePaths.isEmpty()) {
            builder.append("(allow file-write*\n");
            appendSubpaths(builder, writePaths);
            builder.append(")\n");
        }
        if (policy.networkEnabled()) {
            builder.append("(allow network-outbound)\n");
        }
        return builder.toString();
    }

    private void addBaseSeatbeltPaths(Set<String> readPaths, Set<String> writePaths) {
        readPaths.addAll(baseExecutablePaths());
        readPaths.add("/etc");
        readPaths.add("/private/etc");
        readPaths.add("/dev");
        String tmpDir = System.getenv("TMPDIR");
        if (tmpDir != null && !tmpDir.isBlank()) {
            readPaths.add(tmpDir);
            writePaths.add(tmpDir);
        }
        readPaths.add("/tmp");
        readPaths.add("/private/tmp");
        writePaths.add("/tmp");
        writePaths.add("/private/tmp");
        readPaths.add("/private/var/folders");
        writePaths.add("/private/var/folders");
    }

    private List<String> baseExecutablePaths() {
        return List.of(
                "/System",
                "/usr",
                "/bin",
                "/Library",
                "/usr/local",
                "/opt/homebrew"
        );
    }

    private List<String> executablePaths(Path executablePath, CommandExecutionPolicy policy) {
        Set<String> paths = new LinkedHashSet<>(baseExecutablePaths());
        if (policy != null && policy.executablePaths() != null) {
            paths.addAll(policy.executablePaths());
        }
        if (executablePath != null && executablePath.getParent() != null) {
            paths.add(executablePath.getParent().toString());
        }
        return List.copyOf(paths);
    }

    private Path resolveExecutablePath(List<String> command) {
        if (command == null || command.isEmpty()) {
            return null;
        }
        String executable = command.get(0);
        if (executable == null || executable.isBlank()) {
            return null;
        }
        Path path = Path.of(executable);
        if (path.isAbsolute()) {
            return Files.exists(path) ? path.toAbsolutePath().normalize() : null;
        }
        String envPath = System.getenv("PATH");
        if (envPath == null || envPath.isBlank()) {
            return null;
        }
        for (String entry : envPath.split(":")) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            Path candidate = Path.of(entry).resolve(executable).normalize();
            if (Files.exists(candidate) && Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private void appendSubpaths(StringBuilder builder, Iterable<String> paths) {
        for (String path : paths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            builder.append("    (subpath ").append(quote(path)).append(")\n");
        }
    }

    private String quote(String path) {
        return "\"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private boolean looksLikeNetworkCommand(List<String> command) {
        if (command.isEmpty()) {
            return false;
        }
        String bin = command.get(0).toLowerCase();
        return "curl".equals(bin)
                || "wget".equals(bin)
                || "nc".equals(bin)
                || "netcat".equals(bin)
                || "ssh".equals(bin)
                || "scp".equals(bin);
    }

    private static final class OutputCollector implements Runnable {
        private final InputStream input;
        private final int maxOutputBytes;
        private volatile byte[] bytes = new byte[0];

        private OutputCollector(InputStream input, int maxOutputBytes) {
            this.input = input;
            this.maxOutputBytes = maxOutputBytes;
        }

        @Override
        public void run() {
            try {
                this.bytes = readBytes(input, maxOutputBytes);
            } catch (Exception ignored) {
                this.bytes = new byte[0];
            }
        }

        private static byte[] readBytes(InputStream input, int maxOutputBytes) throws Exception {
            int limit = Math.max(1024, maxOutputBytes);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(limit, 8192));
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (total + read <= limit) {
                    out.write(buffer, 0, read);
                    total += read;
                    continue;
                }
                int allowed = limit - total;
                if (allowed > 0) {
                    out.write(buffer, 0, allowed);
                }
                break;
            }
            return out.toByteArray();
        }

        private String output() {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
