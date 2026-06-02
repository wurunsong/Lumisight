package com.lumisight.core.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.lumisight.core.model.ToolArgumentSpec;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ToolArgsSupport {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder()
            .findAndAddModules()
            .build();

    private ToolArgsSupport() {
    }

    public static List<ToolArgumentSpec> argumentSpecs(Class<?> argsType) {
        if (argsType == null || argsType == NoToolArgs.class || !argsType.isRecord()) {
            return List.of();
        }
        return Arrays.stream(argsType.getRecordComponents())
                .map(component -> {
                    ToolArg arg = component.getAnnotation(ToolArg.class);
                    return new ToolArgumentSpec(
                            component.getName(),
                            toSchemaType(component.getType()),
                            arg != null && arg.required(),
                            arg == null ? "" : arg.description()
                    );
                })
                .toList();
    }

    public static Map<String, Object> exampleArgs(Class<?> argsType) {
        if (argsType == null || argsType == NoToolArgs.class || !argsType.isRecord()) {
            return Map.of();
        }
        Map<String, Object> examples = new LinkedHashMap<>();
        for (RecordComponent component : argsType.getRecordComponents()) {
            ToolArg arg = component.getAnnotation(ToolArg.class);
            if (arg == null) {
                continue;
            }
            Object exampleValue = parseExample(component.getType(), arg);
            if (exampleValue != null) {
                examples.put(component.getName(), exampleValue);
            }
        }
        return examples;
    }

    private static Object parseExample(Class<?> type, ToolArg arg) {
        try {
            if (!arg.exampleJson().isBlank()) {
                return OBJECT_MAPPER.readValue(arg.exampleJson(), Object.class);
            }
            if (arg.example().isBlank()) {
                return null;
            }
            String example = arg.example();
            if (type == String.class) {
                return example;
            }
            if (type == Integer.class || type == int.class) {
                return Integer.parseInt(example);
            }
            if (type == Long.class || type == long.class) {
                return Long.parseLong(example);
            }
            if (type == Boolean.class || type == boolean.class) {
                return Boolean.parseBoolean(example);
            }
            if (Number.class.isAssignableFrom(type)) {
                return OBJECT_MAPPER.readValue(example, Object.class);
            }
            if (type.isEnum()) {
                return example;
            }
            return OBJECT_MAPPER.readValue(example, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static String toSchemaType(Class<?> type) {
        if (type == null) {
            return "object";
        }
        if (type == String.class || type.isEnum()) {
            return "string";
        }
        if (type == Integer.class || type == int.class
                || type == Long.class || type == long.class
                || type == Short.class || type == short.class
                || type == Byte.class || type == byte.class) {
            return "integer";
        }
        if (type == Float.class || type == float.class
                || type == Double.class || type == double.class) {
            return "number";
        }
        if (type == Boolean.class || type == boolean.class) {
            return "boolean";
        }
        if (List.class.isAssignableFrom(type) || type.isArray()) {
            return "array";
        }
        if (Map.class.isAssignableFrom(type)) {
            return "object";
        }
        return "object";
    }
}
