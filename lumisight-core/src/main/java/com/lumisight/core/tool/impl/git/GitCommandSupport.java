package com.lumisight.core.tool.impl.git;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

final class GitCommandSupport {

    private GitCommandSupport() {
    }

    static Map<String, Object> run(Path repoRoot, List<String> command) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(repoRoot.toFile());
            builder.redirectErrorStream(true);
            Process process = builder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            int exitCode = process.waitFor();
            return Map.of(
                    "success", exitCode == 0,
                    "exitCode", exitCode,
                    "output", output.toString()
            );
        } catch (Exception e) {
            return Map.of(
                    "success", false,
                    "exitCode", -1,
                    "output", "git 执行异常: " + e.getMessage()
            );
        }
    }
}
