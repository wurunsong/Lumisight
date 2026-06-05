package com.lumisight.core.service.browser;

import java.util.List;

public record BrowserPageSnapshot(
        String url,
        String title,
        String visibleText,
        List<BrowserElementSummary> elements
) {
}
