package com.lumisight.skills.runtime;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class BuiltinWorkflowSkill implements AgentSkill {

    @Override
    public String skillName() {
        return "builtin_workflow_skill";
    }

    @Override
    public int order() {
        return 1000;
    }

    @Override
    public boolean supports(SkillContext context) {
        return true;
    }

    @Override
    public SkillPlan buildPlan(SkillContext context) {
        return new SkillPlan(
                "未提供技能文件，使用内置工作流：先定位目录结构，再读取关键文件，再检索证据并给出结论。",
                List.of("ls", "cat", "grep"),
                List.of("List repository structure", "Read key files", "Search relevant evidence", "Produce final answer"),
                "输出结论、证据与下一步建议"
        );
    }
}
