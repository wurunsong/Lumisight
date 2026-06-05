package com.lumisight.core.service.task;

import java.util.List;
import java.util.Map;

public record TaskCreateRequest(
        String subject,
        String description,
        List<String> blockedBy,
        Map<String, Object> metadata
) {
}
