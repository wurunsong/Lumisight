package com.lumisight.core.support;

import com.lumisight.skills.runtime.RegisteredSkill;
import com.lumisight.skills.runtime.SkillCatalog;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

@Component
public class SkillAutoRouter {

    private final ChatClient llmChatClient;
    private final SkillCatalog skillCatalog;

    public SkillAutoRouter(ChatClient.Builder chatClientBuilder, SkillCatalog skillCatalog) {
        this.llmChatClient = chatClientBuilder.build();
        this.skillCatalog = skillCatalog;
    }

    public String route(String question) {
        if (!StringUtils.hasText(question)) {
            return null;
        }
        List<RegisteredSkill> skills = skillCatalog.allSkills();
        if (skills.isEmpty()) {
            return null;
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
                    .system("你是技能路由器。只返回一个技能id，或返回 NONE。禁止输出其他文本。")
                    .user("用户问题:\n" + question + "\n\n可用技能:\n" + list + "\n只返回最匹配的 id；若都不匹配返回 NONE。")
                    .call()
                    .content();
        } catch (Exception ignored) {
            return null;
        }
        if (!StringUtils.hasText(response)) {
            return null;
        }
        String chosen = response.trim().replace("`", "");
        if ("NONE".equalsIgnoreCase(chosen)) {
            return null;
        }
        for (RegisteredSkill skill : skills) {
            if (skill.id().equals(chosen)
                    || skill.name().equalsIgnoreCase(chosen)
                    || skill.id().equalsIgnoreCase(chosen.toLowerCase(Locale.ROOT))) {
                return skill.id();
            }
        }
        return null;
    }
}

