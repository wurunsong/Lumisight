package com.lumisight.core.agent.model;

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
