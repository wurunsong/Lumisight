package com.lumisight.core.sandbox;

import com.lumisight.common.exec.CommandExecutionPolicy;
import com.lumisight.common.exec.CommandExecutionRequest;
import com.lumisight.common.exec.CommandExecutionResult;
import com.lumisight.common.exec.SandboxAccessSpec;
import com.lumisight.common.exec.SandboxCommandExecutor;
import com.lumisight.common.exec.SandboxExecutionPlan;
import com.lumisight.common.exec.SandboxPolicyPlanner;
import com.lumisight.common.exec.SandboxProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@Component
public class SandboxCommandRunner {

    private final SandboxProperties properties;
    private final SandboxCommandExecutor commandExecutor = new SandboxCommandExecutor();
    private final SandboxPolicyPlanner policyPlanner = new SandboxPolicyPlanner();

    public SandboxCommandRunner(SandboxProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> run(Path workingDir, List<String> command) {
        return run(workingDir, command, new SandboxAccessSpec(
                "command",
                properties.isNetworkEnabled(),
                List.of(workingDir.toAbsolutePath().normalize().toString()),
                List.of(),
                List.of(),
                List.of("fallback to workingDir-level access because no tool-specific sandbox plan was provided")
        ));
    }

    public Map<String, Object> run(Path workingDir, List<String> command, SandboxAccessSpec accessSpec) {
        SandboxExecutionPlan plan = policyPlanner.plan(
                workingDir,
                properties,
                accessSpec,
                Math.max(1, properties.getTimeoutSeconds()) * 1000L,
                properties.getMaxOutputBytes()
        );
        CommandExecutionResult result = commandExecutor.run(new CommandExecutionRequest(
                workingDir,
                command,
                null,
                plan.policy()
        ));
        return Map.of(
                "success", result.success(),
                "exitCode", result.exitCode(),
                "timedOut", result.timedOut(),
                "output", result.output(),
                "sandboxPlan", Map.of(
                        "subject", plan.subject(),
                        "mode", plan.policy().mode(),
                        "networkEnabled", plan.policy().networkEnabled(),
                        "readablePaths", plan.policy().readablePaths(),
                        "writablePaths", plan.policy().writablePaths(),
                        "executablePaths", plan.policy().executablePaths(),
                        "notes", plan.notes()
                )
        );
    }
}
