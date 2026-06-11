package com.lumisight.skills.runtime;

import java.nio.file.Path;

public record RegisteredSkill(
        String id,
        String name,
        String summary,
        Path path
) {
}

