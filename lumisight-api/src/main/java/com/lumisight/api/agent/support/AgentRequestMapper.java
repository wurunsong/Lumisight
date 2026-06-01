package com.lumisight.api.agent.support;

import com.lumisight.api.agent.dto.request.AgentRunRequest;
import com.lumisight.core.model.AgentDialogueMode;
import com.lumisight.core.model.AgentRequest;
import com.lumisight.core.model.AgentRunMode;
import com.lumisight.core.model.AgentTaskType;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;

@Component
public class AgentRequestMapper {

    public AgentRequest toAgentRequest(AgentRunRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request is required");
        }
        boolean interrupt = request.interrupt() != null && request.interrupt();
        boolean resume = request.resume() != null && request.resume();
        AgentTaskType taskType = parseTaskType(request.taskType(), interrupt, resume);
        AgentRunMode runMode = parseRunMode(request.runMode());
        AgentDialogueMode dialogueMode = parseDialogueMode(request.dialogueMode());

        return new AgentRequest(
                taskType,
                normalizeRepoRoot(request.repoRoot()),
                request.question(),
                request.skillPath(),
                request.sessionId(),
                request.approveRiskyToolCall() != null && request.approveRiskyToolCall(),
                interrupt,
                resume,
                request.includeRagContext() == null || request.includeRagContext(),
                request.includeKnowledgeGraphContext() != null && request.includeKnowledgeGraphContext(),
                request.contextLimit(),
                runMode,
                dialogueMode
        );
    }

    private String normalizeRepoRoot(String repoRoot) {
        if (StringUtils.hasText(repoRoot)) {
            return repoRoot.trim();
        }
        return Path.of("").toAbsolutePath().normalize().toString();
    }

    private AgentTaskType parseTaskType(String value, boolean interrupt, boolean resume) {
        if (!StringUtils.hasText(value)) {
            if (interrupt || resume) {
                return AgentTaskType.CHAT;
            }
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
}
