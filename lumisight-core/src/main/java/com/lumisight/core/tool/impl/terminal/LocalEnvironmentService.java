package com.lumisight.core.tool.impl.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumisight.common.exec.SandboxAccessSpec;
import com.lumisight.core.sandbox.SandboxCommandRunner;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class LocalEnvironmentService {

    private static final Set<String> ALLOWED_EXECUTABLES = Set.of("mvn", "npm", "pnpm", "yarn", "java", "node", "bash", "sh");

    private final SandboxCommandRunner commandRunner;
    private final ObjectMapper objectMapper;

    public LocalEnvironmentService(SandboxCommandRunner commandRunner, ObjectMapper objectMapper) {
        this.commandRunner = commandRunner;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> install(Path repoRoot, String workDir, List<String> command) {
        Path workingDir = resolveWorkingDir(repoRoot, workDir);
        List<String> safeCommand = sanitizeCommand(repoRoot, command);
        SandboxAccessSpec accessSpec = new SandboxAccessSpec(
                "envInstall",
                true,
                List.of(repoRoot.toString(), workingDir.toString()),
                List.of(repoRoot.toString(), workingDir.toString()),
                List.of(),
                List.of("dependency install may need workspace write access and network access for package registries")
        );
        Map<String, Object> result = commandRunner.run(workingDir, safeCommand, accessSpec);
        return mergeResult(Map.of(
                "workingDir", repoRoot.relativize(workingDir).toString(),
                "command", safeCommand
        ), result);
    }

    public Map<String, Object> startService(Path repoRoot, String serviceId, String workDir, List<String> command, Map<String, String> env) {
        String normalizedServiceId = normalizeServiceId(serviceId);
        Path workingDir = resolveWorkingDir(repoRoot, workDir);
        List<String> safeCommand = sanitizeCommand(repoRoot, command);
        Path stateDir = ensureStateDir(repoRoot);
        Path logFile = stateDir.resolve(normalizedServiceId + ".log");
        Path metaFile = stateDir.resolve(normalizedServiceId + ".json");
        ManagedServiceRecord existing = load(metaFile).orElse(null);
        if (existing != null && isAlive(existing.pid())) {
            return Map.of(
                    "serviceId", normalizedServiceId,
                    "status", "already_running",
                    "pid", existing.pid(),
                    "logFile", repoRoot.relativize(Path.of(existing.logFile())).toString(),
                    "command", existing.command(),
                    "startedAt", existing.startedAt()
            );
        }
        try {
            Files.createDirectories(logFile.getParent());
            Files.writeString(
                    logFile,
                    "\n[" + Instant.now() + "] starting service " + normalizedServiceId + " with " + safeCommand + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
            ProcessBuilder builder = new ProcessBuilder(safeCommand)
                    .directory(workingDir.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                    .redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
            if (env != null) {
                builder.environment().putAll(env);
            }
            Process process = builder.start();
            long pid = process.pid();
            ManagedServiceRecord record = new ManagedServiceRecord(
                    normalizedServiceId,
                    workingDir.toString(),
                    safeCommand,
                    env == null ? Map.of() : Map.copyOf(env),
                    pid,
                    logFile.toString(),
                    System.currentTimeMillis()
            );
            save(metaFile, record);
            return Map.of(
                    "serviceId", normalizedServiceId,
                    "status", "started",
                    "pid", pid,
                    "workingDir", repoRoot.relativize(workingDir).toString(),
                    "logFile", repoRoot.relativize(logFile).toString(),
                    "command", safeCommand
            );
        } catch (IOException e) {
            throw new IllegalStateException("failed to start service: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> serviceStatus(Path repoRoot, String serviceId) {
        ManagedServiceRecord record = requireRecord(repoRoot, serviceId);
        boolean running = isAlive(record.pid());
        Path logFile = Path.of(record.logFile());
        return Map.of(
                "serviceId", record.serviceId(),
                "running", running,
                "pid", record.pid(),
                "workingDir", relativize(repoRoot, Path.of(record.workingDir())),
                "logFile", relativize(repoRoot, logFile),
                "logExists", Files.exists(logFile),
                "command", record.command(),
                "startedAt", record.startedAt()
        );
    }

    public Map<String, Object> serviceLogs(Path repoRoot, String serviceId, Integer lines) {
        ManagedServiceRecord record = requireRecord(repoRoot, serviceId);
        Path logFile = Path.of(record.logFile());
        if (!Files.exists(logFile)) {
            throw new IllegalArgumentException("log file not found for service: " + serviceId);
        }
        int maxLines = lines == null ? 120 : Math.max(1, Math.min(lines, 500));
        try {
            List<String> allLines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            int from = Math.max(0, allLines.size() - maxLines);
            List<String> tail = allLines.subList(from, allLines.size());
            return Map.of(
                    "serviceId", record.serviceId(),
                    "running", isAlive(record.pid()),
                    "logFile", relativize(repoRoot, logFile),
                    "lines", tail,
                    "lineCount", tail.size()
            );
        } catch (IOException e) {
            throw new IllegalStateException("failed to read service logs: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> stopService(Path repoRoot, String serviceId) {
        ManagedServiceRecord record = requireRecord(repoRoot, serviceId);
        boolean running = isAlive(record.pid());
        boolean stopped = false;
        if (running) {
            ProcessHandle.of(record.pid()).ifPresent(handle -> {
                handle.destroy();
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                if (handle.isAlive()) {
                    handle.destroyForcibly();
                }
            });
            stopped = !isAlive(record.pid());
        }
        return Map.of(
                "serviceId", record.serviceId(),
                "previouslyRunning", running,
                "stopped", stopped || !running,
                "pid", record.pid()
        );
    }

    private Path resolveWorkingDir(Path repoRoot, String workDir) {
        return LocalRepoPathSupport.resolveInRepo(repoRoot, workDir);
    }

    private List<String> sanitizeCommand(Path repoRoot, List<String> command) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("command is required");
        }
        List<String> normalized = command.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("command is required");
        }
        String executable = normalized.getFirst();
        if (!ALLOWED_EXECUTABLES.contains(executable)) {
            throw new IllegalArgumentException("unsupported executable: " + executable);
        }
        if (("bash".equals(executable) || "sh".equals(executable))
                && normalized.stream().anyMatch(arg -> "-c".equals(arg) || "-lc".equals(arg))) {
            throw new IllegalArgumentException("shell inline command is not allowed");
        }
        if ("bash".equals(executable) || "sh".equals(executable)) {
            if (normalized.size() < 2) {
                throw new IllegalArgumentException("script path is required for shell launcher");
            }
            LocalRepoPathSupport.resolveInRepo(repoRoot, normalized.get(1));
        }
        return List.copyOf(normalized);
    }

    private String normalizeServiceId(String serviceId) {
        if (!StringUtils.hasText(serviceId)) {
            throw new IllegalArgumentException("serviceId is required");
        }
        String normalized = serviceId.trim().replaceAll("[^a-zA-Z0-9._-]", "-");
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("serviceId is invalid");
        }
        return normalized;
    }

    private Path ensureStateDir(Path repoRoot) {
        try {
            Path dir = repoRoot.resolve(".lumisight/runtime/services").normalize();
            Files.createDirectories(dir);
            return dir;
        } catch (IOException e) {
            throw new IllegalStateException("failed to prepare runtime service dir", e);
        }
    }

    private Optional<ManagedServiceRecord> load(Path metaFile) {
        if (!Files.exists(metaFile)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(objectMapper.readValue(metaFile.toFile(), ManagedServiceRecord.class));
        } catch (IOException e) {
            throw new IllegalStateException("failed to load service metadata", e);
        }
    }

    private ManagedServiceRecord requireRecord(Path repoRoot, String serviceId) {
        Path metaFile = ensureStateDir(repoRoot).resolve(normalizeServiceId(serviceId) + ".json");
        return load(metaFile).orElseThrow(() -> new IllegalArgumentException("service not found: " + serviceId));
    }

    private void save(Path metaFile, ManagedServiceRecord record) {
        try {
            Files.createDirectories(metaFile.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(metaFile.toFile(), record);
        } catch (IOException e) {
            throw new IllegalStateException("failed to save service metadata", e);
        }
    }

    private boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private String relativize(Path repoRoot, Path target) {
        try {
            return repoRoot.relativize(target.toAbsolutePath().normalize()).toString();
        } catch (Exception e) {
            return target.toString();
        }
    }

    private Map<String, Object> mergeResult(Map<String, Object> base, Map<String, Object> result) {
        Map<String, Object> merged = new LinkedHashMap<>(base);
        merged.putAll(result);
        return Map.copyOf(merged);
    }

    public record ManagedServiceRecord(
            String serviceId,
            String workingDir,
            List<String> command,
            Map<String, String> env,
            long pid,
            String logFile,
            long startedAt
    ) {
        public ManagedServiceRecord {
            command = command == null ? List.of() : List.copyOf(new ArrayList<>(command));
            env = env == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(env));
        }
    }
}
