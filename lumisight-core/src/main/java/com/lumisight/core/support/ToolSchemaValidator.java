package com.lumisight.core.support;

import com.lumisight.core.model.ToolArgumentSpec;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class ToolSchemaValidator {

    private ToolSchemaValidator() {
    }

    public static List<String> validate(Map<String, Object> args, List<ToolArgumentSpec> specs) {
        List<String> errors = new ArrayList<>();
        if (specs == null || specs.isEmpty()) {
            return errors;
        }
        for (ToolArgumentSpec spec : specs) {
            Object value = args.get(spec.name());
            if (spec.required() && (value == null || (value instanceof String s && !StringUtils.hasText(s)))) {
                errors.add(spec.name() + " 是必填参数");
                continue;
            }
            if (value == null) {
                continue;
            }
            if (!isTypeCompatible(value, spec.type())) {
                errors.add(spec.name() + " 类型不匹配，期望 " + spec.type());
            }
        }
        return errors;
    }

    private static boolean isTypeCompatible(Object value, String type) {
        if (type == null) {
            return true;
        }
        return switch (type.toLowerCase()) {
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "object" -> value instanceof Map<?, ?>;
            default -> true;
        };
    }
}
