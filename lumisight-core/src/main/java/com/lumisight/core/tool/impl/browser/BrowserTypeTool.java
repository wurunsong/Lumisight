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
public class BrowserTypeTool implements PermissionedAgentTool<BrowserTypeTool.Args> {

    public record Args(
            @ToolArg(description = "来自最近一次 browser_snapshot 的元素 ID；优先使用它避免填错输入框", example = "el-1")
            String elementId,
            @ToolArg(description = "要填入内容的 CSS selector；仅在没有 elementId 时作为兼容兜底", example = "input[name='email']")
            String selector,
            @ToolArg(description = "要输入的文本", required = true, example = "test@example.com")
            String text,
            @ToolArg(description = "输入前是否清空", example = "true")
            Boolean clearFirst,
            @ToolArg(description = "输入后是否按 Enter", example = "false")
            Boolean pressEnter
    ) {
    }

    private final BrowserAutomationService browserAutomationService;

    public BrowserTypeTool(BrowserAutomationService browserAutomationService) {
        this.browserAutomationService = browserAutomationService;
    }

    @Override
    public String toolName() {
        return "browser_type";
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
        return "向 input/textarea 等元素填入文本。优先使用最近一次 browser_snapshot 返回的 elementId，避免把文本填进错误输入框。";
    }

    @Override
    public List<String> validateArgs(Args args) {
        if (args == null) {
            return List.of("browser type args is required");
        }
        List<String> errors = new java.util.ArrayList<>();
        if (!StringUtils.hasText(args.selector()) && !StringUtils.hasText(args.elementId())) {
            errors.add("elementId 或 selector 至少填一个");
        }
        if (args.text() == null) {
            errors.add("text 是必填参数");
        }
        return errors;
    }

    @Override
    public List<AgentContextItem> invoke(Args args, int defaultLimit) {
        BrowserActionResult result = browserAutomationService.type(
                BrowserToolSupport.sessionId(),
                args.elementId(),
                args.selector(),
                args.text(),
                args.clearFirst() == null || args.clearFirst(),
                args.pressEnter() != null && args.pressEnter()
        );
        return List.of(BrowserToolSupport.item(
                "browser_type",
                result.url(),
                "%s selector=%s url=%s title=%s".formatted(result.message(), result.selector(), result.url(), result.title()),
                Map.of(
                        "elementId", args.elementId() == null ? "" : args.elementId(),
                        "selector", result.selector(),
                        "url", result.url(),
                        "title", result.title()
                )
        ));
    }
}
