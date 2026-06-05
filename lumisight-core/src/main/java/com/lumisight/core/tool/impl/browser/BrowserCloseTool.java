package com.lumisight.core.tool.impl.browser;

import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.NoToolArgs;
import com.lumisight.core.tool.PermissionedAgentTool;
import com.lumisight.core.service.browser.BrowserAutomationService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class BrowserCloseTool implements PermissionedAgentTool<NoToolArgs> {

    private final BrowserAutomationService browserAutomationService;

    public BrowserCloseTool(BrowserAutomationService browserAutomationService) {
        this.browserAutomationService = browserAutomationService;
    }

    @Override
    public String toolName() {
        return "browser_close";
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
    public Class<NoToolArgs> argsType() {
        return NoToolArgs.class;
    }

    @Override
    public String description() {
        return "关闭当前会话对应的浏览器页面与上下文，释放浏览器资源。";
    }

    @Override
    public List<AgentContextItem> invoke(NoToolArgs args, int defaultLimit) {
        browserAutomationService.close(BrowserToolSupport.sessionId());
        return List.of(BrowserToolSupport.item(
                "browser_close",
                BrowserToolSupport.sessionId(),
                "Browser session closed",
                Map.of("sessionId", BrowserToolSupport.sessionId())
        ));
    }
}
