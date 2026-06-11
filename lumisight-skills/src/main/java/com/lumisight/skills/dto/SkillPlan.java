package com.lumisight.skills.dto;

import java.util.List;

public record SkillPlan(
        String summary,
        List<String> executionSteps,
        String outputContract,
        String rawSkillContent
) {
}
