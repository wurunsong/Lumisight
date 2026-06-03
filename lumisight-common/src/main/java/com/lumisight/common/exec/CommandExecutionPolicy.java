package com.lumisight.common.exec;

import java.util.List;

public record CommandExecutionPolicy(
        String mode,
        boolean networkEnabled,
        long timeoutMs,
        int maxOutputBytes,
        long memoryMb,
        double cpuLimit,
        String containerImage,
        List<String> readablePaths,
        List<String> writablePaths,
        List<String> executablePaths
) {
    public CommandExecutionPolicy {
        mode = (mode == null || mode.isBlank()) ? "local" : mode.trim();
        timeoutMs = Math.max(1000L, timeoutMs);
        maxOutputBytes = Math.max(1024, maxOutputBytes);
        memoryMb = Math.max(128L, memoryMb);
        cpuLimit = Math.max(0.1d, cpuLimit);
        containerImage = (containerImage == null || containerImage.isBlank())
                ? "docker.io/library/openjdk:21-jdk"
                : containerImage.trim();
        readablePaths = readablePaths == null ? List.of() : List.copyOf(readablePaths);
        writablePaths = writablePaths == null ? List.of() : List.copyOf(writablePaths);
        executablePaths = executablePaths == null ? List.of() : List.copyOf(executablePaths);
    }
}
