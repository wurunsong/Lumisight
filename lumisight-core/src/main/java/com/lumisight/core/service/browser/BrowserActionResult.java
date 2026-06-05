package com.lumisight.core.service.browser;

public record BrowserActionResult(
        String action,
        String selector,
        String url,
        String title,
        String message
) {
}
