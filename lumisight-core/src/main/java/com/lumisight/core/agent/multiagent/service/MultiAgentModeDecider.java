package com.lumisight.core.agent.multiagent.service;

import com.lumisight.core.model.AgentRequest;
import com.lumisight.core.model.AgentRunMode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Component
public class MultiAgentModeDecider {

    private final MultiAgentProperties properties;

    public MultiAgentModeDecider(MultiAgentProperties properties) {
        this.properties = properties;
    }

    public Decision decide(AgentRequest request, String effectiveQuestion) {
        if (!properties.isEnabled()) {
            return new Decision(AgentRunMode.NORMAL, false, "multi-agent disabled", 0.0d, List.of());
        }
        if (request.runMode() == AgentRunMode.MULTI_AGENT) {
            return new Decision(AgentRunMode.MULTI_AGENT, true, "explicit runMode", 1.0d, List.of("explicit_run_mode"));
        }
        if (!properties.isAutoUpgradeEnabled() || !StringUtils.hasText(effectiveQuestion)) {
            return new Decision(request.runMode(), false, "auto-upgrade disabled", 0.0d, List.of());
        }
        String question = effectiveQuestion.trim();
        int signals = 0;
        List<String> matchedSignals = new ArrayList<>();
        if (question.length() >= 120) {
            signals++;
            matchedSignals.add("long_question");
        }
        if (question.contains("同时") || question.contains("分别") || question.contains("并行")
                || question.contains("拆解") || question.contains("多个模块") || question.contains("多个文件")) {
            signals++;
            matchedSignals.add("parallel_or_split_language");
        }
        if (question.contains("前端") && question.contains("后端")) {
            signals++;
            matchedSignals.add("frontend_backend_pair");
        }
        if (question.contains("测试") && (question.contains("修复") || question.contains("重构"))) {
            signals++;
            matchedSignals.add("fix_with_test_impact");
        }
        double confidence = Math.min(1.0d, signals / 3.0d);
        if (confidence >= properties.getAutoUpgradeThreshold()) {
            return new Decision(AgentRunMode.MULTI_AGENT, true, "orchestrator auto-upgrade", confidence, List.copyOf(matchedSignals));
        }
        return new Decision(request.runMode(), false, "single-agent sufficient", confidence, List.copyOf(matchedSignals));
    }

    public record Decision(
            AgentRunMode effectiveRunMode,
            boolean multiAgentSelected,
            String reason,
            double confidence,
            List<String> matchedSignals
    ) {
    }
}
