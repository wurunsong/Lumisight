package com.lumisight.core.service.browser;

public record BrowserElementSummary(
        String selector,
        String tag,
        String role,
        String type,
        String text,
        String href,
        String placeholder
) {
}
