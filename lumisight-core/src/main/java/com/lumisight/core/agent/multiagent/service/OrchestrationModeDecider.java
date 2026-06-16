package com.lumisight.core.agent.multiagent.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumisight.core.agent.multiagent.model.AgentOrchestrationMode;
import com.lumisight.core.agent.multiagent.model.SubAgentTask;
import com.lumisight.core.config.LumisightChatModelConfig;
import com.lumisight.core.context.ambient.OrchestrationContext;
import com.lumisight.core.support.AgentPromptService;
import com.lumisight.core.support.StreamingChatClientSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.StringJoiner;

/**
 * 编排方式决策器。
 * 任务拆解可以先保持启发式，但 serial / parallel / hybrid 的选择交给 scheduler 模型，
 * 避免调度策略继续散落在关键词判断里。
 */
@Component
public class OrchestrationModeDecider {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationModeDecider.class);

    private final ChatClient schedulerChatClient;
    private final AgentPromptService agentPromptService;
    private final StreamingChatClientSupport streamingChatClientSupport;
    private final ObjectMapper objectMapper;

    public OrchestrationModeDecider(
            @Qualifier(LumisightChatModelConfig.SCHEDULER_CHAT_CLIENT_BUILDER) ChatClient.Builder schedulerChatClientBuilder,
            AgentPromptService agentPromptService,
            StreamingChatClientSupport streamingChatClientSupport,
            ObjectMapper objectMapper
    ) {
        this.schedulerChatClient = schedulerChatClientBuilder.build();
        this.agentPromptService = agentPromptService;
        this.streamingChatClientSupport = streamingChatClientSupport;
        this.objectMapper = objectMapper;
    }

    public AgentOrchestrationMode decide(OrchestrationContext context, List<SubAgentTask> tasks) {
        if (tasks == null || tasks.size() <= 1) {
            return AgentOrchestrationMode.SERIAL;
        }
        try {
            String raw = streamingChatClientSupport.collect(
                    schedulerChatClient,
                    agentPromptService.orchestrationModeSystemPrompt(),
                    agentPromptService.orchestrationModeUserPrompt(context, renderTaskBriefs(tasks))
            );
            OrchestrationModeDecision decision = objectMapper.readValue(cleanJson(raw), OrchestrationModeDecision.class);
            AgentOrchestrationMode mode = parseMode(decision == null ? null : decision.orchestrationMode());
            if (mode != null) {
                return mode;
            }
        } catch (Exception e) {
            log.debug("orchestration_mode_model_decide_failed, error={}", e.getMessage());
        }
        return fallback(tasks);
    }

    private String renderTaskBriefs(List<SubAgentTask> tasks) {
        StringJoiner joiner = new StringJoiner("\n");
        for (SubAgentTask task : tasks) {
            joiner.add("""
                    - taskId: %s
                      title: %s
                      capability: %s
                      dependsOn: %s
                      parallelGroup: %s
                      instruction: %s
                    """.formatted(
                    task.taskId(),
                    task.title(),
                    task.capability(),
                    task.dependsOn(),
                    task.parallelGroup(),
                    task.instruction()
            ).stripTrailing());
        }
        return joiner.toString();
    }

    private AgentOrchestrationMode fallback(List<SubAgentTask> tasks) {
        boolean hasDependencies = tasks.stream().anyMatch(task -> task.dependsOn() != null && !task.dependsOn().isEmpty());
        return hasDependencies ? AgentOrchestrationMode.HYBRID : AgentOrchestrationMode.PARALLEL;
    }

    private AgentOrchestrationMode parseMode(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return AgentOrchestrationMode.valueOf(raw.trim().toUpperCase());
        } catch (Exception ignored) {
            return null;
        }
    }

    private String cleanJson(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?\\s*", "");
            text = text.replaceFirst("\\s*```$", "");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end >= start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OrchestrationModeDecision(
            String orchestrationMode,
            String reason
    ) {
    }
}
