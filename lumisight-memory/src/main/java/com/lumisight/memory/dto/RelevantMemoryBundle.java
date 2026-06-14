package com.lumisight.memory.dto;

import java.util.List;

public record RelevantMemoryBundle(
        MemoryEntrypoint entrypoint,
        List<MemoryEntry> selectedEntries,
        String remindersBlock
) {

    public static RelevantMemoryBundle empty() {
        return new RelevantMemoryBundle(MemoryEntrypoint.empty("# MEMORY\n\n- 暂无长期记忆"), List.of(), "");
    }
}
