package com.lumisight.core.agent.support;

import com.lumisight.core.agent.model.AgentRequest;
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
        if (!StringUtils.hasText(request.question())) {
            throw new IllegalArgumentException("question must not be blank");
        }
        if (request.taskType() == null) {
            throw new IllegalArgumentException("taskType must not be null");
        }
    }
}
