package com.lumisight.memory.dto;

import java.util.List;

public record RelevantMemoryContext(
        MemoryEntrypoint entrypoint,
        List<MemoryEntry> selectedEntries,
        String remindersBlock
) {

    public static RelevantMemoryContext empty() {
        return new RelevantMemoryContext(MemoryEntrypoint.empty("# MEMORY\n\n- 暂无长期记忆"), List.of(), "");
    }
}
