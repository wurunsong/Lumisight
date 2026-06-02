package com.lumisight.core.sandbox;

import com.lumisight.common.exec.CommandExecutionPolicy;
import com.lumisight.common.exec.CommandExecutionRequest;
import com.lumisight.common.exec.CommandExecutionResult;
import com.lumisight.common.exec.SandboxCommandExecutor;
import com.lumisight.common.exec.SandboxProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Component
public class SandboxCommandRunner {

    private final SandboxProperties properties;
    private final SandboxCommandExecutor commandExecutor = new SandboxCommandExecutor();

    public SandboxCommandRunner(SandboxProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> run(Path workingDir, List<String> command) {
        CommandExecutionResult result = commandExecutor.run(new CommandExecutionRequest(
                workingDir,
                command,
                null,
                new CommandExecutionPolicy(
                        properties.isEnabled() ? properties.getMode() : "local",
                        properties.isNetworkEnabled(),
                        Math.max(1, properties.getTimeoutSeconds()) * 1000L,
                        properties.getMaxOutputBytes(),
                        properties.getMemoryMb(),
                        properties.getCpuLimit(),
                        properties.getContainerImage(),
                        List.of(workingDir.toAbsolutePath().normalize().toString()),
                        writablePaths(workingDir)
                )
        ));
        return Map.of(
                "success", result.success(),
                "exitCode", result.exitCode(),
                "timedOut", result.timedOut(),
                "output", result.output()
        );
    }

    private List<String> writablePaths(Path workingDir) {
        String tmpDir = System.getenv("TMPDIR");
        return tmpDir == null || tmpDir.isBlank()
                ? List.of("/tmp", "/private/tmp")
                : List.of(tmpDir, "/tmp", "/private/tmp");
    }
}
