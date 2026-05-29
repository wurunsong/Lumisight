package com.lumisight.skills.runtime;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DefaultAgentSkill implements AgentSkill {

    @Override
    public String skillName() {
        return "default";
    }

    @Override
    public boolean supports(SkillContext context) {
        return true;
    }

    @Override
    public SkillPlan buildPlan(SkillContext context) {
        return new SkillPlan("默认技能：按当前问题进行工具编排并输出结论。", List.of());
    }
}
