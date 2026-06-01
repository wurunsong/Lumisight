package com.lumisight.core.support;

import com.lumisight.skills.runtime.RegisteredSkill;
import com.lumisight.skills.runtime.SkillCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

@Component
public class SkillAutoRouter {

    private final ChatClient llmChatClient;
    private final SkillCatalog skillCatalog;
    private final SkillRoutingProperties skillRoutingProperties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SkillAutoRouter(
            ChatClient.Builder chatClientBuilder,
            SkillCatalog skillCatalog,
            SkillRoutingProperties skillRoutingProperties
    ) {
        this.llmChatClient = chatClientBuilder.build();
        this.skillCatalog = skillCatalog;
        this.skillRoutingProperties = skillRoutingProperties;
    }

    public RouteResult route(String question) {
        if (!StringUtils.hasText(question)) {
            return RouteResult.noMatch("empty_question");
        }
        List<RegisteredSkill> skills = skillCatalog.allSkills();
        if (skills.isEmpty()) {
            return RouteResult.noMatch("no_registered_skill");
        }
        StringBuilder list = new StringBuilder();
        for (RegisteredSkill skill : skills) {
            list.append("- id=").append(skill.id())
                    .append(" | name=").append(skill.name())
                    .append(" | summary=").append(skill.summary())
                    .append("\n");
        }
        String response;
        try {
            response = llmChatClient.prompt()
                    .system("你是技能路由器。输出严格 JSON: {\"skillId\":\"...|NONE\",\"confidence\":0~1,\"reason\":\"...\"}，禁止输出其他文本。")
                    .user("用户问题:\n" + question + "\n\n可用技能:\n" + list + "\n返回最匹配技能。若不匹配返回 skillId=NONE，confidence 必须给出。")
                    .call()
                    .content();
        } catch (Exception e) {
            return RouteResult.noMatch("router_call_failed: " + e.getMessage());
        }
        if (!StringUtils.hasText(response)) {
            return RouteResult.noMatch("empty_router_response");
        }
        String payload = response.trim().replace("```json", "").replace("```", "").trim();
        try {
            JsonNode root = objectMapper.readTree(payload);
            String chosen = root.path("skillId").asText("");
            double confidence = clampConfidence(root.path("confidence").asDouble(0d));
            String reason = root.path("reason").asText("");
            if (!StringUtils.hasText(chosen) || "NONE".equalsIgnoreCase(chosen)) {
                return RouteResult.noMatch(reason);
            }
            for (RegisteredSkill skill : skills) {
                if (skill.id().equals(chosen)
                        || skill.name().equalsIgnoreCase(chosen)
                        || skill.id().equalsIgnoreCase(chosen.toLowerCase(Locale.ROOT))) {
                    boolean accepted = confidence >= skillRoutingProperties.getMinConfidence();
                    if (accepted) {
                        return new RouteResult(skill.id(), confidence, reason, chosen, true);
                    }
                    String fallbackSkillId = resolveFallbackSkillId(skills);
                    return new RouteResult(fallbackSkillId, confidence, reason, chosen, false);
                }
            }
            return RouteResult.noMatch("unknown_skill_id: " + chosen);
        } catch (Exception e) {
            return RouteResult.noMatch("invalid_router_payload: " + e.getMessage());
        }
    }

    private double clampConfidence(double value) {
        if (Double.isNaN(value)) {
            return 0d;
        }
        return Math.max(0d, Math.min(1d, value));
    }

    private String resolveFallbackSkillId(List<RegisteredSkill> skills) {
        if (skillRoutingProperties.getLowConfidenceFallback() != SkillRoutingProperties.LowConfidenceFallback.DEFAULT_SKILL) {
            return null;
        }
        if (!StringUtils.hasText(skillRoutingProperties.getDefaultSkillId())) {
            return null;
        }
        String configured = skillRoutingProperties.getDefaultSkillId().trim();
        for (RegisteredSkill skill : skills) {
            if (skill.id().equals(configured) || skill.name().equalsIgnoreCase(configured)) {
                return skill.id();
            }
        }
        return null;
    }

    public record RouteResult(
            String skillId,
            double confidence,
            String reason,
            String candidateSkillId,
            boolean accepted
    ) {
        static RouteResult noMatch(String reason) {
            return new RouteResult(null, 0d, reason, null, false);
        }
    }
}
