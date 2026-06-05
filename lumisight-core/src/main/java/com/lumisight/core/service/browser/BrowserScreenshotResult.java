package com.lumisight.core.service.browser;

public record BrowserScreenshotResult(
        String path,
        String url,
        String title
) {
}
