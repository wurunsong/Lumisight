package com.lumisight.core.tool.impl.browser;

import com.lumisight.core.context.AgentToolInvocationContext;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.service.browser.BrowserElementSummary;
import com.lumisight.core.service.browser.BrowserPageSnapshot;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

final class BrowserToolSupport {

    private BrowserToolSupport() {
    }

    static String sessionId() {
        AgentToolInvocationContext.Context context = AgentToolInvocationContext.current();
        if (context == null || !StringUtils.hasText(context.sessionId())) {
            return "__browser_global__";
        }
        return context.sessionId().trim();
    }

    static AgentContextItem item(String sourceType, String sourceId, String content, Map<String, Object> metadata) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (metadata != null) {
            merged.putAll(metadata);
        }
        merged.putIfAbsent("logicalResourceId", "browser:" + sourceId);
        return new AgentContextItem(sourceType, sourceId, content, Map.copyOf(merged));
    }

    static String renderSnapshot(BrowserPageSnapshot snapshot) {
        StringJoiner joiner = new StringJoiner("\n");
        joiner.add("URL: " + blank(snapshot.url()));
        joiner.add("Title: " + blank(snapshot.title()));
        joiner.add("");
        joiner.add("Visible text:");
        joiner.add(blank(snapshot.visibleText()));
        joiner.add("");
        joiner.add("Interactive elements:");
        List<BrowserElementSummary> elements = snapshot.elements();
        if (elements == null || elements.isEmpty()) {
            joiner.add("- none");
            return joiner.toString();
        }
        for (BrowserElementSummary element : elements) {
            joiner.add("- selector=%s tag=%s role=%s type=%s text=%s href=%s placeholder=%s".formatted(
                    blank(element.selector()),
                    blank(element.tag()),
                    blank(element.role()),
                    blank(element.type()),
                    oneLine(element.text()),
                    blank(element.href()),
                    oneLine(element.placeholder())
            ));
        }
        return joiner.toString();
    }

    private static String blank(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private static String oneLine(String value) {
        return blank(value).replace('\n', ' ');
    }
}
