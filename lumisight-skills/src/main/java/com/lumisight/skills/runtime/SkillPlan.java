package com.lumisight.skills.runtime;

import java.util.List;

public record SkillPlan(
        String summary,
        List<String> executionSteps,
        String outputContract,
        String rawSkillContent
) {
}
