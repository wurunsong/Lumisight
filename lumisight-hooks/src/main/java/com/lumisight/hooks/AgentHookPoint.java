package com.lumisight.hooks;

public enum AgentHookPoint {
    BEFORE_PLAN,
    AFTER_PLAN,
    BEFORE_DECISION,
    AFTER_DECISION,
    BEFORE_TOOL_CALL,
    AFTER_TOOL_CALL,
    ON_ASK_USER,
    BEFORE_FINAL,
    ON_ERROR
}
