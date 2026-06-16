package com.lumisight.core.support.memory;

import com.lumisight.memory.dto.MemoryWriteRequest;
import com.lumisight.memory.enums.MemoryType;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class MemoryWriteRules {

    private static final Pattern ABSOLUTE_DATE_PATTERN = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b");

    private MemoryWriteRules() {
    }

    public static List<String> validate(MemoryWriteRequest request) {
        List<String> errors = new ArrayList<>();
        if (request == null) {
            errors.add("memory request is required");
            return errors;
        }
        if (!StringUtils.hasText(request.name())) {
            errors.add("name 是必填参数");
        }
        if (!StringUtils.hasText(request.description())) {
            errors.add("description 是必填参数");
        }
        if (request.type() == null) {
            errors.add("type 是必填参数");
        }
        if (!StringUtils.hasText(request.body())) {
            errors.add("body 是必填参数");
        }
        if (request.type() == MemoryType.FEEDBACK && StringUtils.hasText(request.body())) {
            String lower = request.body().toLowerCase();
            if (!lower.contains("why:") && !lower.contains("**why:**")) {
                errors.add("feedback 记忆必须包含 Why");
            }
            if (!lower.contains("how to apply:") && !lower.contains("**how to apply:**")) {
                errors.add("feedback 记忆必须包含 How to apply");
            }
        }
        if (request.type() == MemoryType.PROJECT
                && StringUtils.hasText(request.body())
                && containsRelativeDate(request.body())
                && !ABSOLUTE_DATE_PATTERN.matcher(request.body()).find()) {
            errors.add("project 记忆涉及日期时必须写绝对日期，例如 2026-03-05");
        }
        return errors;
    }

    public static boolean containsRelativeDate(String body) {
        String lower = body.toLowerCase();
        return lower.contains("今天")
                || lower.contains("明天")
                || lower.contains("昨天")
                || lower.contains("周一")
                || lower.contains("周二")
                || lower.contains("周三")
                || lower.contains("周四")
                || lower.contains("周五")
                || lower.contains("周六")
                || lower.contains("周日")
                || lower.contains("最近")
                || lower.contains("today")
                || lower.contains("tomorrow")
                || lower.contains("yesterday")
                || lower.contains("recently");
    }
}
