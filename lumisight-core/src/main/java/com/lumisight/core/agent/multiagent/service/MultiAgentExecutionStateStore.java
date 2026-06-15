package com.lumisight.core.agent.multiagent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumisight.core.agent.multiagent.model.MultiAgentExecutionState;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

@Component
public class MultiAgentExecutionStateStore {

    private static final String STATE_FILE_NAME = "execution-state.json";

    private final MultiAgentProperties properties;
    private final ObjectMapper objectMapper;

    public MultiAgentExecutionStateStore(MultiAgentProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public Optional<MultiAgentExecutionState> load(String repoRoot, String coordinationId) {
        try {
            Path stateFile = statePath(repoRoot, coordinationId);
            if (!Files.exists(stateFile)) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(stateFile.toFile(), MultiAgentExecutionState.class));
        } catch (Exception e) {
            throw new IllegalStateException("failed to load multi-agent execution state", e);
        }
    }

    public void save(String repoRoot, String coordinationId, MultiAgentExecutionState state) {
        try {
            Path stateFile = statePath(repoRoot, coordinationId);
            Files.createDirectories(stateFile.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(stateFile.toFile(), state);
        } catch (Exception e) {
            throw new IllegalStateException("failed to save multi-agent execution state", e);
        }
    }

    public void clear(String repoRoot, String coordinationId) {
        try {
            Files.deleteIfExists(statePath(repoRoot, coordinationId));
        } catch (Exception e) {
            throw new IllegalStateException("failed to clear multi-agent execution state", e);
        }
    }

    public Path statePath(String repoRoot, String coordinationId) {
        return Path.of(repoRoot)
                .resolve(properties.getCoordinationRootDir())
                .resolve(coordinationId)
                .resolve(STATE_FILE_NAME);
    }
}
