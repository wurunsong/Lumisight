package com.lumisight.core.sandbox;

import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class SandboxCommandRunner {

    private final SandboxProperties properties;

    public SandboxCommandRunner(SandboxProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> run(Path workingDir, List<String> command) {
        if (!properties.isEnabled()) {
            return runLocal(workingDir, command, properties.getTimeoutSeconds(), properties.getMaxOutputBytes());
        }
        String mode = properties.getMode() == null ? "local" : properties.getMode().trim().toLowerCase();
        if ("docker".equals(mode)) {
            return runDocker(workingDir, command);
        }
        return runLocal(workingDir, command, properties.getTimeoutSeconds(), properties.getMaxOutputBytes());
    }

    private Map<String, Object> runDocker(Path workingDir, List<String> command) {
        try {
            String repo = workingDir.toAbsolutePath().normalize().toString();
            List<String> dockerCmd = new ArrayList<>();
            dockerCmd.add("docker");
            dockerCmd.add("run");
            dockerCmd.add("--rm");
            dockerCmd.add("--workdir");
            dockerCmd.add("/workspace");
            dockerCmd.add("-v");
            dockerCmd.add(repo + ":/workspace");
            dockerCmd.add("--cpus");
            dockerCmd.add(String.valueOf(properties.getCpuLimit()));
            dockerCmd.add("--memory");
            dockerCmd.add(properties.getMemoryMb() + "m");
            dockerCmd.add("--pids-limit");
            dockerCmd.add("128");
            dockerCmd.add("--security-opt");
            dockerCmd.add("no-new-privileges:true");
            dockerCmd.add("--network");
            dockerCmd.add(properties.isNetworkEnabled() ? "bridge" : "none");
            dockerCmd.add(properties.getContainerImage());
            dockerCmd.addAll(command);
            return runLocal(workingDir, dockerCmd, properties.getTimeoutSeconds(), properties.getMaxOutputBytes());
        } catch (Exception e) {
            return Map.of(
                    "success", false,
                    "exitCode", -1,
                    "timedOut", false,
                    "output", "sandbox docker 启动失败: " + e.getMessage()
            );
        }
    }

    private Map<String, Object> runLocal(Path workingDir, List<String> command, int timeoutSeconds, int maxOutputBytes) {
        try {
            if (!properties.isNetworkEnabled() && looksLikeNetworkCommand(command)) {
                return Map.of(
                        "success", false,
                        "exitCode", -1,
                        "timedOut", false,
                        "output", "网络策略禁止该命令: " + String.join(" ", command)
                );
            }
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(workingDir.toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            OutputCollector collector = new OutputCollector(process.getInputStream(), maxOutputBytes);
            Thread outputThread = new Thread(collector, "sandbox-output-collector");
            outputThread.setDaemon(true);
            outputThread.start();
            boolean finished = process.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                outputThread.join(500);
                return Map.of(
                        "success", false,
                        "exitCode", -1,
                        "timedOut", true,
                        "output", collector.output()
                );
            }
            outputThread.join(500);
            int exitCode = process.exitValue();
            return Map.of(
                    "success", exitCode == 0,
                    "exitCode", exitCode,
                    "timedOut", false,
                    "output", collector.output()
            );
        } catch (Exception e) {
            return Map.of(
                    "success", false,
                    "exitCode", -1,
                    "timedOut", false,
                    "output", "命令执行异常: " + e.getMessage()
            );
        }
    }

    private boolean looksLikeNetworkCommand(List<String> command) {
        if (command == null || command.isEmpty()) {
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
