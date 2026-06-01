package com.lumisight.api.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumisight.api.agent.dto.request.AgentRunRequest;
import com.lumisight.core.agent.CodeAssistantAgentService;
import com.lumisight.core.model.AgentEvent;
import com.lumisight.core.model.AgentDialogueMode;
import com.lumisight.core.model.AgentRequest;
import com.lumisight.core.model.AgentRunMode;
import com.lumisight.core.model.AgentTaskType;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.HttpStatus;

import java.nio.file.Path;

@RestController
@RequestMapping("/api/lumisight/agent")
public class CodeAssistantAgentController {

    private final CodeAssistantAgentService codeAssistantAgentService;
    private final ObjectMapper objectMapper;

    public CodeAssistantAgentController(
            CodeAssistantAgentService codeAssistantAgentService,
            ObjectMapper objectMapper
    ) {
        this.codeAssistantAgentService = codeAssistantAgentService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody AgentRunRequest request) {
        AgentRequest agentRequest = toAgentRequest(request);
        SseEmitter emitter = new SseEmitter(0L);

        codeAssistantAgentService.run(agentRequest)
                .doOnNext(event -> sendEvent(emitter, event))
                .doOnError(emitter::completeWithError)
                .doOnComplete(emitter::complete)
                .subscribe();

        return emitter;
    }

    private AgentRequest toAgentRequest(AgentRunRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request is required");
        }
        AgentTaskType taskType = parseTaskType(request.taskType());
        AgentRunMode runMode = parseRunMode(request.runMode());
        AgentDialogueMode dialogueMode = parseDialogueMode(request.dialogueMode());

        return new AgentRequest(
                taskType,
                normalizeRepoRoot(request.repoRoot(), request.skillPath()),
                request.question(),
                request.skillPath(),
                request.sessionId(),
                request.followUpAnswer(),
                request.approveRiskyToolCall() != null && request.approveRiskyToolCall(),
                request.interrupt() != null && request.interrupt(),
                request.resume() != null && request.resume(),
                request.includeRagContext() == null || request.includeRagContext(),
                request.includeKnowledgeGraphContext() != null && request.includeKnowledgeGraphContext(),
                request.contextLimit(),
                runMode,
                dialogueMode
        );
    }

    private String normalizeRepoRoot(String repoRoot, String skillPath) {
        if (StringUtils.hasText(repoRoot)) {
            return repoRoot.trim();
        }
        if (StringUtils.hasText(skillPath)) {
            try {
                Path skill = Path.of(skillPath).toAbsolutePath().normalize();
                Path parent = skill.getParent();
                if (parent != null) {
                    return parent.toString();
                }
            } catch (Exception ignored) {
            }
        }
        return Path.of("").toAbsolutePath().normalize().toString();
    }

    private AgentTaskType parseTaskType(String value) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "taskType is required");
        }
        try {
            return AgentTaskType.valueOf(value.trim().toUpperCase());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid taskType: " + value);
        }
    }

    private AgentRunMode parseRunMode(String value) {
        if (!StringUtils.hasText(value)) {
            return AgentRunMode.NORMAL;
        }
        try {
            return AgentRunMode.valueOf(value.trim().toUpperCase());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid runMode: " + value);
        }
    }

    private AgentDialogueMode parseDialogueMode(String value) {
        if (!StringUtils.hasText(value)) {
            return AgentDialogueMode.FOLLOW;
        }
        try {
            return AgentDialogueMode.valueOf(value.trim().toUpperCase());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid dialogueMode: " + value);
        }
    }

    private void sendEvent(SseEmitter emitter, AgentEvent event) {
        try {
            String data = objectMapper.writeValueAsString(event);
            emitter.send(SseEmitter.event()
                    .name(event.type())
                    .data(data));
        } catch (Exception e) {
            throw new IllegalStateException("failed to send sse event", e);
        }
    }
}
