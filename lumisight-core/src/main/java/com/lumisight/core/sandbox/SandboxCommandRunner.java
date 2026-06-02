package com.lumisight.core.sandbox;

import com.lumisight.common.exec.CommandExecutionPolicy;
import com.lumisight.common.exec.CommandExecutionRequest;
import com.lumisight.common.exec.CommandExecutionResult;
import com.lumisight.common.exec.SandboxCommandExecutor;
import com.lumisight.common.exec.SandboxProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
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
        CommandExecutionResult result = commandExecutor.run(new CommandExecutionRequest(
                workingDir,
                command,
                null,
                new CommandExecutionPolicy(
                        properties.isNetworkEnabled(),
                        Math.max(1, timeoutSeconds) * 1000L,
                        maxOutputBytes
                )
        ));
        return Map.of(
                "success", result.success(),
                "exitCode", result.exitCode(),
                "timedOut", result.timedOut(),
                "output", result.output()
        );
    }
}
