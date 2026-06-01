package com.lumisight.skills.runtime;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Component
public class FileBackedAgentSkill implements AgentSkill {

    private final SkillMarkdownParser parser;

    public FileBackedAgentSkill(SkillMarkdownParser parser) {
        this.parser = parser;
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
        String skillPath = context.metadata() == null ? null : asString(context.metadata().get("skillPath"));
        if (!StringUtils.hasText(skillPath)) {
            return false;
        }
        try {
            Path path = Path.of(skillPath).toAbsolutePath().normalize();
            return Files.exists(path) && Files.isRegularFile(path);
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public SkillPlan buildPlan(SkillContext context) {
        String skillPath = asString(context.metadata().get("skillPath"));
        try {
            String content = Files.readString(Path.of(skillPath).toAbsolutePath().normalize());
            ParsedSkillDocument doc = parser.parse(content);
            return new SkillPlan(
                    "来自技能文件: " + skillPath + " | " + doc.summary(),
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
