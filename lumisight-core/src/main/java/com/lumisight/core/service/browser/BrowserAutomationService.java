package com.lumisight.core.service.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BrowserAutomationService {

    private static final Logger log = LoggerFactory.getLogger(BrowserAutomationService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(java.time.ZoneOffset.UTC);

    private final BrowserAutomationProperties properties;
    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    private Playwright playwright;
    private Browser browser;

    public BrowserAutomationService(BrowserAutomationProperties properties) {
        this.properties = properties;
    }

    public BrowserOpenResult open(String repoRoot, String sessionId, String url) {
        ensureEnabled();
        if (!StringUtils.hasText(url)) {
            throw new IllegalArgumentException("url 是必填参数");
        }
        SessionState state = ensureSession(sessionId);
        synchronized (state) {
            state.page.navigate(url.trim(), new Page.NavigateOptions().setTimeout((double) properties.getNavigationTimeoutMs()));
            state.page.waitForLoadState();
            return new BrowserOpenResult(state.page.url(), safeTitle(state.page));
        }
    }

    public BrowserPageSnapshot snapshot(String sessionId) {
        ensureEnabled();
        SessionState state = requireSession(sessionId);
        synchronized (state) {
            String visibleText = trimText(state.page.locator("body").innerText(new Locator.InnerTextOptions().setTimeout((double) properties.getActionTimeoutMs())));
            Object raw = state.page.evaluate("""
                    maxElements => {
                      const nodes = Array.from(document.querySelectorAll('a, button, input, textarea, select, [role], [data-testid]'));
                      const seen = new Set();
                      const items = [];
                      function selectorFor(el) {
                        if (el.id) return `#${el.id}`;
                        if (el.getAttribute('data-testid')) return `[data-testid="${el.getAttribute('data-testid')}"]`;
                        if (el.getAttribute('name')) return `${el.tagName.toLowerCase()}[name="${el.getAttribute('name')}"]`;
                        if (el.getAttribute('aria-label')) return `${el.tagName.toLowerCase()}[aria-label="${el.getAttribute('aria-label')}"]`;
                        const classes = Array.from(el.classList || []).slice(0, 2).join('.');
                        return classes ? `${el.tagName.toLowerCase()}.${classes}` : el.tagName.toLowerCase();
                      }
                      for (const el of nodes) {
                        if (!el || !el.isConnected) continue;
                        const style = window.getComputedStyle(el);
                        if (style.display === 'none' || style.visibility === 'hidden') continue;
                        const selector = selectorFor(el);
                        if (seen.has(selector)) continue;
                        seen.add(selector);
                        items.push({
                          selector,
                          tag: el.tagName.toLowerCase(),
                          role: el.getAttribute('role') || '',
                          type: el.getAttribute('type') || '',
                          text: (el.innerText || el.textContent || '').trim().slice(0, 160),
                          href: el.getAttribute('href') || '',
                          placeholder: el.getAttribute('placeholder') || ''
                        });
                        if (items.length >= maxElements) break;
                      }
                      return items;
                    }
                    """, properties.getMaxSnapshotElements());
            List<BrowserElementSummary> elements = new ArrayList<>();
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        elements.add(new BrowserElementSummary(
                                string(map.get("selector")),
                                string(map.get("tag")),
                                string(map.get("role")),
                                string(map.get("type")),
                                string(map.get("text")),
                                string(map.get("href")),
                                string(map.get("placeholder"))
                        ));
                    }
                }
            }
            return new BrowserPageSnapshot(state.page.url(), safeTitle(state.page), visibleText, List.copyOf(elements));
        }
    }

    public BrowserActionResult click(String sessionId, String selector) {
        ensureEnabled();
        SessionState state = requireSession(sessionId);
        if (!StringUtils.hasText(selector)) {
            throw new IllegalArgumentException("selector 是必填参数");
        }
        synchronized (state) {
            state.page.locator(selector.trim()).first().click(new Locator.ClickOptions().setTimeout((double) properties.getActionTimeoutMs()));
            state.page.waitForLoadState();
            return new BrowserActionResult("click", selector.trim(), state.page.url(), safeTitle(state.page), "clicked");
        }
    }

    public BrowserActionResult type(String sessionId, String selector, String text, boolean clearFirst, boolean pressEnter) {
        ensureEnabled();
        SessionState state = requireSession(sessionId);
        if (!StringUtils.hasText(selector)) {
            throw new IllegalArgumentException("selector 是必填参数");
        }
        synchronized (state) {
            Locator locator = state.page.locator(selector.trim()).first();
            if (clearFirst) {
                locator.fill("", new Locator.FillOptions().setTimeout((double) properties.getActionTimeoutMs()));
            }
            locator.fill(text == null ? "" : text, new Locator.FillOptions().setTimeout((double) properties.getActionTimeoutMs()));
            if (pressEnter) {
                locator.press("Enter", new Locator.PressOptions().setTimeout((double) properties.getActionTimeoutMs()));
                state.page.waitForLoadState();
            }
            return new BrowserActionResult("type", selector.trim(), state.page.url(), safeTitle(state.page), pressEnter ? "typed and pressed Enter" : "typed");
        }
    }

    public BrowserScreenshotResult screenshot(String repoRoot, String sessionId, String label, boolean fullPage) {
        ensureEnabled();
        SessionState state = requireSession(sessionId);
        synchronized (state) {
            Path path = allocateScreenshotPath(repoRoot, sessionId, label);
            state.page.screenshot(new Page.ScreenshotOptions()
                    .setPath(path)
                    .setFullPage(fullPage));
            return new BrowserScreenshotResult(path.toString(), state.page.url(), safeTitle(state.page));
        }
    }

    public void close(String sessionId) {
        SessionState state = sessions.remove(normalizeSessionId(sessionId));
        if (state == null) {
            return;
        }
        synchronized (state) {
            try {
                state.context.close();
            } catch (Exception e) {
                log.warn("browser_session_close_failed, sessionId={}, error={}", sessionId, e.getMessage());
            }
        }
    }

    private SessionState ensureSession(String sessionId) {
        String key = normalizeSessionId(sessionId);
        return sessions.computeIfAbsent(key, ignored -> {
            ensureBrowser();
            Browser.NewContextOptions options = new Browser.NewContextOptions()
                    .setViewportSize(properties.getViewportWidth(), properties.getViewportHeight());
            BrowserContext context = browser.newContext(options);
            Page page = context.newPage();
            page.setDefaultNavigationTimeout(properties.getNavigationTimeoutMs());
            page.setDefaultTimeout(properties.getActionTimeoutMs());
            return new SessionState(context, page);
        });
    }

    private SessionState requireSession(String sessionId) {
        SessionState state = sessions.get(normalizeSessionId(sessionId));
        if (state == null) {
            throw new IllegalStateException("当前会话还没有活动浏览器页面，请先调用 browser_open");
        }
        return state;
    }

    private void ensureBrowser() {
        if (browser != null) {
            return;
        }
        synchronized (this) {
            if (browser != null) {
                return;
            }
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(properties.isHeadless()));
        }
    }

    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("浏览器能力当前未启用");
        }
    }

    private Path allocateScreenshotPath(String repoRoot, String sessionId, String label) {
        try {
            Path root = StringUtils.hasText(repoRoot)
                    ? Path.of(repoRoot).toAbsolutePath().normalize()
                    : Path.of("").toAbsolutePath().normalize();
            Path dir = root.resolve(properties.getArtifactDir()).resolve(normalizeSessionId(sessionId)).normalize();
            Files.createDirectories(dir);
            String safeLabel = StringUtils.hasText(label) ? label.replaceAll("[^A-Za-z0-9_.-]", "_") : "page";
            return dir.resolve(TS.format(Instant.now()) + "-" + safeLabel + "-" + UUID.randomUUID() + ".png");
        } catch (Exception e) {
            throw new IllegalStateException("failed to allocate screenshot path", e);
        }
    }

    private String normalizeSessionId(String sessionId) {
        return StringUtils.hasText(sessionId) ? sessionId.trim() : "__browser_global__";
    }

    private String safeTitle(Page page) {
        String title = page.title();
        return title == null ? "" : title.trim();
    }

    private String trimText(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String normalized = text.trim();
        if (normalized.length() <= properties.getMaxSnapshotTextChars()) {
            return normalized;
        }
        return normalized.substring(0, properties.getMaxSnapshotTextChars()) + "\n...(visible text truncated)";
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record SessionState(BrowserContext context, Page page) {
    }
}
