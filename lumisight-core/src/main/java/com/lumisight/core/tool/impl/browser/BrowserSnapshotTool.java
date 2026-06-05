package com.lumisight.core.tool.impl.browser;

import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.service.browser.BrowserAutomationService;
import com.lumisight.core.service.browser.BrowserPageSnapshot;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import com.lumisight.core.tool.NoToolArgs;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class BrowserSnapshotTool implements PermissionedAgentTool<NoToolArgs> {

    private final BrowserAutomationService browserAutomationService;

    public BrowserSnapshotTool(BrowserAutomationService browserAutomationService) {
        this.browserAutomationService = browserAutomationService;
    }

    @Override
    public String toolName() {
        return "browser_snapshot";
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
    public Class<NoToolArgs> argsType() {
        return NoToolArgs.class;
    }

    @Override
    public String description() {
        return "抓取当前页面的标题、URL、可见文本和交互元素摘要，用于 DOM 识别、选择器选择和浏览器态调试。";
    }

    @Override
    public List<AgentContextItem> invoke(NoToolArgs args, int defaultLimit) {
        BrowserPageSnapshot snapshot = browserAutomationService.snapshot(BrowserToolSupport.sessionId());
        return List.of(BrowserToolSupport.item(
                "browser_snapshot",
                snapshot.url(),
                BrowserToolSupport.renderSnapshot(snapshot),
                Map.of("url", snapshot.url(), "title", snapshot.title(), "elementCount", snapshot.elements().size())
        ));
    }
}
