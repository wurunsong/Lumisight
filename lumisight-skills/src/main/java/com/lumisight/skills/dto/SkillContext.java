package com.lumisight.skills.runtime;

import java.util.Map;

public record SkillContext(
        String taskType,
        String dialogueMode,
        String runMode,
        String question,
        Map<String, Object> metadata
) {
}
