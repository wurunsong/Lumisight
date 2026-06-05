package com.lumisight.memory;

import com.lumisight.common.LayerInfo;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MemoryService {

    private final MemoryFileService memoryFileService;

    public MemoryService(MemoryFileService memoryFileService) {
        this.memoryFileService = memoryFileService;
    }

    public LayerInfo describe() {
        return new LayerInfo("lumisight-memory", "负责四分类长期记忆（user/feedback/project/reference）的存储、索引与召回");
    }

    public MemoryEntry save(String repoRoot, String userId, MemoryWriteRequest request) {
        return memoryFileService.write(repoRoot, userId, request);
    }

    public List<MemoryHeader> list(String repoRoot, String userId) {
        return memoryFileService.list(repoRoot, userId);
    }

    public boolean delete(String repoRoot, String userId, String filename) {
        return memoryFileService.delete(repoRoot, userId, filename);
    }

    public MemoryEntrypoint loadEntrypoint(String repoRoot, String userId) {
        return memoryFileService.loadEntrypoint(repoRoot, userId);
    }

    public List<MemoryHeader> scanHeaders(String repoRoot, String userId) {
        return memoryFileService.scanHeaders(repoRoot, userId);
    }

    public List<MemoryEntry> readEntries(String repoRoot, String userId, List<String> filenames) {
        return memoryFileService.readEntries(repoRoot, userId, filenames);
    }

    public String renderSelectorManifest(String repoRoot, String userId) {
        return memoryFileService.renderSelectorManifest(repoRoot, userId);
    }

    public String freshnessText(long mtimeMs) {
        return memoryFileService.freshnessText(mtimeMs);
    }
}
