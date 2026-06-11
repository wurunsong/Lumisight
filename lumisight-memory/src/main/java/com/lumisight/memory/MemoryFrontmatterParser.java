package com.lumisight.memory;

import java.util.LinkedHashMap;
import java.util.Map;

import com.lumisight.memory.dto.MemoryWriteRequest;
import com.lumisight.memory.enums.MemoryType;

/**
 * 解析和渲染长期记忆 markdown 文档。
 * 文档格式要求：
 * 1. 文件必须以 YAML frontmatter 开头，并以第二个 `---` 结束。
 * 2. frontmatter 当前只解析简单的 `key: value` 单行字段，要求至少包含 `name`、`description`、`type`。
 * 3. frontmatter 之后的剩余 markdown 内容会整体作为 memory body。
 *
 * 示例：
 * ---
 * name: Coding Preference
 * description: User prefers root-cause fixes over workarounds.
 * type: user
 * ---
 *
 * 这里是长期记忆正文。
 */
final class MemoryFrontmatterParser {

    private MemoryFrontmatterParser() {
    }

    static ParsedMemoryDocument parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("memory file content is empty");
        }
        String normalized = raw.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) {
            throw new IllegalArgumentException("memory file must start with frontmatter");
        }
        int closing = normalized.indexOf("\n---\n", 4);
        if (closing < 0) {
            throw new IllegalArgumentException("memory file frontmatter is not closed");
        }
        String frontmatterBlock = normalized.substring(4, closing);
        String body = normalized.substring(closing + 5).trim();
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : frontmatterBlock.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = trimmed.substring(0, colon).trim();
            String value = trimmed.substring(colon + 1).trim();
            fields.put(key, unquote(value));
        }
        String name = fields.getOrDefault("name", "");
        String description = fields.getOrDefault("description", "");
        MemoryType type = MemoryType.parse(fields.get("type"));
        if (name.isBlank()) {
            throw new IllegalArgumentException("memory frontmatter.name is required");
        }
        if (description.isBlank()) {
            throw new IllegalArgumentException("memory frontmatter.description is required");
        }
        return new ParsedMemoryDocument(name, description, type, body);
    }

    static String render(MemoryWriteRequest request) {
        return "---\n"
                + "name: " + escape(request.name()) + "\n"
                + "description: " + escape(request.description()) + "\n"
                + "type: " + request.type().wireValue() + "\n"
                + "---\n\n"
                + request.body().trim()
                + "\n";
    }

    private static String unquote(String value) {
        if (value == null || value.length() < 2) {
            return value;
        }
        if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String escape(String value) {
        String text = value == null ? "" : value.trim();
        if (text.contains(":") || text.contains("#")) {
            return "\"" + text.replace("\"", "\\\"") + "\"";
        }
        return text;
    }

    record ParsedMemoryDocument(String name, String description, MemoryType type, String body) {
    }
}
