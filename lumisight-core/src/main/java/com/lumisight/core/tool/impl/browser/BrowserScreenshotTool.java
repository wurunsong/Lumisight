package com.lumisight.core.tool.impl.browser;

import com.lumisight.core.context.ambient.ToolRuntimeScope;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.service.browser.BrowserAutomationService;
import com.lumisight.core.service.browser.BrowserScreenshotResult;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import com.lumisight.core.tool.ToolArg;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class BrowserScreenshotTool implements PermissionedAgentTool<BrowserScreenshotTool.Args> {

    public record Args(
            @ToolArg(description = "截图标签，用于文件名", example = "login-page")
            String label,
            @ToolArg(description = "是否截整页", example = "true")
            Boolean fullPage
    ) {
    }

    private final BrowserAutomationService browserAutomationService;

    public BrowserScreenshotTool(BrowserAutomationService browserAutomationService) {
        this.browserAutomationService = browserAutomationService;
    }

    @Override
    public String toolName() {
        return "browser_screenshot";
    }

    @Override
    public AgentToolCategory category() {
        return AgentToolCategory.BROWSER;
    }

    @Override
    public AgentToolPermission permission() {
        return AgentToolPermission.BROWSER_READ;
    }

    @Override
    public Class<Args> argsType() {
        return Args.class;
    }

    @Override
    public String description() {
        return "为当前页面截图，并把图片保存到本地浏览器 artifact 目录。";
    }

    @Override
    public List<AgentContextItem> invoke(Args args, int defaultLimit) {
        ToolRuntimeScope.Context runtime = ToolRuntimeScope.required();
        BrowserScreenshotResult result = browserAutomationService.screenshot(
                runtime.repoRoot(),
                BrowserToolSupport.sessionId(),
                args == null ? null : args.label(),
                args == null || args.fullPage() == null || args.fullPage()
        );
        return List.of(BrowserToolSupport.item(
                "browser_screenshot",
                result.path(),
                "Screenshot saved: %s\nURL: %s\nTitle: %s".formatted(result.path(), result.url(), result.title()),
                Map.of("path", result.path(), "url", result.url(), "title", result.title())
        ));
    }
}
