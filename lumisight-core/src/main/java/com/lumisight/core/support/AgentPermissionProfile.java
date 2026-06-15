package com.lumisight.core.support;

import com.lumisight.core.tool.AgentToolPermission;

import java.util.Set;

/**
 * 当前 agent 在本轮执行里拿到的工具权限画像。
 */
public record AgentPermissionProfile(Set<AgentToolPermission> enabledPermissions) {

    public AgentPermissionProfile {
        enabledPermissions = enabledPermissions == null ? Set.of() : Set.copyOf(enabledPermissions);
    }
}
