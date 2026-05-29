package com.lumisight.skills.runtime;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Component
public class SkillRegistry {

    private final List<AgentSkill> skills;

    public SkillRegistry(List<AgentSkill> skills) {
        this.skills = skills.stream()
                .sorted(Comparator.comparingInt(AgentSkill::order))
                .toList();
    }

    public AgentSkill select(SkillContext context) {
        Optional<AgentSkill> selected = skills.stream()
                .filter(skill -> skill.supports(context))
                .findFirst();
        return selected.orElseGet(DefaultAgentSkill::new);
    }
}
