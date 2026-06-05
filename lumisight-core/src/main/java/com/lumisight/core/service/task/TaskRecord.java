package com.lumisight.core.service.task;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskRecord(
        String id,
        String subject,
        String description,
        TaskStatus status,
        String owner,
        List<String> blockedBy,
        List<String> blocks,
        Map<String, Object> metadata,
        long createdAt,
        long updatedAt
) {

    public TaskRecord {
        id = id == null ? "" : id.trim();
        subject = subject == null ? "" : subject.trim();
        description = description == null ? "" : description.trim();
        status = status == null ? TaskStatus.PENDING : status;
        owner = owner == null || owner.isBlank() ? null : owner.trim();
        blockedBy = normalizeIds(blockedBy);
        blocks = normalizeIds(blocks);
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }

    public TaskRecord withStatus(TaskStatus nextStatus, String nextOwner, long timestamp) {
        return new TaskRecord(id, subject, description, nextStatus, nextOwner, blockedBy, blocks, metadata, createdAt, timestamp);
    }

    public TaskRecord withBlocks(List<String> nextBlocks, long timestamp) {
        return new TaskRecord(id, subject, description, status, owner, blockedBy, nextBlocks, metadata, createdAt, timestamp);
    }

    public static TaskRecord create(
            String id,
            String subject,
            String description,
            List<String> blockedBy,
            Map<String, Object> metadata,
            long now
    ) {
        return new TaskRecord(id, subject, description, TaskStatus.PENDING, null, blockedBy, List.of(), metadata, now, now);
    }

    private static List<String> normalizeIds(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.trim());
            }
        }
        return List.copyOf(normalized);
    }
}
