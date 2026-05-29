package com.lumisight.core.model;

public enum AgentLoopState {
    INIT,
    PLAN,
    DECIDE,
    TOOL_CALL,
    ASK_USER,
    FINAL,
    INTERRUPTED,
    ERROR
}
