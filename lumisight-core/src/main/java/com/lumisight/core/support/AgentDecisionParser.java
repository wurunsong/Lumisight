package com.lumisight.core.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lumisight.core.model.ToolDecision;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class AgentDecisionParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public ToolDecision parseOrFallback(String raw) throws Exception {
        String json = extractJsonObject(raw);
        return objectMapper.readValue(json, ToolDecision.class);
    }

    public ToolDecision fallbackDecision(String errorMessage) {
        return new ToolDecision("final", null, Map.of(), List.of(), "模型决策解析失败，直接给出最终回答。", errorMessage, null);
    }

    public String extractJsonObject(String raw) {
        if (raw == null) {
            return "{}";
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw;
    }
}
