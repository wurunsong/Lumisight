package com.lumisight.tools.kg.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.lumisight.tools.kg.model.GraphSnapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class GraphSnapshotStore {

    private final ObjectMapper objectMapper;

    public GraphSnapshotStore() {
        // 注册 JavaTime 模块并关闭时间戳输出，保证快照时间字段可读。
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public GraphSnapshot load(Path snapshotPath) {
        // 首次构建时快照文件不存在，返回空快照作为初始状态。
        if (!Files.exists(snapshotPath)) {
            return new GraphSnapshot();
        }
        try {
            return objectMapper.readValue(snapshotPath.toFile(), GraphSnapshot.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load snapshot: " + snapshotPath, e);
        }
    }

    public void save(Path snapshotPath, GraphSnapshot snapshot) {
        try {
            // 自动创建父目录，避免首次写快照时目录缺失。
            if (snapshotPath.getParent() != null) {
                Files.createDirectories(snapshotPath.getParent());
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(snapshotPath.toFile(), snapshot);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save snapshot: " + snapshotPath, e);
        }
    }
}
