package com.lumisight.core.agent.multiagent.service;

import com.lumisight.core.agent.multiagent.model.SubAgentCapability;
import com.lumisight.core.context.ambient.MultiAgentExecutionContext;
import com.lumisight.core.tool.AgentToolPermission;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

@Component
public class ChildAgentPermissionPolicy {

    public Set<AgentToolPermission> permissionsFor(MultiAgentExecutionContext.Role role, SubAgentCapability capability) {
        EnumSet<AgentToolPermission> permissions = EnumSet.of(
                AgentToolPermission.LOCAL_FS_READ,
                AgentToolPermission.GIT_READ,
                AgentToolPermission.MEMORY_READ
        );
        switch (capability) {
            case BUG_FIX, BUILD_ANALYSIS, TEST_ANALYSIS -> {
                permissions.add(AgentToolPermission.LSP_JAVA_READ);
                permissions.add(AgentToolPermission.BUILD_COMPILE);
            }
            case CODE_EXPLAIN, REFACTOR -> permissions.add(AgentToolPermission.LSP_JAVA_READ);
            case GIT_ANALYSIS -> {
            }
        }
        permissions.add(AgentToolPermission.HYBRID_VECTOR_READ);
        permissions.add(AgentToolPermission.KG_ONE_HOP_READ);
        permissions.add(AgentToolPermission.METHOD_SOURCE_READ);
        permissions.add(AgentToolPermission.BROWSER_READ);
        return permissions;
    }
}
