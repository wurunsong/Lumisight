package com.lumisight.memory;

import com.lumisight.common.LayerInfo;
import com.lumisight.memory.dto.MemoryEntry;
import com.lumisight.memory.dto.MemoryEntrypoint;
import com.lumisight.memory.dto.MemoryHeader;
import com.lumisight.memory.dto.MemoryWriteRequest;

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

    public MemoryEntry save(String storageRoot, String userId, String memoryRootDir, MemoryWriteRequest request) {
        return memoryFileService.write(storageRoot, userId, memoryRootDir, request);
    }

    public MemoryEntry update(String repoRoot, String userId, String filename, MemoryWriteRequest request) {
        return memoryFileService.update(repoRoot, userId, filename, request);
    }

    public MemoryEntry update(String storageRoot, String userId, String memoryRootDir, String filename, MemoryWriteRequest request) {
        return memoryFileService.update(storageRoot, userId, memoryRootDir, filename, request);
    }

    public List<MemoryHeader> list(String repoRoot, String userId) {
        return memoryFileService.list(repoRoot, userId);
    }

    public boolean delete(String repoRoot, String userId, String filename) {
        return memoryFileService.delete(repoRoot, userId, filename);
    }

    public boolean delete(String storageRoot, String userId, String memoryRootDir, String filename) {
        return memoryFileService.delete(storageRoot, userId, memoryRootDir, filename);
    }

    public MemoryEntrypoint loadEntrypoint(String repoRoot, String userId) {
        return memoryFileService.loadEntrypoint(repoRoot, userId);
    }

    public MemoryEntrypoint loadEntrypoint(String storageRoot, String userId, String memoryRootDir) {
        return memoryFileService.loadEntrypoint(storageRoot, userId, memoryRootDir);
    }

    public List<MemoryHeader> scanHeaders(String repoRoot, String userId) {
        return memoryFileService.scanHeaders(repoRoot, userId);
    }

    public List<MemoryHeader> scanHeaders(String storageRoot, String userId, String memoryRootDir) {
        return memoryFileService.scanHeaders(storageRoot, userId, memoryRootDir);
    }

    public List<MemoryEntry> readEntries(String repoRoot, String userId, List<String> filenames) {
        return memoryFileService.readEntries(repoRoot, userId, filenames);
    }

    public List<MemoryEntry> readEntries(String storageRoot, String userId, String memoryRootDir, List<String> filenames) {
        return memoryFileService.readEntries(storageRoot, userId, memoryRootDir, filenames);
    }

    public String renderSelectorManifest(String repoRoot, String userId) {
        return memoryFileService.renderSelectorManifest(repoRoot, userId);
    }

    public String freshnessText(long mtimeMs) {
        return memoryFileService.freshnessText(mtimeMs);
    }
}
