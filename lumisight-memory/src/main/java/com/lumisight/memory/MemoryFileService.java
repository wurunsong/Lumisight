package com.lumisight.memory;

import static com.lumisight.memory.MemoryConstants.ENTRYPOINT_FILENAME;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

import com.lumisight.memory.dto.MemoryEntry;
import com.lumisight.memory.dto.MemoryEntrypoint;
import com.lumisight.memory.dto.MemoryHeader;
import com.lumisight.memory.dto.MemoryWriteRequest;
import com.lumisight.memory.enums.MemoryType;
import com.lumisight.memory.properties.MemoryProperties;

@Service
public class MemoryFileService {

    private static final Logger log = LoggerFactory.getLogger(MemoryFileService.class);
    private static final Set<String> MEMORY_FILENAME_PREFIXES = Stream.of(MemoryType.values())
            .map(MemoryType::wireValue)
            .map(prefix -> prefix + "_")
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final MemoryProperties properties;
    private final ConcurrentMap<String, Object> directoryLocks = new ConcurrentHashMap<>();

    public MemoryFileService(MemoryProperties properties) {
        this.properties = properties;
    }

    public MemoryEntry write(String repoRoot, String userId, MemoryWriteRequest request) {
        return write(repoRoot, userId, properties.getRootDir(), request);
    }

    public MemoryEntry write(String storageRoot, String userId, String memoryRootDir, MemoryWriteRequest request) {
        validateWriteRequest(request);
        synchronized (lockFor(storageRoot, userId, memoryRootDir)) {
            try {
                Path dir = ensureMemoryDir(storageRoot, userId, memoryRootDir);
                Path file = allocateFile(dir, request.type(), request.name());
                String raw = MemoryFrontmatterParser.render(request);
                Files.writeString(file, raw, StandardCharsets.UTF_8);
                rebuildEntrypoint(storageRoot, userId, memoryRootDir);
                return readEntry(file);
            } catch (IOException e) {
                throw new IllegalStateException("failed to write memory", e);
            }
        }
    }

    public List<MemoryHeader> scanHeaders(String repoRoot, String userId) {
        return scanHeaders(repoRoot, userId, properties.getRootDir());
    }

    public List<MemoryHeader> scanHeaders(String storageRoot, String userId, String memoryRootDir) {
        synchronized (lockFor(storageRoot, userId, memoryRootDir)) {
            try {
                Path dir = ensureMemoryDir(storageRoot, userId, memoryRootDir);
                List<Path> files = listMemoryFiles(dir);
                List<MemoryHeader> headers = new ArrayList<>();
                for (Path file : files) {
                    try {
                        headers.add(readHeader(file));
                    } catch (Exception e) {
                        log.warn("memory_header_scan_failed, file={}, error={}", file, e.getMessage());
                    }
                }
                headers.sort(Comparator.comparingLong(MemoryHeader::mtimeMs).reversed());
                int max = Math.max(1, properties.getMaxScannedFiles());
                return headers.size() <= max ? headers : headers.subList(0, max);
            } catch (IOException e) {
                throw new IllegalStateException("failed to scan memory headers", e);
            }
        }
    }

    public List<MemoryEntry> readEntries(String repoRoot, String userId, List<String> filenames) {
        return readEntries(repoRoot, userId, properties.getRootDir(), filenames);
    }

    public List<MemoryEntry> readEntries(String storageRoot, String userId, String memoryRootDir, List<String> filenames) {
        if (filenames == null || filenames.isEmpty()) {
            return List.of();
        }
        synchronized (lockFor(storageRoot, userId, memoryRootDir)) {
            Path dir = ensureMemoryDirUnchecked(storageRoot, userId, memoryRootDir);
            List<MemoryEntry> entries = new ArrayList<>();
            for (String filename : filenames) {
                if (!StringUtils.hasText(filename) || filename.contains("/") || filename.contains("..")) {
                    continue;
                }
                Path file = dir.resolve(filename).normalize();
                if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
                    continue;
                }
                try {
                    entries.add(readEntry(file));
                } catch (Exception e) {
                    log.warn("memory_read_failed, file={}, error={}", file, e.getMessage());
                }
            }
            entries.sort(Comparator.comparingLong(MemoryEntry::mtimeMs).reversed());
            return entries;
        }
    }

    public List<MemoryHeader> list(String repoRoot, String userId) {
        return scanHeaders(repoRoot, userId);
    }

    public boolean delete(String repoRoot, String userId, String filename) {
        if (!StringUtils.hasText(filename) || filename.contains("/") || filename.contains("..")) {
            return false;
        }
        synchronized (lockFor(repoRoot, userId, properties.getRootDir())) {
            Path dir = ensureMemoryDirUnchecked(repoRoot, userId);
            Path file = dir.resolve(filename).normalize();
            if (!file.startsWith(dir) || !Files.exists(file)) {
                return false;
            }
            try {
                Files.delete(file);
                rebuildEntrypoint(repoRoot, userId);
                return true;
            } catch (IOException e) {
                throw new IllegalStateException("failed to delete memory", e);
            }
        }
    }

    public MemoryEntrypoint loadEntrypoint(String repoRoot, String userId) {
        return loadEntrypoint(repoRoot, userId, properties.getRootDir());
    }

    public MemoryEntrypoint loadEntrypoint(String storageRoot, String userId, String memoryRootDir) {
        synchronized (lockFor(storageRoot, userId, memoryRootDir)) {
            try {
                Path dir = ensureMemoryDir(storageRoot, userId, memoryRootDir);
                Path entrypointFile = dir.resolve(ENTRYPOINT_FILENAME);
                if (!Files.exists(entrypointFile)) {
                    return rebuildEntrypoint(storageRoot, userId, memoryRootDir);
                }
                String content = Files.readString(entrypointFile, StandardCharsets.UTF_8);
                return truncateEntrypoint(content);
            } catch (IOException e) {
                throw new IllegalStateException("failed to load MEMORY.md", e);
            }
        }
    }

    /**
     * 根据当前的所有记忆文件，重新生成一份记忆索引MEMORY.md
     * @param repoRoot
     * @param userId
     * @return
     */
    public MemoryEntrypoint rebuildEntrypoint(String repoRoot, String userId) {
        return rebuildEntrypoint(repoRoot, userId, properties.getRootDir());
    }

    public MemoryEntrypoint rebuildEntrypoint(String storageRoot, String userId, String memoryRootDir) {
        synchronized (lockFor(storageRoot, userId, memoryRootDir)) {
            try {
                Path dir = ensureMemoryDir(storageRoot, userId, memoryRootDir);
                List<MemoryHeader> headers = scanHeaders(storageRoot, userId, memoryRootDir);
                String raw = renderEntrypoint(headers);
                MemoryEntrypoint entrypoint = truncateEntrypoint(raw);
                Files.writeString(dir.resolve(ENTRYPOINT_FILENAME), entrypoint.content(), StandardCharsets.UTF_8);
                return entrypoint;
            } catch (IOException e) {
                throw new IllegalStateException("failed to rebuild MEMORY.md", e);
            }
        }
    }

    public String renderSelectorManifest(String repoRoot, String userId) {
        return MemorySelectorManifest.render(scanHeaders(repoRoot, userId));
    }

    public String freshnessText(long mtimeMs) {
        long ageDays = Math.max(0, Duration.between(Instant.ofEpochMilli(mtimeMs), Instant.now()).toDays());
        if (ageDays <= properties.getStaleAfterDays()) {
            return "";
        }
        return "这条记忆已经有 " + ageDays + " 天了。记忆是某个时间点的观察，不是实时状态；其中关于代码行为或 file:line 引用的断言可能已经过时，引用前请先对照当前代码验证。";
    }

    private void validateWriteRequest(MemoryWriteRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("memory request is required");
        }
        if (!StringUtils.hasText(request.name())) {
            throw new IllegalArgumentException("memory name is required");
        }
        if (!StringUtils.hasText(request.description())) {
            throw new IllegalArgumentException("memory description is required");
        }
        if (request.type() == null) {
            throw new IllegalArgumentException("memory type is required");
        }
        if (!StringUtils.hasText(request.body())) {
            throw new IllegalArgumentException("memory body is required");
        }
    }

    private Path allocateFile(Path dir, MemoryType type, String name) throws IOException {
        String slug = slugify(name);
        String prefix = type.wireValue() + "_" + slug;
        Path file = dir.resolve(prefix + ".md");
        int index = 2;
        while (Files.exists(file)) {
            file = dir.resolve(prefix + "_" + index + ".md");
            index++;
        }
        return file;
    }

    private String renderEntrypoint(List<MemoryHeader> headers) {
        StringJoiner joiner = new StringJoiner("\n");
        joiner.add("# MEMORY");
        joiner.add("");
        if (headers == null || headers.isEmpty()) {
            joiner.add("- 暂无长期记忆");
            return joiner.toString();
        }
        headers.stream()
                .sorted(Comparator.comparingLong(MemoryHeader::mtimeMs).reversed())
                .forEach(header -> joiner.add("- [%s](%s) — %s".formatted(
                        header.name(),
                        header.filename(),
                        header.description()
                )));
        return joiner.toString();
    }

    private MemoryEntrypoint truncateEntrypoint(String raw) {
        String content = raw == null ? "" : raw;
        // 行数+字节双截断
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String[] lines = content.split("\\R", -1);
        boolean lineTruncated = lines.length > properties.getMaxEntrypointLines();
        boolean byteTruncated = bytes.length > properties.getMaxEntrypointBytes();
        if (!lineTruncated && !byteTruncated) {
            return new MemoryEntrypoint(content, false, false, lines.length, bytes.length);
        }

        StringBuilder builder = new StringBuilder();
        int maxLines = Math.max(1, properties.getMaxEntrypointLines());
        int maxBytes = Math.max(256, properties.getMaxEntrypointBytes());
        int currentBytes = 0;
        int keptLines = 0;
        for (String line : lines) {
            String candidate = (keptLines == 0 ? "" : "\n") + line;
            int candidateBytes = candidate.getBytes(StandardCharsets.UTF_8).length;
            if (keptLines >= maxLines || currentBytes + candidateBytes > maxBytes) {
                break;
            }
            builder.append(candidate);
            currentBytes += candidateBytes;
            keptLines++;
        }
        builder.append("\n\n> WARNING: MEMORY.md 太大了，已按行数/字节上限截断；请删除或合并低价值记忆。");
        return new MemoryEntrypoint(builder.toString(), lineTruncated, byteTruncated, lines.length, bytes.length);
    }

    private List<Path> listMemoryFiles(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(this::isStructuredMemoryFile)
                    .sorted(Comparator.comparing(this::lastModifiedMillis).reversed())
                    .toList();
        }
    }

    private boolean isStructuredMemoryFile(Path path) {
        String filename = path.getFileName().toString();
        if (Objects.equals(filename, ENTRYPOINT_FILENAME) || !filename.endsWith(".md")) {
            return false;
        }
        return MEMORY_FILENAME_PREFIXES.stream().anyMatch(filename::startsWith);
    }

    private MemoryHeader readHeader(Path file) throws IOException {
        String content = readHead(file, properties.getHeaderScanLines());
        MemoryFrontmatterParser.ParsedMemoryDocument parsed = MemoryFrontmatterParser.parse(content);
        return new MemoryHeader(
                file.getFileName().toString(),
                parsed.name(),
                parsed.description(),
                parsed.type(),
                lastModifiedMillis(file)
        );
    }

    private MemoryEntry readEntry(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        MemoryFrontmatterParser.ParsedMemoryDocument parsed = MemoryFrontmatterParser.parse(content);
        return new MemoryEntry(
                file.getFileName().toString(),
                parsed.name(),
                parsed.description(),
                parsed.type(),
                parsed.body(),
                lastModifiedMillis(file)
        );
    }

    private String readHead(Path file, int maxLines) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int end = Math.min(lines.size(), Math.max(1, maxLines));
        return String.join("\n", lines.subList(0, end));
    }

    private long lastModifiedMillis(Path path) {
        try {
            FileTime time = Files.getLastModifiedTime(path);
            return time.toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private Path ensureMemoryDir(String repoRoot, String userId) throws IOException {
        return ensureMemoryDir(repoRoot, userId, properties.getRootDir());
    }

    private Path ensureMemoryDir(String storageRoot, String userId, String memoryRootDir) throws IOException {
        Path dir = ensureMemoryDirUnchecked(storageRoot, userId, memoryRootDir);
        Files.createDirectories(dir);
        return dir;
    }

    private Path ensureMemoryDirUnchecked(String repoRoot, String userId) {
        return ensureMemoryDirUnchecked(repoRoot, userId, properties.getRootDir());
    }

    private Path ensureMemoryDirUnchecked(String storageRoot, String userId, String memoryRootDir) {
        String safeUserId = StringUtils.hasText(userId) ? userId.trim() : "default-user";
        Path root = StringUtils.hasText(storageRoot)
                ? Path.of(storageRoot)
                : Path.of(".").toAbsolutePath().normalize();
        return root
                .resolve(memoryRootDir)
                .resolve(safeUserId)
                .normalize();
    }

    private Object lockFor(String storageRoot, String userId, String memoryRootDir) {
        String key = ensureMemoryDirUnchecked(storageRoot, userId, memoryRootDir)
                .toAbsolutePath()
                .normalize()
                .toString();
        return directoryLocks.computeIfAbsent(key, ignored -> new Object());
    }

    private String slugify(String value) {
        String normalized = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
        String slug = normalized.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return slug.isBlank() ? "memory" : slug;
    }
}
