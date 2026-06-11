package com.lumisight.skills.runtime;

import java.util.List;

public record ParsedSkillDocument(
        String name,
        String summary,
        List<String> executionSteps,
        String outputContract
) {
}
