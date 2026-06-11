package com.lumisight.memory.dto;

public record MemoryEntrypoint(
        String content,
        boolean lineTruncated,
        boolean byteTruncated,
        int lineCount,
        int byteCount
) {

    public static MemoryEntrypoint empty(String content) {
        int bytes = content == null ? 0 : content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        int lines = content == null || content.isEmpty() ? 0 : content.split("\\R", -1).length;
        return new MemoryEntrypoint(content, false, false, lines, bytes);
    }
}
