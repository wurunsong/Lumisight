package com.lumisight.core.support.memory;

public record RelevantMemorySource(
        String sourceId,
        String displayName,
        String storageRoot,
        String memoryRootDir
) {
}
