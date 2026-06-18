package com.lumisight.core.support;

import com.lumisight.core.agent.multiagent.model.SubAgentCapability;
import com.lumisight.core.agent.multiagent.service.ChildAgentPermissionPolicy;
import com.lumisight.core.context.ambient.MultiAgentExecutionScope;
import com.lumisight.core.model.AgentRequest;
import com.lumisight.core.tool.AgentToolPermission;
import org.springframework.stereotype.Component;

import java.util.EnumSet;

/**
 * 统一解析当前 agent 的权限画像。
 * 单 agent、lead 收敛阶段、sub agent 都在这里收口，避免权限规则散落在执行流程中。
 */
@Component
public class AgentPermissionProfileResolver {

    private static final EnumSet<AgentToolPermission> BASE_TOOL_PERMISSIONS = EnumSet.of(
            AgentToolPermission.LOCAL_FS_READ,
            AgentToolPermission.GIT_READ,
            AgentToolPermission.BROWSER_READ,
            AgentToolPermission.MEMORY_READ,
            AgentToolPermission.MEMORY_WRITE,
            AgentToolPermission.TODO_WRITE,
            AgentToolPermission.AGENT_SPAWN
    );
    // TODO(network-tools): wire direct HTTP/network permissions here once http_get/http_post tools exist.
    // Browser permissions only cover page automation and should not imply raw network access.
    private static final EnumSet<AgentToolPermission> SINGLE_AGENT_WRITE_PERMISSIONS = EnumSet.of(
            AgentToolPermission.LOCAL_FS_WRITE,
            AgentToolPermission.BROWSER_WRITE
    );
    private static final EnumSet<AgentToolPermission> LEAD_CONVERGENCE_WRITE_PERMISSIONS = EnumSet.of(
            AgentToolPermission.LOCAL_FS_WRITE,
            AgentToolPermission.BROWSER_WRITE
    );

    private final ChildAgentPermissionPolicy childAgentPermissionPolicy;

    public AgentPermissionProfileResolver(ChildAgentPermissionPolicy childAgentPermissionPolicy) {
        this.childAgentPermissionPolicy = childAgentPermissionPolicy;
    }

    public AgentPermissionProfile resolve(AgentRequest request) {
        MultiAgentExecutionScope.Context multiAgentScope = MultiAgentExecutionScope.current();
        if (multiAgentScope != null && multiAgentScope.role() != MultiAgentExecutionScope.Role.LEAD_AGENT) {
            // fan-out 期间的 sub-agent 一律走只读子权限，不允许因为任务类型不同而拿到写权限。
            return new AgentPermissionProfile(childAgentPermissionPolicy.permissionsFor(
                    capabilityFromTaskType(request)
            ));
        }
        EnumSet<AgentToolPermission> permissions = EnumSet.noneOf(AgentToolPermission.class);
        permissions.addAll(BASE_TOOL_PERMISSIONS);
        // 只有普通 single-agent 可以带写权限进入执行；真正写盘时仍然需要后续 human gate。
        if (request.runMode() != null && request.runMode().name().equals("NORMAL")) {
            permissions.addAll(SINGLE_AGENT_WRITE_PERMISSIONS);
        }
        if (multiAgentScope != null
                && multiAgentScope.role() == MultiAgentExecutionScope.Role.LEAD_AGENT
                && multiAgentScope.phase() == MultiAgentExecutionScope.Phase.CONVERGENCE) {
            // 多-agent 的 lead 只有在 fan-in 完成后的收敛阶段才恢复写权限，同时移除继续 spawn 的能力。
            permissions.addAll(LEAD_CONVERGENCE_WRITE_PERMISSIONS);
            permissions.remove(AgentToolPermission.AGENT_SPAWN);
        }
        if (request.includeRagContext()) {
            permissions.add(AgentToolPermission.HYBRID_VECTOR_READ);
        }
        if (request.includeKnowledgeGraphContext()) {
            permissions.add(AgentToolPermission.KG_ONE_HOP_READ);
            permissions.add(AgentToolPermission.METHOD_SOURCE_READ);
        }
        if (request.taskType() != null && request.taskType().name().equals("BUG_FIX")) {
            permissions.add(AgentToolPermission.LSP_JAVA_READ);
            permissions.add(AgentToolPermission.BUILD_COMPILE);
        }
        return new AgentPermissionProfile(permissions);
    }

    private SubAgentCapability capabilityFromTaskType(AgentRequest request) {
        if (request == null || request.taskType() == null) {
            return SubAgentCapability.CODE_EXPLAIN;
        }
        return switch (request.taskType()) {
            case BUG_FIX -> SubAgentCapability.BUG_FIX;
            case CHAT, CODE_EXPLAIN -> SubAgentCapability.CODE_EXPLAIN;
        };
    }
}
