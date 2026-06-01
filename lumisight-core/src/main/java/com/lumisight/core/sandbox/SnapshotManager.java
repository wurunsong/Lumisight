package com.lumisight.core.sandbox;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

@Component
public class SnapshotManager {

    private final SandboxProperties properties;

    public SnapshotManager(SandboxProperties properties) {
        this.properties = properties;
    }

    public String snapshotBeforeWrite(Path repoRoot, Path targetFile) {
        try {
            Path normalizedRoot = repoRoot.toAbsolutePath().normalize();
            Path normalizedTarget = targetFile.toAbsolutePath().normalize();
            if (!normalizedTarget.startsWith(normalizedRoot)) {
                throw new IllegalArgumentException("目标文件不在仓库内");
            }
            String snapshotId = Instant.now().toEpochMilli() + "-" + UUID.randomUUID();
            Path relativeFile = normalizedRoot.relativize(normalizedTarget);
            Path snapshotBase = normalizedRoot.resolve(properties.getSnapshotDir()).resolve(snapshotId);
            Files.createDirectories(snapshotBase);
            Path backupFile = snapshotBase.resolve("backup.bin");
            boolean existed = Files.exists(normalizedTarget);
            if (existed) {
                Files.copy(normalizedTarget, backupFile, StandardCopyOption.REPLACE_EXISTING);
            }
            Properties meta = new Properties();
            meta.setProperty("relativePath", relativeFile.toString());
            meta.setProperty("existed", String.valueOf(existed));
            Files.writeString(snapshotBase.resolve("meta.properties"), toText(meta), StandardCharsets.UTF_8);
            return snapshotId;
        } catch (Exception e) {
            throw new IllegalStateException("创建快照失败: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> rollback(Path repoRoot, String snapshotId) {
        try {
            Path root = repoRoot.toAbsolutePath().normalize();
            Path snapshotBase = root.resolve(properties.getSnapshotDir()).resolve(snapshotId).normalize();
            Path snapshotRoot = root.resolve(properties.getSnapshotDir()).normalize();
            if (!snapshotBase.startsWith(snapshotRoot) || !Files.exists(snapshotBase)) {
                throw new IllegalArgumentException("snapshot 不存在");
            }
            Properties meta = new Properties();
            meta.load(Files.newBufferedReader(snapshotBase.resolve("meta.properties"), StandardCharsets.UTF_8));
            String relativePath = meta.getProperty("relativePath", "");
            boolean existed = Boolean.parseBoolean(meta.getProperty("existed", "false"));
            Path target = root.resolve(relativePath).normalize();
            if (!target.startsWith(root)) {
                throw new IllegalArgumentException("snapshot 元数据非法");
            }
            if (target.getParent() != null && !Files.exists(target.getParent())) {
                Files.createDirectories(target.getParent());
            }
            if (existed) {
                Path backup = snapshotBase.resolve("backup.bin");
                Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.deleteIfExists(target);
            }
            return Map.of(
                    "success", true,
                    "snapshotId", snapshotId,
                    "relativePath", relativePath,
                    "restored", existed
            );
        } catch (Exception e) {
            return Map.of(
                    "success", false,
                    "snapshotId", snapshotId,
                    "error", e.getMessage()
            );
        }
    }

    private String toText(Properties properties) throws Exception {
        StringBuilder builder = new StringBuilder();
        for (String name : properties.stringPropertyNames()) {
            builder.append(name).append("=").append(properties.getProperty(name)).append("\n");
        }
        return builder.toString();
    }
}
