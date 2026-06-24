package com.lumisight.core.service.browser;

public record BrowserElementSummary(
        String elementId,
        String selector,
        String actionSelector,
        String tag,
        String role,
        String type,
        String text,
        String href,
        String placeholder,
        String ariaLabel,
        String value,
        boolean enabled,
        boolean visible
) {
}
