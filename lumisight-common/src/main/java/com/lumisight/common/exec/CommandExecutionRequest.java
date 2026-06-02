package com.lumisight.common.exec;

import java.nio.file.Path;
import java.util.List;

public record CommandExecutionRequest(
        Path workingDir,
        List<String> command,
        String stdin,
        CommandExecutionPolicy policy
) {
}
