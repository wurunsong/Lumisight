package com.lumisight.core.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumisight.core.config.LumisightChatModelConfig;
import com.lumisight.core.model.AgentRequest;
import com.lumisight.memory.MemoryService;
import com.lumisight.memory.dto.MemoryEntry;
import com.lumisight.memory.dto.MemoryWriteRequest;
import com.lumisight.memory.dto.RelevantMemoryBundle;
import com.lumisight.memory.enums.MemoryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Path;

/**
 * 单次长期记忆沉淀执行器。
 * 异步调度和串行化由 AgentMemoryAsyncService 负责，这里只做 memory 模型判断和实际写入。
 */
@Component
public class AgentMemoryConsolidationService {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryConsolidationService.class);

    private final ChatClient memoryChatClient;
    private final AgentPromptService agentPromptService;
    private final StreamingChatClientSupport streamingChatClientSupport;
    private final MemoryService memoryService;
    private final ObjectMapper objectMapper;

    public AgentMemoryConsolidationService(
            @Qualifier(LumisightChatModelConfig.MEMORY_CHAT_CLIENT_BUILDER) ChatClient.Builder memoryChatClientBuilder,
            AgentPromptService agentPromptService,
            StreamingChatClientSupport streamingChatClientSupport,
            MemoryService memoryService,
            ObjectMapper objectMapper
    ) {
        this.memoryChatClient = memoryChatClientBuilder.build();
        this.agentPromptService = agentPromptService;
        this.streamingChatClientSupport = streamingChatClientSupport;
        this.memoryService = memoryService;
        this.objectMapper = objectMapper;
    }

    public void consolidateAfterFinalAnswer(
            AgentRequest request,
            String effectiveQuestion,
            String finalAnswer,
            RelevantMemoryBundle memoryContext
    ) {
        consolidateSignal("final_answer", request, effectiveQuestion, finalAnswer, memoryContext);
    }

    public void consolidateSignal(
            String trigger,
            AgentRequest request,
            String effectiveQuestion,
            String observedContent,
            RelevantMemoryBundle memoryContext
    ) {
        if (request == null || !StringUtils.hasText(request.repoRoot()) || !StringUtils.hasText(request.userId()) || !StringUtils.hasText(observedContent)) {
            return;
        }
        try {
            String raw = streamingChatClientSupport.collect(
                    memoryChatClient,
                    agentPromptService.memoryConsolidateSystemPrompt(),
                    agentPromptService.memoryConsolidateUserPrompt(request, trigger, effectiveQuestion, observedContent, memoryContext)
            );
            MemoryWriteRequest writeRequest = parseWriteRequest(raw);
            if (writeRequest == null) {
                return;
            }
            MemoryEntry entry = saveToLongTermMemory(request, writeRequest);
            log.info("agent_memory_consolidated, repoRoot={}, userId={}, filename={}, type={}",
                    request.repoRoot(), request.userId(), entry.filename(), entry.type().wireValue());
        } catch (Exception e) {
            log.debug("agent_memory_consolidation_failed, error={}", e.getMessage());
        }
    }

    private MemoryWriteRequest parseWriteRequest(String raw) throws Exception {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        JsonNode root = objectMapper.readTree(cleanJson(raw));
        if (!root.path("shouldWrite").asBoolean(false)) {
            return null;
        }
        String name = text(root, "name");
        String description = text(root, "description");
        String typeText = text(root, "type");
        String body = text(root, "body");
        if (!StringUtils.hasText(name) || !StringUtils.hasText(description) || !StringUtils.hasText(typeText) || !StringUtils.hasText(body)) {
            return null;
        }
        return new MemoryWriteRequest(name, description, MemoryType.parse(typeText), body);
    }

    private MemoryEntry saveToLongTermMemory(AgentRequest request, MemoryWriteRequest writeRequest) {
        // 用户画像和明确反馈跨项目复用，落到用户根目录；项目动态和外部参考仍跟随当前仓库。
        if (writeRequest.type() == MemoryType.USER || writeRequest.type() == MemoryType.FEEDBACK) {
            String userHome = System.getProperty("user.home");
            if (StringUtils.hasText(userHome)) {
                return memoryService.save(
                        Path.of(userHome, ".lumisight").toAbsolutePath().normalize().toString(),
                        request.userId(),
                        "",
                        writeRequest
                );
            }
        }
        return memoryService.save(request.repoRoot(), request.userId(), writeRequest);
    }

    private String text(JsonNode root, String field) {
        JsonNode value = root.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private String cleanJson(String raw) {
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
}
