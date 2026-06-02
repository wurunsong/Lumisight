package com.lumisight.common.exec;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class SandboxCommandExecutor {

    public CommandExecutionResult run(CommandExecutionRequest request) {
        if (request == null || request.command() == null || request.command().isEmpty()) {
            return new CommandExecutionResult(false, -1, false, "empty command");
        }
        CommandExecutionPolicy policy = request.policy() == null
                ? new CommandExecutionPolicy(false, 20_000L, 200_000)
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
        try {
            ProcessBuilder builder = new ProcessBuilder(request.command());
            builder.directory(workingDir.toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            OutputCollector collector = new OutputCollector(process.getInputStream(), policy.maxOutputBytes());
            Thread outputThread = new Thread(collector, "sandbox-output-collector");
            outputThread.setDaemon(true);
            outputThread.start();
            if (request.stdin() != null) {
                try (OutputStream os = process.getOutputStream()) {
                    os.write(request.stdin().getBytes(StandardCharsets.UTF_8));
                }
            } else {
                process.getOutputStream().close();
            }
            boolean finished = process.waitFor(policy.timeoutMs(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                outputThread.join(500);
                return new CommandExecutionResult(false, -1, true, collector.output());
            }
            outputThread.join(500);
            int exitCode = process.exitValue();
            return new CommandExecutionResult(exitCode == 0, exitCode, false, collector.output());
        } catch (Exception e) {
            return new CommandExecutionResult(false, -1, false, "命令执行异常: " + e.getMessage());
        }
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
