package com.lumisight.skills.runtime;

public interface AgentSkill {

    String skillName();

    default int order() {
        return 0;
    }

    boolean supports(SkillContext context);

    SkillPlan buildPlan(SkillContext context);
}
