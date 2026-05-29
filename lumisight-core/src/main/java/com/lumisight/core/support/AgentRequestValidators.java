package com.lumisight.core.support;

import com.lumisight.core.model.AgentRequest;
import org.springframework.util.StringUtils;

public final class AgentRequestValidators {

    private AgentRequestValidators() {
    }

    public static void validate(AgentRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        if (!StringUtils.hasText(request.repoRoot())) {
            throw new IllegalArgumentException("repoRoot must not be blank");
        }
        boolean hasQuestionInput = StringUtils.hasText(request.question()) || StringUtils.hasText(request.followUpAnswer());
        boolean resumeWithoutText = request.resume() && StringUtils.hasText(request.sessionId());
        if (!hasQuestionInput && !resumeWithoutText) {
            throw new IllegalArgumentException("question must not be blank");
        }
        if (request.taskType() == null) {
            throw new IllegalArgumentException("taskType must not be null");
        }
        if (request.runMode() == null) {
            throw new IllegalArgumentException("runMode must not be null");
        }
        if (request.dialogueMode() == null) {
            throw new IllegalArgumentException("dialogueMode must not be null");
        }
    }
}
