package com.lumisight.core.tool.impl.browser;

import com.lumisight.core.context.AgentToolRuntimeContext;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.service.browser.BrowserAutomationService;
import com.lumisight.core.service.browser.BrowserOpenResult;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import com.lumisight.core.tool.ToolArg;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Component
public class BrowserOpenTool implements PermissionedAgentTool<BrowserOpenTool.Args> {

    public record Args(
            @ToolArg(description = "要打开的网页 URL", required = true, example = "http://127.0.0.1:8080/agent-console.html")
            String url
    ) {
    }

    private final BrowserAutomationService browserAutomationService;

    public BrowserOpenTool(BrowserAutomationService browserAutomationService) {
        this.browserAutomationService = browserAutomationService;
    }

    @Override
    public String toolName() {
        return "browser_open";
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
        return "打开网页并为当前会话建立一个可复用的浏览器页面。适合本地页面验证、DOM 调试和后续点击/输入操作。";
    }

    @Override
    public List<String> validateArgs(Args args) {
        if (args == null || !StringUtils.hasText(args.url())) {
            return List.of("url 是必填参数");
        }
        return List.of();
    }

    @Override
    public List<AgentContextItem> invoke(Args args, int defaultLimit) {
        AgentToolRuntimeContext.Context runtime = AgentToolRuntimeContext.required();
        BrowserOpenResult result = browserAutomationService.open(runtime.repoRoot(), BrowserToolSupport.sessionId(), args.url());
        return List.of(BrowserToolSupport.item(
                "browser_open",
                result.url(),
                "Opened %s\nTitle: %s".formatted(result.url(), result.title()),
                Map.of("url", result.url(), "title", result.title())
        ));
    }
}
