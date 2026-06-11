package com.lumisight.skills;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

import com.lumisight.skills.dto.SkillContext;
import com.lumisight.skills.dto.SkillPlan;
import com.lumisight.skills.runtime.AgentSkill;

@Component
public class SkillRegistry {

    private final List<AgentSkill> skills;

    public SkillRegistry(List<AgentSkill> skills) {
        this.skills = skills.stream()
                .sorted(Comparator.comparingInt(AgentSkill::order))
                .toList();
    }

    public AgentSkill select(SkillContext context) {
        return skills.stream()
                .filter(skill -> skill.supports(context))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No workflow skill matched current context. Please register at least one concrete AgentSkill."
                ));
    }

    public ResolvedSkill resolve(SkillContext context) {
        AgentSkill skill = select(context);
        SkillPlan plan = skill.buildPlan(context);
        return new ResolvedSkill(skill.skillName(), plan);
    }

    public record ResolvedSkill(String skillName, SkillPlan plan) {
    }
}
