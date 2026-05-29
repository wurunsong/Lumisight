package com.lumisight.skills.runtime;

import java.util.List;

public record SkillPlan(
        String summary,
        List<String> preferredTools
) {
}
