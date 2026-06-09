package com.lumisight.core.support;

import com.lumisight.core.model.AgentRequest;
import com.lumisight.memory.MemoryEntry;
import com.lumisight.memory.MemoryEntrypoint;
import com.lumisight.memory.MemoryHeader;
import com.lumisight.memory.MemoryService;
import com.lumisight.memory.MemoryType;
import com.lumisight.memory.RelevantMemoryContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RelevantMemoryService {

    private static final Logger log = LoggerFactory.getLogger(RelevantMemoryService.class);
    private static final Pattern FILENAME_PATTERN = Pattern.compile("[a-z0-9_\\-]+\\.md", Pattern.CASE_INSENSITIVE);
    private static final int MAX_RELEVANT = 5;

    private final ChatClient selectorChatClient;
    private final StreamingChatClientSupport streamingChatClientSupport;
    private final MemoryService memoryService;

    public RelevantMemoryService(
            ChatClient.Builder chatClientBuilder,
            StreamingChatClientSupport streamingChatClientSupport,
            MemoryService memoryService
    ) {
        this.selectorChatClient = chatClientBuilder.build();
        this.streamingChatClientSupport = streamingChatClientSupport;
        this.memoryService = memoryService;
    }

    /**
     * 预取相关记忆
     * @param request 结构化agent请求
     * @return 相关记忆
     */
    public CompletableFuture<RelevantMemoryContext> prefetch(AgentRequest request) {
        return CompletableFuture.supplyAsync(() -> resolveRelevant(request.repoRoot(), request.userId(), request.question()));
    }

    public RelevantMemoryContext resolveRelevant(String repoRoot, String userId, String query) {
        try {
            // todo 这里需要改成既从仓库获取仓库记忆，也从根目录获取长期记忆（有关于用户信息的记忆）
            // 获取该用户在该仓库的记忆索引
            MemoryEntrypoint entrypoint = memoryService.loadEntrypoint(repoRoot, userId);
            // 获取该用户在该仓库的长期记忆header
            List<MemoryHeader> headers = memoryService.scanHeaders(repoRoot, userId);
            if (headers.isEmpty() || !StringUtils.hasText(query)) {
                return new RelevantMemoryContext(entrypoint, List.of(), "");
            }
            // 选取相关记忆文件名
            List<String> selectedFilenames = selectRelevantFilenames(query, headers);
            if (selectedFilenames.isEmpty()) {
                return new RelevantMemoryContext(entrypoint, List.of(), "");
            }
            // 获取选中的记忆文件
            List<MemoryEntry> entries = memoryService.readEntries(repoRoot, userId, selectedFilenames);
            return new RelevantMemoryContext(entrypoint, entries, renderSelectedReminders(entries));
        } catch (Exception e) {
            log.warn("relevant_memory_prefetch_failed, error={}", e.getMessage());
            return RelevantMemoryContext.empty();
        }
    }

    private List<String> selectRelevantFilenames(String query, List<MemoryHeader> headers) {
        List<String> selected = selectViaModel(query, headers);
        if (!selected.isEmpty()) {
            return selected;
        }
        return selectHeuristically(query, headers);
    }

    private List<String> selectViaModel(String query, List<MemoryHeader> headers) {
        try {
            String response = streamingChatClientSupport.collect(
                    selectorChatClient,
                    """
                    你是一个长期记忆选择器。你会看到用户当前问题，以及一批可用记忆的文件名、类型、日期和一句话摘要。
                    只选择与当前问题最相关的记忆文件名，最多 5 条。

                    返回规则：
                    - 只返回文件名，每行一个，不能附加解释。
                    - 如果没有明显相关项，只返回 NONE。
                    - 优先选择稳定的跨会话信息：用户画像、明确反馈、正在进行的项目动态、外部参考指针。
                    - 不要因为“代码位置、最近改动、项目结构”相似就选中；这些信息应优先从当前仓库实时获取。
                    """,
                    """
                    用户问题:
                    %s

                    可用记忆:
                    %s
                    """.formatted(query.trim(), renderHeaders(headers))
            );
            return parseSelectedFilenames(response, headers);
        } catch (Exception e) {
            log.debug("relevant_memory_model_select_failed, error={}", e.getMessage());
            return List.of();
        }
    }

    private List<String> selectHeuristically(String query, List<MemoryHeader> headers) {
        // 把用户提问拆成一组去重后的关键词
        List<String> tokens = tokenize(query);
        record ScoredHeader(MemoryHeader header, double score) {
        }
        // todo 是否需要引入模型打分？
        List<ScoredHeader> scored = new ArrayList<>();
        for (MemoryHeader header : headers) {
            String haystack = (header.filename() + " " + header.name() + " " + header.description()).toLowerCase(Locale.ROOT);
            // 先用关键词做一次粗筛
            double score = typeHintScore(query, header.type());
            for (String token : tokens) {
                if (token.length() >= 2 && haystack.contains(token)) {
                    score += 2.0;
                }
            }
            if (score > 0) {
                // 做一个时间衰减
                score += header.mtimeMs() / 1_000_000_000_000.0;
                scored.add(new ScoredHeader(header, score));
            }
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredHeader::score).reversed())
                .limit(MAX_RELEVANT)
                .map(item -> item.header().filename())
                .toList();
    }

    private String renderHeaders(List<MemoryHeader> headers) {
        StringJoiner joiner = new StringJoiner("\n");
        for (MemoryHeader header : headers) {
            joiner.add("- [%s] %s: %s".formatted(header.type().wireValue(), header.filename(), header.description()));
        }
        return joiner.length() == 0 ? "- none" : joiner.toString();
    }

    private List<String> parseSelectedFilenames(String raw, List<MemoryHeader> headers) {
        if (!StringUtils.hasText(raw) || "NONE".equalsIgnoreCase(raw.trim())) {
            return List.of();
        }
        Set<String> allowed = headers.stream().map(MemoryHeader::filename).collect(java.util.stream.Collectors.toSet());
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        Matcher matcher = FILENAME_PATTERN.matcher(raw);
        while (matcher.find()) {
            String filename = matcher.group();
            if (allowed.contains(filename)) {
                selected.add(filename);
            }
            if (selected.size() >= MAX_RELEVANT) {
                break;
            }
        }
        return List.copyOf(selected);
    }

    private String renderSelectedReminders(List<MemoryEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return "";
        }
        StringJoiner joiner = new StringJoiner("\n\n");
        for (MemoryEntry entry : entries) {
            StringBuilder builder = new StringBuilder();
            builder.append("<system-reminder>\n");
            builder.append("长期记忆: [").append(entry.type().wireValue()).append("] ")
                    .append(entry.name()).append(" (").append(entry.filename()).append(")\n");
            builder.append(entry.description()).append("\n\n");
            builder.append(entry.body()).append("\n");
            String freshness = memoryService.freshnessText(entry.mtimeMs());
            if (StringUtils.hasText(freshness)) {
                builder.append("\n").append(freshness).append("\n");
            }
            builder.append("</system-reminder>");
            joiner.add(builder);
        }
        return joiner.toString();
    }

    private double typeHintScore(String query, MemoryType type) {
        String lower = query.toLowerCase(Locale.ROOT);
        return switch (type) {
            case USER -> containsAny(lower, "用户", "偏好", "背景", "熟悉", "不熟", "preference", "profile") ? 2.5 : 0;
            case FEEDBACK -> containsAny(lower, "不要", "必须", "规范", "规则", "feedback", "should", "must") ? 2.5 : 0;
            case PROJECT -> containsAny(lower, "项目", "截止", "里程碑", "协作", "进行中", "deadline", "milestone", "project") ? 2.5 : 0;
            case REFERENCE -> containsAny(lower, "在哪", "链接", "文档", "reference", "wiki", "linear", "grafana", "slack") ? 2.5 : 0;
        };
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private List<String> tokenize(String query) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        String[] pieces = query.toLowerCase(Locale.ROOT).split("[^\\p{IsAlphabetic}\\p{IsDigit}_]+");
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String piece : pieces) {
            if (!piece.isBlank()) {
                tokens.add(piece);
            }
        }
        return List.copyOf(tokens);
    }
}
