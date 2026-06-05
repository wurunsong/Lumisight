package com.lumisight.core.service.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(java.time.ZoneOffset.UTC);

    private final TaskProperties properties;
    private final ObjectMapper objectMapper;
    private final ReentrantLock lock = new ReentrantLock();

    public TaskService(TaskProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public TaskRecord create(String repoRoot, TaskCreateRequest request) {
        validateCreateRequest(request);
        lock.lock();
        try {
            Path dir = ensureTaskDir(repoRoot);
            long now = System.currentTimeMillis();
            TaskRecord created = TaskRecord.create(
                    nextTaskId(),
                    request.subject(),
                    request.description(),
                    request.blockedBy(),
                    request.metadata(),
                    now
            );
            saveTask(dir, created);
            synchronizeBlocks(dir);
            return getInternal(dir, created.id()).task();
        } finally {
            lock.unlock();
        }
    }

    public List<TaskView> list(String repoRoot) {
        lock.lock();
        try {
            return listInternal(ensureTaskDir(repoRoot));
        } finally {
            lock.unlock();
        }
    }

    public TaskView get(String repoRoot, String taskId) {
        requireTaskId(taskId);
        lock.lock();
        try {
            return getInternal(ensureTaskDir(repoRoot), taskId.trim());
        } finally {
            lock.unlock();
        }
    }

    public TaskClaimResult claim(String repoRoot, String taskId, String owner) {
        requireTaskId(taskId);
        String normalizedOwner = normalizeOwner(owner);
        lock.lock();
        try {
            Path dir = ensureTaskDir(repoRoot);
            Map<String, TaskRecord> tasks = loadTaskMap(dir);
            TaskRecord task = requireTask(tasks, taskId.trim());
            TaskView currentView = toView(task, tasks);
            if (task.status() != TaskStatus.PENDING) {
                return new TaskClaimResult(false, "Task %s is %s, cannot claim".formatted(task.id(), task.status().wireValue()), currentView);
            }
            if (!currentView.canStart()) {
                return new TaskClaimResult(false, "Blocked by: " + currentView.blockingDependencies(), currentView);
            }
            TaskRecord claimed = task.withStatus(TaskStatus.IN_PROGRESS, normalizedOwner, System.currentTimeMillis());
            saveTask(dir, claimed);
            Map<String, TaskRecord> updated = new LinkedHashMap<>(tasks);
            updated.put(claimed.id(), claimed);
            TaskView claimedView = toView(claimed, updated);
            return new TaskClaimResult(true, "Claimed %s (%s)".formatted(claimed.id(), claimed.subject()), claimedView);
        } finally {
            lock.unlock();
        }
    }

    public TaskCompleteResult complete(String repoRoot, String taskId) {
        requireTaskId(taskId);
        lock.lock();
        try {
            Path dir = ensureTaskDir(repoRoot);
            Map<String, TaskRecord> tasks = loadTaskMap(dir);
            TaskRecord task = requireTask(tasks, taskId.trim());
            if (task.status() == TaskStatus.COMPLETED) {
                return new TaskCompleteResult(
                        "Task %s (%s) is already completed".formatted(task.id(), task.subject()),
                        task,
                        List.of()
                );
            }
            TaskRecord completed = task.withStatus(TaskStatus.COMPLETED, task.owner(), System.currentTimeMillis());
            saveTask(dir, completed);
            Map<String, TaskRecord> updated = new LinkedHashMap<>(tasks);
            updated.put(completed.id(), completed);
            List<TaskView> unblocked = updated.values().stream()
                    .filter(candidate -> candidate.status() == TaskStatus.PENDING)
                    .filter(candidate -> candidate.blockedBy().contains(completed.id()))
                    .map(candidate -> toView(candidate, updated))
                    .filter(TaskView::canStart)
                    .sorted(Comparator.comparing(view -> view.task().createdAt()))
                    .toList();
            String message = "Completed %s (%s)".formatted(completed.id(), completed.subject());
            if (!unblocked.isEmpty()) {
                message += "\nUnblocked: " + unblocked.stream().map(view -> view.task().subject()).toList();
            }
            return new TaskCompleteResult(message, completed, unblocked);
        } finally {
            lock.unlock();
        }
    }

    private List<TaskView> listInternal(Path dir) {
        Map<String, TaskRecord> tasks = loadTaskMap(dir);
        return tasks.values().stream()
                .map(task -> toView(task, tasks))
                .sorted(Comparator.comparing((TaskView view) -> view.task().createdAt()))
                .toList();
    }

    private TaskView getInternal(Path dir, String taskId) {
        Map<String, TaskRecord> tasks = loadTaskMap(dir);
        return toView(requireTask(tasks, taskId), tasks);
    }

    private TaskView toView(TaskRecord task, Map<String, TaskRecord> tasks) {
        List<String> blockingDependencies = unresolvedDependencies(task, tasks);
        return new TaskView(task, blockingDependencies.isEmpty(), blockingDependencies);
    }

    private List<String> unresolvedDependencies(TaskRecord task, Map<String, TaskRecord> tasks) {
        if (task == null || task.blockedBy() == null || task.blockedBy().isEmpty()) {
            return List.of();
        }
        List<String> unresolved = new ArrayList<>();
        for (String dependencyId : task.blockedBy()) {
            TaskRecord dependency = tasks.get(dependencyId);
            if (dependency == null || dependency.status() != TaskStatus.COMPLETED) {
                unresolved.add(dependencyId);
            }
        }
        return List.copyOf(unresolved);
    }

    private void synchronizeBlocks(Path dir) {
        Map<String, TaskRecord> tasks = loadTaskMap(dir);
        Map<String, List<String>> blocksByTask = new LinkedHashMap<>();
        for (TaskRecord task : tasks.values()) {
            blocksByTask.put(task.id(), new ArrayList<>());
        }
        for (TaskRecord task : tasks.values()) {
            for (String dependencyId : task.blockedBy()) {
                blocksByTask.computeIfAbsent(dependencyId, key -> new ArrayList<>()).add(task.id());
            }
        }
        for (TaskRecord task : tasks.values()) {
            List<String> nextBlocks = blocksByTask.getOrDefault(task.id(), List.of()).stream()
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            if (!nextBlocks.equals(task.blocks())) {
                saveTask(dir, task.withBlocks(nextBlocks, System.currentTimeMillis()));
            }
        }
    }

    private Map<String, TaskRecord> loadTaskMap(Path dir) {
        List<TaskRecord> tasks = loadTasks(dir);
        Map<String, TaskRecord> map = new LinkedHashMap<>();
        for (TaskRecord task : tasks) {
            map.put(task.id(), task);
        }
        return map;
    }

    private List<TaskRecord> loadTasks(Path dir) {
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .map(this::readTask)
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingLong(TaskRecord::createdAt))
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("failed to load tasks", e);
        }
    }

    private TaskRecord readTask(Path file) {
        try {
            return objectMapper.readValue(Files.readString(file, StandardCharsets.UTF_8), TaskRecord.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to read task file: " + file, e);
        }
    }

    private void saveTask(Path dir, TaskRecord task) {
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(task.id() + ".json");
            Files.writeString(file, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(task), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("failed to save task " + task.id(), e);
        }
    }

    private Path ensureTaskDir(String repoRoot) {
        try {
            Path root = StringUtils.hasText(repoRoot)
                    ? Path.of(repoRoot).toAbsolutePath().normalize()
                    : Path.of("").toAbsolutePath().normalize();
            Path taskDir = root.resolve(properties.getRootDir()).normalize();
            Files.createDirectories(taskDir);
            return taskDir;
        } catch (IOException e) {
            throw new IllegalStateException("failed to prepare task dir", e);
        }
    }

    private TaskRecord requireTask(Map<String, TaskRecord> tasks, String taskId) {
        TaskRecord task = tasks.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("task not found: " + taskId);
        }
        return task;
    }

    private void validateCreateRequest(TaskCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("task request is required");
        }
        if (!StringUtils.hasText(request.subject())) {
            throw new IllegalArgumentException("subject 是必填参数");
        }
    }

    private void requireTaskId(String taskId) {
        if (!StringUtils.hasText(taskId)) {
            throw new IllegalArgumentException("taskId 是必填参数");
        }
    }

    private String normalizeOwner(String owner) {
        return StringUtils.hasText(owner) ? owner.trim() : "agent";
    }

    private String nextTaskId() {
        byte[] bytes = new byte[4];
        RANDOM.nextBytes(bytes);
        return "task_" + TIMESTAMP.format(Instant.now()) + "_" + HexFormat.of().formatHex(bytes);
    }
}
