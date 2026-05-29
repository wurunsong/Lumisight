package com.lumisight.core.agent.support;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ToolArgumentValidators {

    private ToolArgumentValidators() {
    }

    public static List<String> requireText(Map<String, Object> args, String key, String label) {
        List<String> errors = new ArrayList<>();
        String value = args.get(key) == null ? "" : String.valueOf(args.get(key));
        if (!StringUtils.hasText(value)) {
            errors.add(label + "不能为空");
        }
        return errors;
    }

    public static List<String> requireInteger(Map<String, Object> args, String key, String label) {
        List<String> errors = new ArrayList<>();
        Object value = args.get(key);
        if (value == null) {
            errors.add(label + "不能为空");
            return errors;
        }
        try {
            Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            errors.add(label + "必须是整数");
        }
        return errors;
    }
}
