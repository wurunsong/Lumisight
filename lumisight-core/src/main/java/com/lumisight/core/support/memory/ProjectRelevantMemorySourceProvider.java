package com.lumisight.core.support.memory;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
public class ProjectRelevantMemorySourceProvider implements RelevantMemorySourceProvider {

    @Override
    public List<RelevantMemorySource> resolveSources(String repoRoot, String userId, String query) {
        if (!StringUtils.hasText(repoRoot)) {
            return List.of();
        }
        // 示例路径: <repoRoot>/.lumisight/memory/<userId>/MEMORY.md
        return List.of(new RelevantMemorySource(
                "project",
                "项目长期记忆",
                repoRoot.trim(),
                ".lumisight/memory"
        ));
    }
}
