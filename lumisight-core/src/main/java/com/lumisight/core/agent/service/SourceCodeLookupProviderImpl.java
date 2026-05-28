package com.lumisight.core.agent.service;

import com.lumisight.core.agent.model.AgentContextItem;
import com.lumisight.core.agent.port.SourceCodeLookupProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnMissingBean(SourceCodeLookupProvider.class)
public class SourceCodeLookupProviderImpl implements SourceCodeLookupProvider {

    @Override
    public List<AgentContextItem> lookupMethodSource(String repoRoot, String sourceFile, Integer startLine, Integer endLine) {
        if (sourceFile == null || sourceFile.isBlank()) {
            return List.of(new AgentContextItem(
                    "source_code",
                    "invalid_source_file",
                    "sourceFile 不能为空",
                    Map.of("repoRoot", repoRoot)
            ));
        }
        int from = startLine == null ? 1 : Math.max(1, startLine);
        int to = endLine == null ? from + 200 : Math.max(from, endLine);

        Path repo = Path.of(repoRoot).toAbsolutePath().normalize();
        Path file = repo.resolve(sourceFile).normalize();
        if (!file.startsWith(repo) || !Files.exists(file) || !Files.isRegularFile(file)) {
            return List.of(new AgentContextItem(
                    "source_code",
                    sourceFile,
                    "源码文件不存在或路径非法",
                    Map.of("repoRoot", repo.toString(), "sourceFile", sourceFile)
            ));
        }

        StringBuilder snippet = new StringBuilder();
        int lineNo = 0;
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (lineNo < from) {
                    continue;
                }
                if (lineNo > to) {
                    break;
                }
                snippet.append(lineNo).append(": ").append(line).append("\n");
            }
        } catch (Exception e) {
            return List.of(new AgentContextItem(
                    "source_code",
                    sourceFile,
                    "读取源码失败: " + e.getMessage(),
                    Map.of("repoRoot", repo.toString(), "sourceFile", sourceFile)
            ));
        }

        if (snippet.isEmpty()) {
            return List.of(new AgentContextItem(
                    "source_code",
                    sourceFile,
                    "未读取到指定行范围的源码",
                    Map.of("repoRoot", repo.toString(), "sourceFile", sourceFile, "startLine", from, "endLine", to)
            ));
        }

        List<AgentContextItem> result = new ArrayList<>();
        result.add(new AgentContextItem(
                "source_code",
                sourceFile + ":" + from + "-" + to,
                snippet.toString(),
                Map.of(
                        "repoRoot", repo.toString(),
                        "sourceFile", sourceFile,
                        "startLine", from,
                        "endLine", to
                )
        ));
        return result;
    }
}
