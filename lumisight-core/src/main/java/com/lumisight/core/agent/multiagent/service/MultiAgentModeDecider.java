package com.lumisight.core.agent.multiagent.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumisight.core.config.LumisightChatModelConfig;
import com.lumisight.core.model.AgentRequest;
import com.lumisight.core.model.AgentRunMode;
import com.lumisight.core.support.AgentPromptService;
import com.lumisight.core.support.StreamingChatClientSupport;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import java.util.List;

@Component
public class MultiAgentModeDecider {
    private final MultiAgentProperties properties;
    private final ChatClient schedulerChatClient;
    private final AgentPromptService agentPromptService;
    private final StreamingChatClientSupport streamingChatClientSupport;
    private final ObjectMapper objectMapper;

    public MultiAgentModeDecider(
            MultiAgentProperties properties,
            @Qualifier(LumisightChatModelConfig.SCHEDULER_CHAT_CLIENT_BUILDER) ChatClient.Builder schedulerChatClientBuilder,
            AgentPromptService agentPromptService,
            StreamingChatClientSupport streamingChatClientSupport,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.schedulerChatClient = schedulerChatClientBuilder.build();
        this.agentPromptService = agentPromptService;
        this.streamingChatClientSupport = streamingChatClientSupport;
        this.objectMapper = objectMapper;
    }

    public Decision decide(AgentRequest request, String effectiveQuestion) {
        // 没打开多agent开关
        if (!properties.isEnabled()) {
            return new Decision(AgentRunMode.NORMAL, false, "multi-agent disabled", 0.0d, List.of());
        }
        // 用户明确指定了多agent模式
        if (request.runMode() == AgentRunMode.MULTI_AGENT) {
            return new Decision(AgentRunMode.MULTI_AGENT, true, "explicit runMode", 1.0d, List.of("explicit_run_mode"));
        }
        // 自动升级开关关闭，或者问题不符合自动升级条件
        if (!properties.isAutoUpgradeEnabled() || !StringUtils.hasText(effectiveQuestion)) {
            return new Decision(request.runMode(), false, "auto-upgrade disabled", 0.0d, List.of());
        }
        // 这里把自动升级判断切到专门的调度模型上，但 API 仍然复用同一套 OpenAI-compatible client。
        Decision modelDecision = decideBySchedulerModel(request, effectiveQuestion);
        if (modelDecision != null) {
            return modelDecision;
        }
        // 关键词匹配做兜底处理
        return decideByHeuristics(request, effectiveQuestion);
    }

    private Decision decideBySchedulerModel(AgentRequest request, String effectiveQuestion) {
        try {
            // 提取关键词
            List<String> heuristicSignals = collectHeuristicSignals(effectiveQuestion.trim());
            String raw = streamingChatClientSupport.collect(
                    schedulerChatClient,
                    agentPromptService.multiAgentUpgradeSystemPrompt(),
                    agentPromptService.multiAgentUpgradeUserPrompt(
                            effectiveQuestion.trim(),
                            String.valueOf(request.runMode()),
                            String.valueOf(request.dialogueMode()),
                            request.repoRoot(),
                            heuristicSignals
                    )
            );
            SchedulerModelDecision payload = objectMapper.readValue(cleanJson(raw), SchedulerModelDecision.class);
            if (payload == null || !StringUtils.hasText(payload.selectedRunMode())) {
                return null;
            }
            // 解析模型响应结果
            AgentRunMode selectedRunMode = parseRunMode(payload.selectedRunMode(), request.runMode());
            double confidence = normalizeConfidence(payload.confidence());
            List<String> matchedSignals = payload.matchedSignals() == null ? heuristicSignals : List.copyOf(payload.matchedSignals());
            if (selectedRunMode == AgentRunMode.MULTI_AGENT && confidence >= properties.getAutoUpgradeThreshold()) {
                return new Decision(AgentRunMode.MULTI_AGENT, true, safeReason(payload.reason(), "scheduler-model upgrade"), confidence, matchedSignals);
            }
            return new Decision(request.runMode(), false, safeReason(payload.reason(), "scheduler-model kept single-agent"), confidence, matchedSignals);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Decision decideByHeuristics(AgentRequest request, String effectiveQuestion) {
        String question = effectiveQuestion.trim();
        List<String> matchedSignals = collectHeuristicSignals(question);
        double confidence = Math.min(1.0d, matchedSignals.size() / 3.0d);
        if (confidence >= properties.getAutoUpgradeThreshold()) {
            return new Decision(AgentRunMode.MULTI_AGENT, true, "heuristic auto-upgrade", confidence, List.copyOf(matchedSignals));
        }
        return new Decision(request.runMode(), false, "single-agent sufficient", confidence, List.copyOf(matchedSignals));
    }

    private List<String> collectHeuristicSignals(String question) {
        return MultiAgentHeuristicSignal.collectMatchedSignals(question);
    }

    private AgentRunMode parseRunMode(String raw, AgentRunMode fallback) {
        try {
            return AgentRunMode.valueOf(raw.trim().toUpperCase());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private double normalizeConfidence(Double confidence) {
        if (confidence == null) {
            return 0.0d;
        }
        return Math.max(0.0d, Math.min(1.0d, confidence));
    }

    private String safeReason(String reason, String fallback) {
        return StringUtils.hasText(reason) ? reason.trim() : fallback;
    }

    private String cleanJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```json\\s*", "");
            text = text.replaceFirst("^```\\s*", "");
            text = text.replaceFirst("\\s*```$", "");
        }
        return text.trim();
    }

    public record Decision(
            AgentRunMode effectiveRunMode,
            boolean multiAgentSelected,
            String reason,
            double confidence,
            List<String> matchedSignals
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SchedulerModelDecision(
            String selectedRunMode,
            Double confidence,
            String reason,
            List<String> matchedSignals
    ) {
    }
}
