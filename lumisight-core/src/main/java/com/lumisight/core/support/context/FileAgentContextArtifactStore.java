package com.lumisight.core.support.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class FileAgentContextArtifactStore implements AgentContextArtifactStore {

    private final AgentContextManagementProperties properties;
    private final ObjectMapper objectMapper;

    public FileAgentContextArtifactStore(AgentContextManagementProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentContextArtifactRef persist(String sessionId, String entryId, String content, Map<String, Object> metadata) {
        try {
            Path root = Path.of("").toAbsolutePath().normalize();
            Path artifactRoot = root.resolve(properties.getArtifactDir()).normalize();
            Files.createDirectories(artifactRoot);
            String normalizedSession = StringUtils.hasText(sessionId) ? sessionId : "anonymous";
            String artifactId = Instant.now().toEpochMilli() + "-" + UUID.randomUUID();
            Path sessionDir = artifactRoot.resolve(normalizedSession).normalize();
            Files.createDirectories(sessionDir);
            Path artifactDir = sessionDir.resolve(artifactId).normalize();
            Files.createDirectories(artifactDir);
            String preview = preview(content, properties.getPreviewBytes());
            Files.writeString(artifactDir.resolve("content.txt"), content == null ? "" : content, StandardCharsets.UTF_8);
            Files.writeString(artifactDir.resolve("preview.txt"), preview, StandardCharsets.UTF_8);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("sessionId", normalizedSession);
            meta.put("entryId", entryId);
            meta.put("contentBytes", content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length);
            meta.put("previewBytes", preview.getBytes(StandardCharsets.UTF_8).length);
            if (metadata != null && !metadata.isEmpty()) {
                meta.put("metadata", metadata);
            }
            Files.writeString(artifactDir.resolve("meta.json"), objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(meta), StandardCharsets.UTF_8);
            return new AgentContextArtifactRef(
                    artifactId,
                    root.relativize(artifactDir.resolve("content.txt")).toString(),
                    content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length,
                    preview.getBytes(StandardCharsets.UTF_8).length
            );
        } catch (Exception e) {
            throw new IllegalStateException("保存上下文 artifact 失败: " + e.getMessage(), e);
        }
    }

    private String preview(String content, int maxBytes) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maxBytes) {
            return content;
        }
        return new String(bytes, 0, maxBytes, StandardCharsets.UTF_8) + "\n...(artifact preview truncated)";
    }
}
