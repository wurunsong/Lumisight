package com.lumisight.skills.runtime;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
                    List.of("ls", "cat", "grep"),
                    List.of(),
                    ""
            );
        }
        RegisteredSkill skill = resolved.get();
        try {
            String content = Files.readString(skill.path());
            ParsedSkillDocument doc = parser.parse(content);
            return new SkillPlan(
                    "来自已注册技能: " + skill.id() + " | " + doc.summary(),
                    inferPreferredTools(doc.executionSteps()),
                    doc.executionSteps(),
                    doc.outputContract()
            );
        } catch (Exception e) {
            return new SkillPlan(
                    "技能文件读取失败，回退默认编排: " + e.getMessage(),
                    List.of("ls", "cat", "grep"),
                    List.of(),
                    ""
            );
        }
    }

    private List<String> inferPreferredTools(List<String> steps) {
        List<String> tools = new ArrayList<>();
        for (String step : steps) {
            String s = step.toLowerCase();
            if ((s.contains("read") || s.contains("读取")) && !tools.contains("cat")) {
                tools.add("cat");
            }
            if ((s.contains("list") || s.contains("目录")) && !tools.contains("ls")) {
                tools.add("ls");
            }
            if ((s.contains("search") || s.contains("检索") || s.contains("grep")) && !tools.contains("grep")) {
                tools.add("grep");
            }
            if ((s.contains("compile") || s.contains("编译")) && !tools.contains("compileJava")) {
                tools.add("compileJava");
            }
            if ((s.contains("diff") || s.contains("git")) && !tools.contains("gitDiff")) {
                tools.add("gitDiff");
            }
        }
        if (tools.isEmpty()) {
            tools.add("ls");
            tools.add("cat");
        }
        return tools;
    }

    private String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
