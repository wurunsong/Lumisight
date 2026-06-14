package com.lumisight.core.support;

import com.lumisight.core.model.AgentRequest;
import com.lumisight.core.support.memory.RelevantMemorySource;
import com.lumisight.core.support.memory.RelevantMemorySourceProvider;
import com.lumisight.memory.dto.MemoryEntry;
import com.lumisight.memory.dto.MemoryEntrypoint;
import com.lumisight.memory.dto.MemoryHeader;
import com.lumisight.memory.MemoryService;
import com.lumisight.memory.enums.MemoryType;
import com.lumisight.memory.dto.RelevantMemoryContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;

@Component
public class RelevantMemoryService {

    private static final Logger log = LoggerFactory.getLogger(RelevantMemoryService.class);
    private static final int MAX_RELEVANT = 5;

    private final ChatClient selectorChatClient;
    private final AgentPromptService agentPromptService;
    private final StreamingChatClientSupport streamingChatClientSupport;
    private final MemoryService memoryService;
    private final List<RelevantMemorySourceProvider> sourceProviders;

    public RelevantMemoryService(
            ChatClient.Builder chatClientBuilder,
            AgentPromptService agentPromptService,
            StreamingChatClientSupport streamingChatClientSupport,
            MemoryService memoryService,
            List<RelevantMemorySourceProvider> sourceProviders
    ) {
        this.selectorChatClient = chatClientBuilder.build();
        this.agentPromptService = agentPromptService;
        this.streamingChatClientSupport = streamingChatClientSupport;
        this.memoryService = memoryService;
        this.sourceProviders = sourceProviders == null ? List.of() : List.copyOf(sourceProviders);
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
            List<RelevantMemorySource> sources = resolveSources(repoRoot, userId, query);
            if (sources.isEmpty()) {
                return RelevantMemoryContext.empty();
            }
            // 获取全部记忆索引
            MemoryEntrypoint entrypoint = buildCombinedEntrypoint(sources, userId);
            // 获取记忆文件的头部
            List<SourceHeader> headers = loadSourceHeaders(sources, userId);
            if (headers.isEmpty() || !StringUtils.hasText(query)) {
                return new RelevantMemoryContext(entrypoint, List.of(), "");
            }
            List<String> selectedKeys = selectRelevantKeys(query, headers);
            if (selectedKeys.isEmpty()) {
                return new RelevantMemoryContext(entrypoint, List.of(), "");
            }
            List<MemoryEntry> entries = readSelectedEntries(userId, headers, selectedKeys);
            return new RelevantMemoryContext(entrypoint, entries, renderSelectedReminders(entries));
        } catch (Exception e) {
            log.warn("relevant_memory_prefetch_failed, error={}", e.getMessage());
            return RelevantMemoryContext.empty();
        }
    }

    private List<RelevantMemorySource> resolveSources(String repoRoot, String userId, String query) {
        List<RelevantMemorySource> sources = new ArrayList<>();
        for (RelevantMemorySourceProvider provider : sourceProviders) {
            List<RelevantMemorySource> resolved = provider.resolveSources(repoRoot, userId, query);
            if (resolved != null && !resolved.isEmpty()) {
                sources.addAll(resolved);
            }
        }
        return sources;
    }

    private MemoryEntrypoint buildCombinedEntrypoint(List<RelevantMemorySource> sources, String userId) {
        StringJoiner joiner = new StringJoiner("\n\n");
        joiner.add("# MEMORY");
        boolean hasContent = false;
        for (RelevantMemorySource source : sources) {
            MemoryEntrypoint entrypoint = memoryService.loadEntrypoint(source.storageRoot(), userId, source.memoryRootDir());
            String normalized = normalizeEntrypointContent(entrypoint.content());
            if (!StringUtils.hasText(normalized)) {
                continue;
            }
            joiner.add("## " + source.displayName());
            joiner.add(normalized);
            hasContent = true;
        }
        if (!hasContent) {
            return RelevantMemoryContext.empty().entrypoint();
        }
        return MemoryEntrypoint.empty(joiner.toString());
    }

    private List<SourceHeader> loadSourceHeaders(List<RelevantMemorySource> sources, String userId) {
        List<SourceHeader> headers = new ArrayList<>();
        for (RelevantMemorySource source : sources) {
            for (MemoryHeader header : memoryService.scanHeaders(source.storageRoot(), userId, source.memoryRootDir())) {
                headers.add(new SourceHeader(source, selectorKey(source, header.filename()), header));
            }
        }
        headers.sort(Comparator.comparingLong(SourceHeader::mtimeMs).reversed());
        return headers;
    }

    private List<String> selectRelevantKeys(String query, List<SourceHeader> headers) {
        // 用模型选择记忆
        List<String> selected = selectViaModel(query, headers);
        if (!selected.isEmpty()) {
            return selected;
        }
        // 用关键词匹配 + 时间衰退的方式选择记忆
        return selectHeuristically(query, headers);
    }

    private List<String> selectViaModel(String query, List<SourceHeader> headers) {
        try {
            String response = streamingChatClientSupport.collect(
                    selectorChatClient,
                    agentPromptService.relevantMemorySelectSystemPrompt(),
                    agentPromptService.relevantMemorySelectUserPrompt(query.trim(), renderHeaders(headers))
            );
            return parseSelectedKeys(response, headers);
        } catch (Exception e) {
            log.debug("relevant_memory_model_select_failed, error={}", e.getMessage());
            return List.of();
        }
    }

    private List<String> selectHeuristically(String query, List<SourceHeader> headers) {
        List<String> tokens = tokenize(query);
        record ScoredHeader(SourceHeader header, double score) {
        }
        List<ScoredHeader> scored = new ArrayList<>();
        for (SourceHeader header : headers) {
            MemoryHeader memoryHeader = header.header();
            String haystack = (
                    header.source().displayName() + " "
                            + memoryHeader.filename() + " "
                            + memoryHeader.name() + " "
                            + memoryHeader.description()
            ).toLowerCase(Locale.ROOT);
            double score = typeHintScore(query, memoryHeader.type());
            for (String token : tokens) {
                if (token.length() >= 2 && haystack.contains(token)) {
                    score += 2.0;
                }
            }
            if (score > 0) {
                score += memoryHeader.mtimeMs() / 1_000_000_000_000.0;
                scored.add(new ScoredHeader(header, score));
            }
        }
        return scored.stream()
                .sorted(Comparator.comparingDouble(ScoredHeader::score).reversed())
                .limit(MAX_RELEVANT)
                .map(item -> item.header().selectorKey())
                .toList();
    }

    private String renderHeaders(List<SourceHeader> headers) {
        StringJoiner joiner = new StringJoiner("\n");
        for (SourceHeader header : headers) {
            joiner.add("- [%s] (%s) %s / %s: %s".formatted(
                    header.selectorKey(),
                    header.source().displayName(),
                    header.header().type().wireValue(),
                    header.header().filename(),
                    header.header().description()
            ));
        }
        return joiner.length() == 0 ? "- none" : joiner.toString();
    }

    private List<String> parseSelectedKeys(String raw, List<SourceHeader> headers) {
        if (!StringUtils.hasText(raw) || "NONE".equalsIgnoreCase(raw.trim())) {
            return List.of();
        }
        Set<String> allowed = headers.stream().map(SourceHeader::selectorKey).collect(java.util.stream.Collectors.toSet());
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (String line : raw.split("\\R")) {
            String candidate = line.trim();
            if (allowed.contains(candidate)) {
                selected.add(candidate);
                if (selected.size() >= MAX_RELEVANT) {
                    break;
                }
            }
        }
        return List.copyOf(selected);
    }

    private List<MemoryEntry> readSelectedEntries(String userId, List<SourceHeader> headers, List<String> selectedKeys) {
        Map<String, SourceHeader> headerByKey = new LinkedHashMap<>();
        for (SourceHeader header : headers) {
            headerByKey.put(header.selectorKey(), header);
        }

        Map<String, Map<String, MemoryEntry>> entriesByKey = new LinkedHashMap<>();
        Map<RelevantMemorySource, List<String>> filenamesBySource = new LinkedHashMap<>();
        // 先按来源分桶，避免项目记忆和用户画像记忆混在一起读，也避免同名文件跨来源冲突。
        for (String selectedKey : selectedKeys) {
            SourceHeader header = headerByKey.get(selectedKey);
            if (header == null) {
                continue;
            }
            filenamesBySource.computeIfAbsent(header.source(), ignored -> new ArrayList<>()).add(header.header().filename());
        }

        for (Map.Entry<RelevantMemorySource, List<String>> entry : filenamesBySource.entrySet()) {
            RelevantMemorySource source = entry.getKey();
            List<MemoryEntry> sourceEntries = memoryService.readEntries(source.storageRoot(), userId, source.memoryRootDir(), entry.getValue());
            Map<String, MemoryEntry> byFilename = new LinkedHashMap<>();
            for (MemoryEntry sourceEntry : sourceEntries) {
                byFilename.put(sourceEntry.filename(), sourceEntry);
            }
            entriesByKey.put(source.sourceId(), byFilename);
        }

        List<MemoryEntry> selectedEntries = new ArrayList<>();
        // 最后再按 selectedKeys 的原始顺序回放，保证模型选中的优先级不会被批量读取过程打乱。
        for (String selectedKey : selectedKeys) {
            SourceHeader header = headerByKey.get(selectedKey);
            if (header == null) {
                continue;
            }
            MemoryEntry entry = entriesByKey.getOrDefault(header.source().sourceId(), Map.of()).get(header.header().filename());
            if (entry != null) {
                selectedEntries.add(entry);
            }
        }
        return selectedEntries;
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

    private String selectorKey(RelevantMemorySource source, String filename) {
        return source.sourceId() + "__" + filename;
    }

    private String normalizeEntrypointContent(String content) {
        if (!StringUtils.hasText(content)) {
            return "";
        }
        String trimmed = content.trim();
        if (trimmed.startsWith("# MEMORY")) {
            trimmed = trimmed.substring("# MEMORY".length()).trim();
        }
        return trimmed;
    }

    private record SourceHeader(
            RelevantMemorySource source,
            String selectorKey,
            MemoryHeader header
    ) {
        private long mtimeMs() {
            return header.mtimeMs();
        }
    }
}
