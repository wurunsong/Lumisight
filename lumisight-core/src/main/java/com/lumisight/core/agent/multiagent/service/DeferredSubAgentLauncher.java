package com.lumisight.core.agent.multiagent.service;

import com.lumisight.core.agent.multiagent.model.SubAgentResult;
import com.lumisight.core.agent.multiagent.model.TaskContextEnvelope;
import com.lumisight.core.agent.multiagent.port.SubAgentLauncher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Spring 运行时适配器。
 * 工具注册只需要 SubAgentLauncher 这个端口；真正的 SubAgentExecutionService
 * 只有在 task_subagent 被调用时才解析，避免 registry -> tool -> sub-agent execution -> dispatcher -> loop -> registry 的初始化环。
 */
@Component
public class DeferredSubAgentLauncher implements SubAgentLauncher {

    private final ObjectProvider<SubAgentExecutionService> subAgentExecutionServiceProvider;

    public DeferredSubAgentLauncher(ObjectProvider<SubAgentExecutionService> subAgentExecutionServiceProvider) {
        this.subAgentExecutionServiceProvider = subAgentExecutionServiceProvider;
    }

    @Override
    public SubAgentResult execute(TaskContextEnvelope envelope, String parentSessionId) {
        return subAgentExecutionServiceProvider.getObject().execute(envelope, parentSessionId);
    }
}
