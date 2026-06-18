package com.lumisight.core.tool.impl.memory;

import com.lumisight.core.context.ambient.ToolRuntimeScope;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import com.lumisight.core.tool.ToolArg;
import com.lumisight.core.support.memory.MemoryWriteRules;
import com.lumisight.memory.dto.MemoryEntry;
import com.lumisight.memory.MemoryService;
import com.lumisight.memory.enums.MemoryType;
import com.lumisight.memory.dto.MemoryWriteRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class MemoryWriteTool implements PermissionedAgentTool<MemoryWriteTool.Args> {

    public record Args(
            @ToolArg(description = "记忆名称，简短且可读", required = true, example = "no-mock-database")
            String name,
            @ToolArg(description = "一句话摘要，供 MEMORY.md 检索使用", required = true, example = "集成测试必须使用真实数据库，不能用 mock")
            String description,
            @ToolArg(description = "记忆类型，只能是 user / feedback / project / reference", required = true, example = "feedback")
            String type,
            @ToolArg(
                    description = "记忆正文。feedback 需包含 Why 和 How to apply；project 若涉及日期必须写绝对日期。",
                    required = true,
                    example = "集成测试必须使用真实数据库，不能用 mock。\\n\\n**Why:** 上季度 mock 测试全部通过但生产迁移失败。\\n**How to apply:** 在这个模块写测试时始终连接真实数据库。"
            )
            String body
    ) {
    }

    private final MemoryService memoryService;

    public MemoryWriteTool(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public String toolName() {
        return "memory_write";
    }

    @Override
    public AgentToolCategory category() {
        return AgentToolCategory.MEMORY;
    }

    @Override
    public AgentToolPermission permission() {
        return AgentToolPermission.MEMORY_WRITE;
    }

    @Override
    public Class<Args> argsType() {
        return Args.class;
    }

    @Override
    public String description() {
        return "写入长期记忆。只存稳定的跨会话信息：用户画像、明确反馈、项目动态、外部参考指针；不要存代码结构、git 历史、临时任务状态或当前会话上下文。";
    }

    @Override
    public List<String> validateArgs(Args args) {
        List<String> errors = new ArrayList<>();
        if (args == null) {
            errors.add("memory args is required");
            return errors;
        }
        if (!StringUtils.hasText(args.name())) {
            errors.add("name 是必填参数");
        }
        if (!StringUtils.hasText(args.description())) {
            errors.add("description 是必填参数");
        }
        if (!StringUtils.hasText(args.body())) {
            errors.add("body 是必填参数");
        }
        MemoryType type = null;
        try {
            type = MemoryType.parse(args.type());
        } catch (Exception e) {
            errors.add(e.getMessage());
        }
        if (type != null) {
            errors.addAll(MemoryWriteRules.validate(new MemoryWriteRequest(args.name(), args.description(), type, args.body())));
        } else {
            if (!StringUtils.hasText(args.name())) {
                errors.add("name 是必填参数");
            }
            if (!StringUtils.hasText(args.description())) {
                errors.add("description 是必填参数");
            }
            if (!StringUtils.hasText(args.body())) {
                errors.add("body 是必填参数");
            }
        }
        return errors;
    }

    @Override
    public List<AgentContextItem> invoke(Args args, int defaultLimit) {
        ToolRuntimeScope.Context context = ToolRuntimeScope.required();
        MemoryType type = MemoryType.parse(args.type());
        MemoryWriteRequest request = new MemoryWriteRequest(args.name(), args.description(), type, args.body());
        MemoryEntry entry = memoryService.save(
                storageRootFor(type, context.repoRoot()),
                context.userId(),
                memoryRootDirFor(type),
                request
        );
        return List.of(new AgentContextItem(
                "memory_write",
                entry.filename(),
                "[" + entry.type().wireValue() + "] " + entry.description() + "\n" + entry.body(),
                Map.of("filename", entry.filename(), "type", entry.type().wireValue(), "name", entry.name())
        ));
    }

    private String storageRootFor(MemoryType type, String repoRoot) {
        if (usesUserProfileStore(type)) {
            return userProfileStorageRoot();
        }
        return repoRoot;
    }

    private String memoryRootDirFor(MemoryType type) {
        return usesUserProfileStore(type) ? "" : ".lumisight/memory";
    }

    private boolean usesUserProfileStore(MemoryType type) {
        return type == MemoryType.USER || type == MemoryType.FEEDBACK;
    }

    private String userProfileStorageRoot() {
        return Path.of(System.getProperty("user.home"), ".lumisight").toAbsolutePath().normalize().toString();
    }
}
