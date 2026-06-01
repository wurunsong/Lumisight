package com.lumisight.skills.runtime;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Component
public class SkillMarkdownParser {

    public ParsedSkillDocument parse(String markdown) {
        String[] lines = markdown.split("\\R");
        String name = "";
        String summary = "";
        String outputContract = "";
        List<String> executionSteps = new ArrayList<>();

        String section = "";
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("# ")) {
                if (!StringUtils.hasText(name)) {
                    name = line.substring(2).trim();
                }
                continue;
            }
            if (line.startsWith("## ")) {
                section = line.substring(3).trim();
                continue;
            }
            if ("Purpose".equalsIgnoreCase(section) && !StringUtils.hasText(summary) && StringUtils.hasText(line)) {
                summary = line;
                continue;
            }
            if ("Execution Steps".equalsIgnoreCase(section)) {
                if (line.startsWith("### ")) {
                    executionSteps.add(line.substring(4).trim());
                    continue;
                }
                if (line.startsWith("- ")) {
                    executionSteps.add(line.substring(2).trim());
                }
                continue;
            }
            if ("Output Contract".equalsIgnoreCase(section) && line.startsWith("- ")) {
                outputContract = outputContract + line.substring(2).trim() + "; ";
            }
        }

        if (!StringUtils.hasText(summary)) {
            summary = "按技能文件步骤执行并输出结果。";
        }
        if (!StringUtils.hasText(name)) {
            name = "file-skill";
        }
        return new ParsedSkillDocument(name, summary, executionSteps, outputContract.trim());
    }
}
