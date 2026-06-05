package com.lumisight.core.agent.multiagent.service;

import com.lumisight.core.model.AgentRequest;
import com.lumisight.core.model.AgentRunMode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class MultiAgentModeDecider {

    private final MultiAgentProperties properties;

    public MultiAgentModeDecider(MultiAgentProperties properties) {
        this.properties = properties;
    }

    public Decision decide(AgentRequest request, String effectiveQuestion) {
        if (!properties.isEnabled()) {
            return new Decision(AgentRunMode.NORMAL, false, "multi-agent disabled");
        }
        if (request.runMode() == AgentRunMode.MULTI_AGENT) {
            return new Decision(AgentRunMode.MULTI_AGENT, true, "explicit runMode");
        }
        if (!properties.isAutoUpgradeEnabled() || !StringUtils.hasText(effectiveQuestion)) {
            return new Decision(request.runMode(), false, "auto-upgrade disabled");
        }
        String question = effectiveQuestion.trim();
        int signals = 0;
        if (question.length() >= 120) {
            signals++;
        }
        if (question.contains("同时") || question.contains("分别") || question.contains("并行")
                || question.contains("拆解") || question.contains("多个模块") || question.contains("多个文件")) {
            signals++;
        }
        if (question.contains("前端") && question.contains("后端")) {
            signals++;
        }
        if (question.contains("测试") && (question.contains("修复") || question.contains("重构"))) {
            signals++;
        }
        double confidence = Math.min(1.0d, signals / 3.0d);
        if (confidence >= properties.getAutoUpgradeThreshold()) {
            return new Decision(AgentRunMode.MULTI_AGENT, true, "orchestrator auto-upgrade");
        }
        return new Decision(request.runMode(), false, "single-agent sufficient");
    }

    public record Decision(AgentRunMode effectiveRunMode, boolean multiAgentSelected, String reason) {
    }
}
