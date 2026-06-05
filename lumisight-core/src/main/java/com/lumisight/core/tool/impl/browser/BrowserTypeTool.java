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
            @ToolArg(description = "要填入内容的 CSS selector", required = true, example = "input[name='email']")
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
        return "向 input/textarea 等元素填入文本，可选清空后输入，也可在输入后按 Enter 提交。";
    }

    @Override
    public List<String> validateArgs(Args args) {
        if (args == null) {
            return List.of("browser type args is required");
        }
        List<String> errors = new java.util.ArrayList<>();
        if (!StringUtils.hasText(args.selector())) {
            errors.add("selector 是必填参数");
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
                args.selector(),
                args.text(),
                args.clearFirst() == null || args.clearFirst(),
                args.pressEnter() != null && args.pressEnter()
        );
        return List.of(BrowserToolSupport.item(
                "browser_type",
                result.url(),
                "%s selector=%s url=%s title=%s".formatted(result.message(), result.selector(), result.url(), result.title()),
                Map.of("selector", result.selector(), "url", result.url(), "title", result.title())
        ));
    }
}
