package com.lumisight.common.exec;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class SandboxPolicyPlanner {

    public SandboxExecutionPlan plan(
            Path workingDir,
            SandboxProperties properties,
            SandboxAccessSpec accessSpec,
            long timeoutMs,
            int maxOutputBytes
    ) {
        Path normalizedWorkingDir = workingDir == null
                ? Path.of("").toAbsolutePath().normalize()
                : workingDir.toAbsolutePath().normalize();
        SandboxAccessSpec spec = accessSpec == null
                ? new SandboxAccessSpec("command", properties.isNetworkEnabled(), List.of(), List.of(), List.of(), List.of())
                : accessSpec;

        Set<String> readablePaths = new LinkedHashSet<>();
        readablePaths.add(normalizedWorkingDir.toString());
        readablePaths.addAll(normalize(spec.readablePaths()));

        Set<String> writablePaths = new LinkedHashSet<>();
        writablePaths.addAll(normalize(spec.writablePaths()));
        writablePaths.addAll(runtimeWritablePaths());

        Set<String> executablePaths = new LinkedHashSet<>();
        executablePaths.addAll(normalize(spec.executablePaths()));

        CommandExecutionPolicy policy = new CommandExecutionPolicy(
                properties.isEnabled() ? properties.getMode() : "local",
                spec.networkEnabled(),
                timeoutMs,
                maxOutputBytes,
                properties.getMemoryMb(),
                properties.getCpuLimit(),
                properties.getContainerImage(),
                List.copyOf(readablePaths),
                List.copyOf(writablePaths),
                List.copyOf(executablePaths)
        );
        return new SandboxExecutionPlan(spec.subject(), policy, spec.notes());
    }

    private List<String> normalize(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return List.of();
        }
        return paths.stream()
                .filter(path -> path != null && !path.isBlank())
                .map(path -> Path.of(path).toAbsolutePath().normalize().toString())
                .distinct()
                .toList();
    }

    private List<String> runtimeWritablePaths() {
        String tmpDir = System.getenv("TMPDIR");
        return tmpDir == null || tmpDir.isBlank()
                ? List.of("/tmp", "/private/tmp", "/private/var/folders")
                : List.of(tmpDir, "/tmp", "/private/tmp", "/private/var/folders");
    }
}
