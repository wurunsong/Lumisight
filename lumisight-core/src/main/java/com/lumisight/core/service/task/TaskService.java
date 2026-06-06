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
import java.util.HashSet;
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
        lock.lock();
        try {
            Path dir = ensureTaskDir(repoRoot);
            Map<String, TaskRecord> tasks = loadTaskMap(dir);
            String taskId = nextTaskId();
            validateCreateRequest(request, taskId, tasks);
            long now = System.currentTimeMillis();
            TaskRecord created = TaskRecord.create(
                    taskId,
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

    public TaskReleaseResult release(String repoRoot, String taskId, String owner) {
        requireTaskId(taskId);
        String normalizedOwner = normalizeOwner(owner);
        lock.lock();
        try {
            Path dir = ensureTaskDir(repoRoot);
            Map<String, TaskRecord> tasks = loadTaskMap(dir);
            TaskRecord task = requireTask(tasks, taskId.trim());
            TaskView currentView = toView(task, tasks);
            if (task.status() != TaskStatus.IN_PROGRESS) {
                return new TaskReleaseResult(false, "Task %s is %s, cannot release".formatted(task.id(), task.status().wireValue()), currentView);
            }
            if (task.owner() != null && !task.owner().equals(normalizedOwner) && !"agent".equals(normalizedOwner)) {
                return new TaskReleaseResult(false, "Task %s is owned by %s, cannot release as %s".formatted(task.id(), task.owner(), normalizedOwner), currentView);
            }
            TaskRecord released = task.withStatus(TaskStatus.PENDING, null, System.currentTimeMillis());
            saveTask(dir, released);
            Map<String, TaskRecord> updated = new LinkedHashMap<>(tasks);
            updated.put(released.id(), released);
            return new TaskReleaseResult(true, "Released %s (%s) back to pending".formatted(released.id(), released.subject()), toView(released, updated));
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
                Map<String, TaskRecord> current = new LinkedHashMap<>(tasks);
                List<TaskView> readyTasks = readyPendingTasks(current);
                return new TaskCompleteResult(
                        "Task %s (%s) is already completed".formatted(task.id(), task.subject()),
                        task,
                        List.of(),
                        readyTasks,
                        allCompleted(current)
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
            List<TaskView> readyTasks = readyPendingTasks(updated);
            String message = "Completed %s (%s)".formatted(completed.id(), completed.subject());
            if (!unblocked.isEmpty()) {
                message += "\nUnblocked: " + unblocked.stream().map(view -> view.task().subject()).toList();
            }
            if (readyTasks.isEmpty() && allCompleted(updated)) {
                message += "\nAll tasks completed.";
            } else if (!readyTasks.isEmpty()) {
                message += "\nReady next: " + readyTasks.stream().map(view -> view.task().id()).toList();
            }
            return new TaskCompleteResult(message, completed, unblocked, readyTasks, allCompleted(updated));
        } finally {
            lock.unlock();
        }
    }

    public TaskResumeResult resume(String repoRoot, String owner, boolean autoClaim, int limit) {
        String normalizedOwner = normalizeOwner(owner);
        int cappedLimit = Math.max(1, Math.min(properties.getMaxListLimit(), limit));
        lock.lock();
        try {
            Path dir = ensureTaskDir(repoRoot);
            Map<String, TaskRecord> tasks = loadTaskMap(dir);
            List<TaskView> activeTasks = tasks.values().stream()
                    .filter(task -> task.status() == TaskStatus.IN_PROGRESS)
                    .filter(task -> task.owner() == null || normalizedOwner.equals(task.owner()))
                    .map(task -> toView(task, tasks))
                    .sorted(Comparator.comparing(view -> view.task().createdAt()))
                    .limit(cappedLimit)
                    .toList();
            if (!activeTasks.isEmpty()) {
                return new TaskResumeResult(
                        "Found %d in-progress task(s) for %s".formatted(activeTasks.size(), normalizedOwner),
                        false,
                        activeTasks,
                        List.of(),
                        allCompleted(tasks)
                );
            }
            List<TaskView> readyTasks = readyPendingTasks(tasks).stream().limit(cappedLimit).toList();
            if (readyTasks.isEmpty()) {
                String message = allCompleted(tasks)
                        ? "All tasks are completed"
                        : "No runnable pending tasks right now";
                return new TaskResumeResult(message, false, List.of(), List.of(), allCompleted(tasks));
            }
            if (!autoClaim) {
                return new TaskResumeResult(
                        "Found %d runnable pending task(s)".formatted(readyTasks.size()),
                        false,
                        List.of(),
                        readyTasks,
                        false
                );
            }
            List<TaskView> claimed = new ArrayList<>();
            Map<String, TaskRecord> updated = new LinkedHashMap<>(tasks);
            long now = System.currentTimeMillis();
            for (TaskView readyTask : readyTasks) {
                TaskRecord claimedTask = readyTask.task().withStatus(TaskStatus.IN_PROGRESS, normalizedOwner, now);
                saveTask(dir, claimedTask);
                updated.put(claimedTask.id(), claimedTask);
                claimed.add(toView(claimedTask, updated));
            }
            return new TaskResumeResult(
                    "Auto-claimed %d runnable task(s) for %s".formatted(claimed.size(), normalizedOwner),
                    true,
                    claimed,
                    List.of(),
                    false
            );
        } finally {
            lock.unlock();
        }
    }

    public TaskBoardView board(String repoRoot) {
        lock.lock();
        try {
            Map<String, TaskRecord> tasks = loadTaskMap(ensureTaskDir(repoRoot));
            List<TaskView> views = tasks.values().stream()
                    .map(task -> toView(task, tasks))
                    .sorted(Comparator.comparing(view -> view.task().createdAt()))
                    .toList();
            List<TaskView> readyTasks = views.stream()
                    .filter(view -> view.task().status() == TaskStatus.PENDING)
                    .filter(TaskView::canStart)
                    .toList();
            List<TaskView> blockedTasks = views.stream()
                    .filter(view -> view.task().status() == TaskStatus.PENDING)
                    .filter(view -> !view.canStart())
                    .toList();
            List<TaskView> inProgressTasks = views.stream()
                    .filter(view -> view.task().status() == TaskStatus.IN_PROGRESS)
                    .toList();
            List<TaskView> completedTasks = views.stream()
                    .filter(view -> view.task().status() == TaskStatus.COMPLETED)
                    .toList();
            return new TaskBoardView(
                    views.size(),
                    readyTasks.size() + blockedTasks.size(),
                    inProgressTasks.size(),
                    completedTasks.size(),
                    readyTasks.size(),
                    blockedTasks.size(),
                    allCompleted(tasks),
                    readyTasks,
                    inProgressTasks,
                    blockedTasks,
                    completedTasks
            );
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

    private void validateCreateRequest(TaskCreateRequest request, String taskId, Map<String, TaskRecord> tasks) {
        if (request == null) {
            throw new IllegalArgumentException("task request is required");
        }
        if (!StringUtils.hasText(request.subject())) {
            throw new IllegalArgumentException("subject 是必填参数");
        }
        List<String> dependencies = request.blockedBy() == null ? List.of() : request.blockedBy().stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        for (String dependencyId : dependencies) {
            if (!tasks.containsKey(dependencyId)) {
                throw new IllegalArgumentException("blockedBy 依赖不存在: " + dependencyId);
            }
            if (dependencyId.equals(taskId)) {
                throw new IllegalArgumentException("任务不能依赖自己: " + taskId);
            }
            if (hasPath(tasks, dependencyId, taskId)) {
                throw new IllegalArgumentException("blockedBy 会形成依赖环: " + dependencyId + " -> " + taskId);
            }
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

    private List<TaskView> readyPendingTasks(Map<String, TaskRecord> tasks) {
        return tasks.values().stream()
                .filter(task -> task.status() == TaskStatus.PENDING)
                .map(task -> toView(task, tasks))
                .filter(TaskView::canStart)
                .sorted(Comparator.comparing(view -> view.task().createdAt()))
                .toList();
    }

    private boolean allCompleted(Map<String, TaskRecord> tasks) {
        return !tasks.isEmpty() && tasks.values().stream().allMatch(task -> task.status() == TaskStatus.COMPLETED);
    }

    private boolean hasPath(Map<String, TaskRecord> tasks, String startId, String targetId) {
        return hasPath(tasks, startId, targetId, new HashSet<>());
    }

    private boolean hasPath(Map<String, TaskRecord> tasks, String currentId, String targetId, HashSet<String> visited) {
        if (!visited.add(currentId)) {
            return false;
        }
        if (currentId.equals(targetId)) {
            return true;
        }
        TaskRecord current = tasks.get(currentId);
        if (current == null || current.blocks().isEmpty()) {
            return false;
        }
        for (String nextId : current.blocks()) {
            if (hasPath(tasks, nextId, targetId, visited)) {
                return true;
            }
        }
        return false;
    }
}
