package com.lumisight.core.tool.impl.browser;

import com.lumisight.core.context.ambient.ToolInvocationScope;
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
        ToolInvocationScope.Context invocationScope = ToolInvocationScope.current();
        if (invocationScope == null || !StringUtils.hasText(invocationScope.sessionId())) {
            return "__browser_global__";
        }
        return invocationScope.sessionId().trim();
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
            joiner.add("- elementId=%s selector=%s tag=%s role=%s type=%s text=%s href=%s placeholder=%s ariaLabel=%s value=%s enabled=%s visible=%s".formatted(
                    blank(element.elementId()),
                    blank(element.selector()),
                    blank(element.tag()),
                    blank(element.role()),
                    blank(element.type()),
                    oneLine(element.text()),
                    blank(element.href()),
                    oneLine(element.placeholder()),
                    oneLine(element.ariaLabel()),
                    oneLine(element.value()),
                    element.enabled(),
                    element.visible()
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
