package com.lumisight.skills.runtime;

import com.lumisight.skills.dto.SkillContext;
import com.lumisight.skills.dto.SkillPlan;

public interface AgentSkill {

    String skillName();

    default int order() {
        return 0;
    }

    boolean supports(SkillContext context);

    SkillPlan buildPlan(SkillContext context);
}
