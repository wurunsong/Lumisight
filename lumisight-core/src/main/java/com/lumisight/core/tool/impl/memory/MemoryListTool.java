package com.lumisight.core.tool.impl.memory;

import com.lumisight.core.context.ambient.AgentToolRuntimeContext;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import com.lumisight.core.tool.ToolArg;
import com.lumisight.memory.dto.MemoryHeader;
import com.lumisight.memory.MemoryService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

@Component
public class MemoryListTool implements PermissionedAgentTool<MemoryListTool.Args> {

    public record Args(
            @ToolArg(description = "最多返回多少条记忆摘要", example = "10")
            Integer limit
    ) {
    }

    private final MemoryService memoryService;

    public MemoryListTool(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public String toolName() {
        return "memory_list";
    }

    @Override
    public AgentToolCategory category() {
        return AgentToolCategory.MEMORY;
    }

    @Override
    public AgentToolPermission permission() {
        return AgentToolPermission.MEMORY_READ;
    }

    @Override
    public Class<Args> argsType() {
        return Args.class;
    }

    @Override
    public String description() {
        return "列出当前用户在当前仓库下的长期记忆摘要，适合先判断是否已有可复用的画像、反馈、项目动态或参考指针。";
    }

    @Override
    public List<AgentContextItem> invoke(Args args, int defaultLimit) {
        AgentToolRuntimeContext.Context context = AgentToolRuntimeContext.required();
        int limit = args == null || args.limit() == null ? 10 : Math.max(1, Math.min(50, args.limit()));
        List<MemoryHeader> headers = memoryService.list(context.repoRoot(), context.userId());
        StringJoiner joiner = new StringJoiner("\n");
        headers.stream().limit(limit).forEach(header ->
                joiner.add("- [" + header.type().wireValue() + "] " + header.filename() + ": " + header.description()));
        String content = joiner.length() == 0 ? "暂无长期记忆" : joiner.toString();
        return List.of(new AgentContextItem("memory_index", "MEMORY.md", content, Map.of("count", Math.min(limit, headers.size()))));
    }
}
