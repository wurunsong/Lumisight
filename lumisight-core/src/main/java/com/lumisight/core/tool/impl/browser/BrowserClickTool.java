package com.lumisight.core.tool.impl.browser;

import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.service.browser.BrowserActionResult;
import com.lumisight.core.service.browser.BrowserAutomationService;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import com.lumisight.core.tool.ToolArg;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Component
public class BrowserClickTool implements PermissionedAgentTool<BrowserClickTool.Args> {

    public record Args(
            @ToolArg(description = "要点击的 CSS selector", required = true, example = "button[type='submit']")
            String selector
    ) {
    }

    private final BrowserAutomationService browserAutomationService;

    public BrowserClickTool(BrowserAutomationService browserAutomationService) {
        this.browserAutomationService = browserAutomationService;
    }

    @Override
    public String toolName() {
        return "browser_click";
    }

    @Override
    public AgentToolCategory category() {
        return AgentToolCategory.BROWSER;
    }

    @Override
    public AgentToolPermission permission() {
        return AgentToolPermission.BROWSER_WRITE;
    }

    @Override
    public Class<Args> argsType() {
        return Args.class;
    }

    @Override
    public String description() {
        return "点击当前页面上的一个 DOM 元素。通常先用 browser_snapshot 观察可用 selector，再执行点击。";
    }

    @Override
    public List<String> validateArgs(Args args) {
        if (args == null || !StringUtils.hasText(args.selector())) {
            return List.of("selector 是必填参数");
        }
        return List.of();
    }

    @Override
    public List<AgentContextItem> invoke(Args args, int defaultLimit) {
        BrowserActionResult result = browserAutomationService.click(BrowserToolSupport.sessionId(), args.selector());
        return List.of(BrowserToolSupport.item(
                "browser_click",
                result.url(),
                "%s selector=%s url=%s title=%s".formatted(result.message(), result.selector(), result.url(), result.title()),
                Map.of("selector", result.selector(), "url", result.url(), "title", result.title())
        ));
    }
}
