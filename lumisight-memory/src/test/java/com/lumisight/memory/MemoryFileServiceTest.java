package com.lumisight.memory;

import com.lumisight.memory.dto.MemoryEntry;
import com.lumisight.memory.dto.MemoryHeader;
import com.lumisight.memory.dto.MemoryWriteRequest;
import com.lumisight.memory.enums.MemoryType;
import com.lumisight.memory.properties.MemoryProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryFileServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void scanHeaders_archivesExpiredProjectMemory() throws Exception {
        MemoryFileService service = new MemoryFileService(memoryProperties());
        String storageRoot = tempDir.toString();
        String userId = "tester";
        String memoryRootDir = ".lumisight/memory";

        MemoryEntry entry = service.write(
                storageRoot,
                userId,
                memoryRootDir,
                new MemoryWriteRequest("Old Project Fact", "project memory", MemoryType.PROJECT, "body")
        );
        Path userDir = tempDir.resolve(".lumisight/memory").resolve(userId);
        Path memoryFile = userDir.resolve(entry.filename());
        Files.setLastModifiedTime(memoryFile, FileTime.from(Instant.now().minus(45, ChronoUnit.DAYS)));

        List<MemoryHeader> headers = service.scanHeaders(storageRoot, userId, memoryRootDir);

        assertTrue(headers.isEmpty());
        assertFalse(Files.exists(memoryFile));
        assertTrue(Files.exists(userDir.resolve(".forgotten").resolve(entry.filename())));
    }

    @Test
    void scanHeaders_keepsExpiredUserMemoryOutOfAutoArchive() throws Exception {
        MemoryFileService service = new MemoryFileService(memoryProperties());
        String storageRoot = tempDir.toString();
        String userId = "tester";
        String memoryRootDir = ".lumisight/memory";

        MemoryEntry entry = service.write(
                storageRoot,
                userId,
                memoryRootDir,
                new MemoryWriteRequest("Stable Preference", "user memory", MemoryType.USER, "body")
        );
        Path userDir = tempDir.resolve(".lumisight/memory").resolve(userId);
        Path memoryFile = userDir.resolve(entry.filename());
        Files.setLastModifiedTime(memoryFile, FileTime.from(Instant.now().minus(45, ChronoUnit.DAYS)));

        List<MemoryHeader> headers = service.scanHeaders(storageRoot, userId, memoryRootDir);

        assertEquals(1, headers.size());
        assertEquals(entry.filename(), headers.get(0).filename());
        assertTrue(Files.exists(memoryFile));
        assertFalse(Files.exists(userDir.resolve(".forgotten").resolve(entry.filename())));
    }

    @Test
    void retrievalFreshnessWeight_decaysAfterSoftForgetWindow() {
        MemoryFileService service = new MemoryFileService(memoryProperties());

        double freshWeight = service.retrievalFreshnessWeight(MemoryType.PROJECT, Instant.now().toEpochMilli());
        double softForgottenWeight = service.retrievalFreshnessWeight(MemoryType.PROJECT, Instant.now().minus(12, ChronoUnit.DAYS).toEpochMilli());
        double nearlyExpiredWeight = service.retrievalFreshnessWeight(MemoryType.PROJECT, Instant.now().minus(29, ChronoUnit.DAYS).toEpochMilli());

        assertEquals(1.0D, freshWeight, 0.0001D);
        assertTrue(softForgottenWeight < freshWeight);
        assertTrue(nearlyExpiredWeight < softForgottenWeight);
    }

    @Test
    void retrievalFreshnessWeight_doesNotDecayUserMemory() {
        MemoryFileService service = new MemoryFileService(memoryProperties());

        double oldUserWeight = service.retrievalFreshnessWeight(MemoryType.USER, Instant.now().minus(90, ChronoUnit.DAYS).toEpochMilli());
        double oldFeedbackWeight = service.retrievalFreshnessWeight(MemoryType.FEEDBACK, Instant.now().minus(90, ChronoUnit.DAYS).toEpochMilli());

        assertEquals(1.0D, oldUserWeight, 0.0001D);
        assertEquals(1.0D, oldFeedbackWeight, 0.0001D);
    }

    private MemoryProperties memoryProperties() {
        MemoryProperties properties = new MemoryProperties();
        properties.setAutoArchiveEnabled(true);
        properties.setArchiveDirName(".forgotten");
        properties.setSoftForgetAfterDays(7);
        properties.setHardForgetAfterDays(30);
        properties.setAutoForgetTypes(List.of("project", "reference"));
        return properties;
    }
}
