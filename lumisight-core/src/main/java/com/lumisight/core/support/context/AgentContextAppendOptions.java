package com.lumisight.core.support.context;

public record AgentContextAppendOptions(
        AgentContextEntryKind kind,
        boolean compactable,
        boolean retriable,
        String toolName,
        int priority
) {

    public static AgentContextAppendOptions conversation() {
        return new AgentContextAppendOptions(AgentContextEntryKind.CONVERSATION, false, false, null, 50);
    }

    public static AgentContextAppendOptions verifier() {
        return new AgentContextAppendOptions(AgentContextEntryKind.VERIFIER, false, false, null, 80);
    }

    public static AgentContextAppendOptions todo() {
        return new AgentContextAppendOptions(AgentContextEntryKind.TODO, false, false, "todo_write", 90);
    }

    public static AgentContextAppendOptions system() {
        return new AgentContextAppendOptions(AgentContextEntryKind.SYSTEM, false, false, null, 85);
    }
}
