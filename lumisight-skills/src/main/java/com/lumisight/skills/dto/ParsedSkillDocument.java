package com.lumisight.skills.dto;

import java.util.List;

public record ParsedSkillDocument(
        String name,
        String summary,
        List<String> executionSteps,
        String outputContract
) {
}
