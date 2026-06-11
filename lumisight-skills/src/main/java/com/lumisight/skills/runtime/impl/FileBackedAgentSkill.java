package com.lumisight.skills.runtime.impl;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

import com.lumisight.skills.runtime.AgentSkill;
import com.lumisight.skills.dto.ParsedSkillDocument;
import com.lumisight.skills.dto.RegisteredSkill;
import com.lumisight.skills.SkillCatalog;
import com.lumisight.skills.dto.SkillContext;
import com.lumisight.skills.SkillMarkdownParser;
import com.lumisight.skills.dto.SkillPlan;

@Component
public class FileBackedAgentSkill implements AgentSkill {

    private final SkillMarkdownParser parser;
    private final SkillCatalog catalog;

    public FileBackedAgentSkill(SkillMarkdownParser parser, SkillCatalog catalog) {
        this.parser = parser;
        this.catalog = catalog;
    }

    @Override
    public String skillName() {
        return "file_backed_skill";
    }

    @Override
    public int order() {
        return -100;
    }

    @Override
    public boolean supports(SkillContext context) {
        String skillRef = context.metadata() == null ? null : asString(context.metadata().get("skillPath"));
        if (!StringUtils.hasText(skillRef)) {
            return false;
        }
        return catalog.resolve(skillRef).isPresent();
    }

    @Override
    public SkillPlan buildPlan(SkillContext context) {
        String skillRef = asString(context.metadata().get("skillPath"));
        Optional<RegisteredSkill> resolved = catalog.resolve(skillRef);
        if (resolved.isEmpty()) {
            return new SkillPlan(
                    "技能未注册或不可访问，回退默认编排: " + skillRef,
                    List.of(),
                    "",
                    ""
            );
        }
        RegisteredSkill skill = resolved.get();
        try {
            String content = Files.readString(skill.path());
            ParsedSkillDocument doc = parser.parse(content);
            return new SkillPlan(
                    "来自已注册技能: " + skill.id() + " | " + doc.summary(),
                    doc.executionSteps(),
                    doc.outputContract(),
                    content
            );
        } catch (Exception e) {
            return new SkillPlan(
                    "技能文件读取失败，回退默认编排: " + e.getMessage(),
                    List.of(),
                    "",
                    ""
            );
        }
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
