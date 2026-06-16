package com.lumisight.core.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumisight.core.config.LumisightChatModelConfig;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.AgentRequest;
import com.lumisight.core.support.memory.MemoryWriteRules;
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
import java.util.List;
import java.util.Locale;

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
            List<AgentContextItem> currentContexts,
            RelevantMemoryBundle memoryContext
    ) {
        consolidateSignal("final_answer", request, effectiveQuestion, finalAnswer, currentContexts, memoryContext);
    }

    public void consolidateSignal(
            String trigger,
            AgentRequest request,
            String effectiveQuestion,
            String observedContent,
            List<AgentContextItem> currentContexts,
            RelevantMemoryBundle memoryContext
    ) {
        if (request == null || !StringUtils.hasText(request.repoRoot()) || !StringUtils.hasText(request.userId()) || !StringUtils.hasText(observedContent)) {
            return;
        }
        try {
            String raw = streamingChatClientSupport.collect(
                    memoryChatClient,
                    agentPromptService.memoryConsolidateSystemPrompt(),
                    agentPromptService.memoryConsolidateUserPrompt(request, trigger, effectiveQuestion, observedContent, currentContexts, memoryContext)
            );
            // 自动记忆不是单纯追加：memory 模型先给出维护动作，再由这里统一做硬校验和落盘。
            MemoryMaintenanceDecision decision = parseDecision(raw);
            if (decision == null || decision.action() == MemoryMaintenanceAction.NONE) {
                return;
            }
            applyDecision(request, decision);
        } catch (Exception e) {
            log.warn("agent_memory_consolidation_failed, error={}", e.getMessage());
        }
    }

    private MemoryMaintenanceDecision parseDecision(String raw) throws Exception {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        JsonNode root = objectMapper.readTree(cleanJson(raw));
        MemoryMaintenanceAction action = parseAction(root);
        if (action == MemoryMaintenanceAction.NONE) {
            return new MemoryMaintenanceDecision(action, "", null);
        }
        String targetFilename = text(root, "targetFilename");
        MemoryWriteRequest writeRequest = parseWriteRequest(root, action);
        if ((action == MemoryMaintenanceAction.UPDATE || action == MemoryMaintenanceAction.MERGE || action == MemoryMaintenanceAction.DELETE)
                && !StringUtils.hasText(targetFilename)) {
            log.warn("agent_memory_consolidation_skipped, action={}, reason=missing_target_filename", action);
            return null;
        }
        if (writeRequest != null) {
            List<String> errors = MemoryWriteRules.validate(writeRequest);
            if (!errors.isEmpty()) {
                log.warn("agent_memory_consolidation_skipped, action={}, reason=invalid_memory, errors={}", action, errors);
                return null;
            }
            MemoryType targetType = typeFromFilename(targetFilename);
            if ((action == MemoryMaintenanceAction.UPDATE || action == MemoryMaintenanceAction.MERGE)
                    && targetType != null
                    && targetType != writeRequest.type()) {
                log.warn("agent_memory_consolidation_skipped, action={}, reason=target_type_mismatch, targetFilename={}, targetType={}, writeType={}",
                        action, targetFilename, targetType.wireValue(), writeRequest.type().wireValue());
                return null;
            }
        }
        return new MemoryMaintenanceDecision(action, targetFilename, writeRequest);
    }

    private MemoryMaintenanceAction parseAction(JsonNode root) {
        String actionText = text(root, "action");
        if (!StringUtils.hasText(actionText)) {
            return root.path("shouldWrite").asBoolean(false) ? MemoryMaintenanceAction.CREATE : MemoryMaintenanceAction.NONE;
        }
        try {
            return MemoryMaintenanceAction.valueOf(actionText.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return MemoryMaintenanceAction.NONE;
        }
    }

    private MemoryWriteRequest parseWriteRequest(JsonNode root, MemoryMaintenanceAction action) {
        if (action == MemoryMaintenanceAction.DELETE || action == MemoryMaintenanceAction.NONE) {
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

    private void applyDecision(AgentRequest request, MemoryMaintenanceDecision decision) {
        switch (decision.action()) {
            case CREATE -> {
                MemoryEntry entry = saveToLongTermMemory(request, decision.writeRequest());
                log.info("agent_memory_created, repoRoot={}, userId={}, filename={}, type={}",
                        request.repoRoot(), request.userId(), entry.filename(), entry.type().wireValue());
            }
            case UPDATE, MERGE -> {
                MemoryEntry entry = updateLongTermMemory(request, decision.targetFilename(), decision.writeRequest());
                log.info("agent_memory_updated, action={}, repoRoot={}, userId={}, filename={}, type={}",
                        decision.action(), request.repoRoot(), request.userId(), entry.filename(), entry.type().wireValue());
            }
            case DELETE -> {
                boolean deleted = deleteLongTermMemory(request, decision.targetFilename());
                log.info("agent_memory_deleted, repoRoot={}, userId={}, filename={}, deleted={}",
                        request.repoRoot(), request.userId(), decision.targetFilename(), deleted);
            }
            case NONE -> {
            }
        }
    }

    private MemoryEntry saveToLongTermMemory(AgentRequest request, MemoryWriteRequest writeRequest) {
        // 用户画像和明确反馈跨项目复用，落到用户根目录；项目动态和外部参考仍跟随当前仓库。
        if (usesUserProfileStore(writeRequest.type())) {
            return memoryService.save(userProfileStorageRoot(), request.userId(), "", writeRequest);
        }
        return memoryService.save(request.repoRoot(), request.userId(), writeRequest);
    }

    private MemoryEntry updateLongTermMemory(AgentRequest request, String targetFilename, MemoryWriteRequest writeRequest) {
        // UPDATE/MERGE 必须落回目标文件所属的记忆域，避免把项目记忆误更新到用户画像目录。
        if (usesUserProfileStore(writeRequest.type())) {
            return memoryService.update(userProfileStorageRoot(), request.userId(), "", targetFilename, writeRequest);
        }
        return memoryService.update(request.repoRoot(), request.userId(), targetFilename, writeRequest);
    }

    private boolean deleteLongTermMemory(AgentRequest request, String targetFilename) {
        MemoryType type = typeFromFilename(targetFilename);
        if (type == null) {
            throw new IllegalArgumentException("invalid memory filename type: " + targetFilename);
        }
        if (usesUserProfileStore(type)) {
            return memoryService.delete(userProfileStorageRoot(), request.userId(), "", targetFilename);
        }
        return memoryService.delete(request.repoRoot(), request.userId(), targetFilename);
    }

    private boolean usesUserProfileStore(MemoryType type) {
        return type == MemoryType.USER || type == MemoryType.FEEDBACK;
    }

    private String userProfileStorageRoot() {
        String userHome = System.getProperty("user.home");
        if (!StringUtils.hasText(userHome)) {
            throw new IllegalStateException("user.home is required for user profile memory");
        }
        return Path.of(userHome, ".lumisight").toAbsolutePath().normalize().toString();
    }

    private MemoryType typeFromFilename(String filename) {
        if (!StringUtils.hasText(filename)) {
            return MemoryType.PROJECT;
        }
        int index = filename.indexOf('_');
        String prefix = index > 0 ? filename.substring(0, index) : filename;
        try {
            return MemoryType.parse(prefix);
        } catch (Exception e) {
            return null;
        }
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

    private enum MemoryMaintenanceAction {
        NONE,
        CREATE,
        UPDATE,
        MERGE,
        DELETE
    }

    private record MemoryMaintenanceDecision(
            MemoryMaintenanceAction action,
            String targetFilename,
            MemoryWriteRequest writeRequest
    ) {
    }
}
