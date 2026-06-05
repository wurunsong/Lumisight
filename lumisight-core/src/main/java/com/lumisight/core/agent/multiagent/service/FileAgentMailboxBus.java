package com.lumisight.core.agent.multiagent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumisight.core.agent.multiagent.model.TeamAgentMessage;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class FileAgentMailboxBus implements AgentMailboxBus {

    private final MultiAgentProperties properties;
    private final ObjectMapper objectMapper;

    public FileAgentMailboxBus(MultiAgentProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void send(String repoRoot, TeamAgentMessage message) {
        try {
            Path inbox = inboxPath(repoRoot, message.teamId(), message.toAgentId());
            Files.createDirectories(inbox.getParent());
            String line = objectMapper.writeValueAsString(message) + System.lineSeparator();
            Files.writeString(inbox, line, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            throw new IllegalStateException("failed to send team agent message", e);
        }
    }

    @Override
    public List<TeamAgentMessage> readInbox(String repoRoot, String teamId, String agentId, boolean consume) {
        try {
            Path inbox = inboxPath(repoRoot, teamId, agentId);
            if (!Files.exists(inbox)) {
                return List.of();
            }
            List<TeamAgentMessage> messages = new ArrayList<>();
            for (String line : Files.readAllLines(inbox, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                messages.add(objectMapper.readValue(line, new TypeReference<>() {
                }));
            }
            if (consume) {
                Files.deleteIfExists(inbox);
            }
            return List.copyOf(messages);
        } catch (Exception e) {
            throw new IllegalStateException("failed to read team agent inbox", e);
        }
    }

    private Path inboxPath(String repoRoot, String teamId, String agentId) {
        return Path.of(repoRoot)
                .resolve(properties.getTeamRootDir())
                .resolve(teamId)
                .resolve("inboxes")
                .resolve(agentId + ".jsonl");
    }
}
