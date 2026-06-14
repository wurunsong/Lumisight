package com.lumisight.core.support.memory;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.List;

@Component
public class UserProfileRelevantMemorySourceProvider implements RelevantMemorySourceProvider {

    @Override
    public List<RelevantMemorySource> resolveSources(String repoRoot, String userId, String query) {
        String userHome = System.getProperty("user.home");
        if (!StringUtils.hasText(userHome)) {
            return List.of();
        }
        // 示例路径: ~/.lumisight/<userId>/MEMORY.md
        return List.of(new RelevantMemorySource(
                "user_profile",
                "用户画像长期记忆",
                Path.of(userHome, ".lumisight").toAbsolutePath().normalize().toString(),
                ""
        ));
    }
}
