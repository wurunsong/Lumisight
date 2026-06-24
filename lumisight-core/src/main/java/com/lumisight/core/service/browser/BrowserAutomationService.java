package com.lumisight.core.service.browser;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
            stabilizePage(state.page);
            state.elementTargets().clear();
            return new BrowserOpenResult(state.page.url(), safeTitle(state.page));
        }
    }

    public BrowserPageSnapshot snapshot(String sessionId) {
        ensureEnabled();
        SessionState state = requireSession(sessionId);
        synchronized (state) {
            stabilizePage(state.page);
            String visibleText = trimText(state.page.locator("body").innerText(new Locator.InnerTextOptions().setTimeout((double) properties.getActionTimeoutMs())));
            Object raw = state.page.evaluate("""
                    maxElements => {
                      function escapeCss(value) {
                        if (window.CSS && typeof window.CSS.escape === 'function') return window.CSS.escape(String(value));
                        return String(value).replace(/[^a-zA-Z0-9_-]/g, ch => `\\${ch}`);
                      }
                      function isVisible(el) {
                        if (!el || !el.isConnected) return false;
                        const style = window.getComputedStyle(el);
                        if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') return false;
                        const rect = el.getBoundingClientRect();
                        return rect.width > 0 && rect.height > 0;
                      }
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
                      function uniqueSelectorFor(el) {
                        if (el.id) return `#${escapeCss(el.id)}`;
                        const dataTestId = el.getAttribute('data-testid');
                        if (dataTestId) {
                          const candidate = `[data-testid="${dataTestId.replace(/"/g, '\\"')}"]`;
                          if (document.querySelectorAll(candidate).length === 1) return candidate;
                        }
                        const name = el.getAttribute('name');
                        if (name) {
                          const candidate = `${el.tagName.toLowerCase()}[name="${name.replace(/"/g, '\\"')}"]`;
                          if (document.querySelectorAll(candidate).length === 1) return candidate;
                        }
                        const parts = [];
                        let current = el;
                        while (current && current.nodeType === Node.ELEMENT_NODE && current.tagName.toLowerCase() !== 'html') {
                          const tag = current.tagName.toLowerCase();
                          let index = 1;
                          let sibling = current.previousElementSibling;
                          while (sibling) {
                            if (sibling.tagName === current.tagName) index += 1;
                            sibling = sibling.previousElementSibling;
                          }
                          parts.unshift(`${tag}:nth-of-type(${index})`);
                          current = current.parentElement;
                        }
                        return parts.join(' > ');
                      }
                      for (const el of nodes) {
                        if (!el || !el.isConnected) continue;
                        const visible = isVisible(el);
                        if (!visible) continue;
                        const selector = selectorFor(el);
                        const actionSelector = uniqueSelectorFor(el);
                        if (!actionSelector || seen.has(actionSelector)) continue;
                        seen.add(actionSelector);
                        items.push({
                          selector,
                          actionSelector,
                          tag: el.tagName.toLowerCase(),
                          role: el.getAttribute('role') || '',
                          type: el.getAttribute('type') || '',
                          text: (el.innerText || el.textContent || '').trim().slice(0, 160),
                          href: el.getAttribute('href') || '',
                          placeholder: el.getAttribute('placeholder') || '',
                          ariaLabel: el.getAttribute('aria-label') || '',
                          value: 'value' in el ? String(el.value || '').slice(0, 160) : '',
                          enabled: !el.disabled,
                          visible
                        });
                        if (items.length >= maxElements) break;
                      }
                      return items;
                    }
                    """, properties.getMaxSnapshotElements());
            List<BrowserElementSummary> elements = new ArrayList<>();
            Map<String, ElementTarget> elementTargets = new LinkedHashMap<>();
            if (raw instanceof List<?> list) {
                int index = 1;
                for (Object item : list) {
                    if (item instanceof Map<?, ?> map) {
                        String actionSelector = string(map.get("actionSelector"));
                        if (!StringUtils.hasText(actionSelector)) {
                            continue;
                        }
                        String elementId = "el-" + index++;
                        elements.add(new BrowserElementSummary(
                                elementId,
                                string(map.get("selector")),
                                actionSelector,
                                string(map.get("tag")),
                                string(map.get("role")),
                                string(map.get("type")),
                                string(map.get("text")),
                                string(map.get("href")),
                                string(map.get("placeholder")),
                                string(map.get("ariaLabel")),
                                string(map.get("value")),
                                bool(map.get("enabled")),
                                bool(map.get("visible"))
                        ));
                        elementTargets.put(elementId, new ElementTarget(actionSelector, string(map.get("selector"))));
                    }
                }
            }
            state.elementTargets().clear();
            state.elementTargets().putAll(elementTargets);
            return new BrowserPageSnapshot(state.page.url(), safeTitle(state.page), visibleText, List.copyOf(elements));
        }
    }

    public BrowserActionResult click(String sessionId, String elementId, String selector) {
        ensureEnabled();
        SessionState state = requireSession(sessionId);
        if (!StringUtils.hasText(selector) && !StringUtils.hasText(elementId)) {
            throw new IllegalArgumentException("elementId 或 selector 是必填参数");
        }
        synchronized (state) {
            ResolvedTarget target = resolveTarget(state, elementId, selector);
            target.locator().click(new Locator.ClickOptions().setTimeout((double) properties.getActionTimeoutMs()));
            stabilizePage(state.page);
            state.elementTargets().clear();
            return new BrowserActionResult("click", target.selector(), state.page.url(), safeTitle(state.page), "clicked");
        }
    }

    public BrowserActionResult type(String sessionId, String elementId, String selector, String text, boolean clearFirst, boolean pressEnter) {
        ensureEnabled();
        SessionState state = requireSession(sessionId);
        if (!StringUtils.hasText(selector) && !StringUtils.hasText(elementId)) {
            throw new IllegalArgumentException("elementId 或 selector 是必填参数");
        }
        synchronized (state) {
            ResolvedTarget target = resolveTarget(state, elementId, selector);
            Locator locator = target.locator();
            if (clearFirst) {
                locator.fill("", new Locator.FillOptions().setTimeout((double) properties.getActionTimeoutMs()));
            }
            locator.fill(text == null ? "" : text, new Locator.FillOptions().setTimeout((double) properties.getActionTimeoutMs()));
            if (pressEnter) {
                locator.press("Enter", new Locator.PressOptions().setTimeout((double) properties.getActionTimeoutMs()));
                stabilizePage(state.page);
                state.elementTargets().clear();
            } else {
                settleDelay(state.page);
            }
            return new BrowserActionResult("type", target.selector(), state.page.url(), safeTitle(state.page), pressEnter ? "typed and pressed Enter" : "typed");
        }
    }

    public BrowserScreenshotResult screenshot(String repoRoot, String sessionId, String label, boolean fullPage) {
        ensureEnabled();
        SessionState state = requireSession(sessionId);
        synchronized (state) {
            stabilizePage(state.page);
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
            if (StringUtils.hasText(properties.getUserAgent())) {
                options.setUserAgent(properties.getUserAgent().trim());
            }
            if (StringUtils.hasText(properties.getLocale())) {
                options.setLocale(properties.getLocale().trim());
                options.setExtraHTTPHeaders(Map.of("Accept-Language", properties.getLocale().trim() + ",zh;q=0.9,en;q=0.8"));
            }
            if (StringUtils.hasText(properties.getTimezoneId())) {
                options.setTimezoneId(properties.getTimezoneId().trim());
            }
            BrowserContext context = browser.newContext(options);
            context.addInitScript("""
                    Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
                    Object.defineProperty(navigator, 'platform', { get: () => 'MacIntel' });
                    Object.defineProperty(navigator, 'languages', { get: () => ['zh-CN', 'zh', 'en'] });
                    window.chrome = window.chrome || { runtime: {} };
                    """);
            Page page = context.newPage();
            page.setDefaultNavigationTimeout(properties.getNavigationTimeoutMs());
            page.setDefaultTimeout(properties.getActionTimeoutMs());
            return new SessionState(context, page, new LinkedHashMap<>());
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

    private ResolvedTarget resolveTarget(SessionState state, String elementId, String selector) {
        if (StringUtils.hasText(elementId)) {
            ElementTarget target = state.elementTargets().get(elementId.trim());
            if (target == null) {
                throw new IllegalArgumentException("elementId 无效或已过期，请先重新执行 browser_snapshot 获取最新候选元素");
            }
            Locator locator = state.page.locator(target.actionSelector());
            long count = locator.count();
            if (count != 1) {
                throw new IllegalStateException("elementId 对应的页面元素已变化，请重新执行 browser_snapshot 后再操作");
            }
            return new ResolvedTarget(locator, target.actionSelector());
        }
        String normalizedSelector = selector == null ? "" : selector.trim();
        Locator locator = state.page.locator(normalizedSelector);
        long count = locator.count();
        if (count == 0) {
            throw new IllegalArgumentException("selector 未命中任何元素: " + normalizedSelector);
        }
        if (count > 1) {
            throw new IllegalArgumentException("selector 命中多个元素(" + count + ")，请先执行 browser_snapshot 并改用 elementId");
        }
        return new ResolvedTarget(locator, normalizedSelector);
    }

    private void stabilizePage(Page page) {
        page.waitForLoadState();
        waitForNetworkIdle(page);
        settleDelay(page);
    }

    private void waitForNetworkIdle(Page page) {
        if (properties.getNetworkIdleTimeoutMs() <= 0) {
            return;
        }
        try {
            page.waitForLoadState(
                    LoadState.NETWORKIDLE,
                    new Page.WaitForLoadStateOptions().setTimeout((double) properties.getNetworkIdleTimeoutMs())
            );
        } catch (Exception e) {
            log.debug("browser_network_idle_wait_skipped, reason={}", e.getMessage());
        }
    }

    private void settleDelay(Page page) {
        if (properties.getPostActionDelayMs() <= 0) {
            return;
        }
        page.waitForTimeout(properties.getPostActionDelayMs());
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

    private boolean bool(Object value) {
        return value instanceof Boolean flag && flag;
    }

    private record SessionState(BrowserContext context, Page page, Map<String, ElementTarget> elementTargets) {
    }

    private record ElementTarget(String actionSelector, String summarySelector) {
    }

    private record ResolvedTarget(Locator locator, String selector) {
    }
}
