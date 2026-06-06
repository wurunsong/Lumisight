package com.lumisight.api.reflection.support;

import com.lumisight.api.agent.dto.response.AgentCronJobResponse;
import com.lumisight.api.agent.dto.response.AgentCronRunResponse;
import com.lumisight.api.agent.support.AgentCronJobService;
import com.lumisight.api.memory.dto.MemoryEntryResponse;
import com.lumisight.api.memory.dto.MemoryHeaderResponse;
import com.lumisight.api.reflection.dto.OfflineReflectionCronRunResponse;
import com.lumisight.api.reflection.dto.OfflineReflectionResponse;
import com.lumisight.api.reflection.dto.OfflineReflectionRunRequest;
import com.lumisight.api.reflection.dto.OfflineReflectionTaskBoardResponse;
import com.lumisight.api.reflection.dto.OfflineReflectionTaskResponse;
import com.lumisight.core.service.task.TaskBoardView;
import com.lumisight.core.service.task.TaskCreateRequest;
import com.lumisight.core.service.task.TaskRecord;
import com.lumisight.core.service.task.TaskService;
import com.lumisight.core.service.task.TaskView;
import com.lumisight.memory.MemoryEntry;
import com.lumisight.memory.MemoryService;
import com.lumisight.memory.MemoryType;
import com.lumisight.memory.MemoryWriteRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class OfflineReflectionService {

    private static final DateTimeFormatter TITLE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final TaskService taskService;
    private final MemoryService memoryService;
    private final AgentCronJobService cronJobService;

    public OfflineReflectionService(
            TaskService taskService,
            MemoryService memoryService,
            AgentCronJobService cronJobService
    ) {
        this.taskService = taskService;
        this.memoryService = memoryService;
        this.cronJobService = cronJobService;
    }

    public OfflineReflectionResponse run(String repoRoot, String userId, OfflineReflectionRunRequest request) {
        OfflineReflectionRunRequest safeRequest = request == null
                ? new OfflineReflectionRunRequest(repoRoot, userId, Boolean.TRUE, Boolean.FALSE, 6, 6, 3)
                : request;
        boolean persistMemory = safeBoolean(safeRequest.persistMemory(), true);
        boolean createFollowUpTasks = safeBoolean(safeRequest.createFollowUpTasks(), false);
        int maxRecentMemories = clamp(safeRequest.maxRecentMemories(), 1, 12, 6);
        int maxRecentCronRuns = clamp(safeRequest.maxRecentCronRuns(), 1, 12, 6);
        int maxFollowUpTasks = clamp(safeRequest.maxFollowUpTasks(), 1, 6, 3);
        long now = System.currentTimeMillis();

        TaskBoardView board = taskService.board(repoRoot);
        List<MemoryHeaderResponse> recentMemories = memoryService.list(repoRoot, userId).stream()
                .sorted(Comparator.comparingLong(header -> -header.mtimeMs()))
                .limit(maxRecentMemories)
                .map(MemoryHeaderResponse::from)
                .toList();
        List<OfflineReflectionCronRunResponse> recentCronRuns = collectRecentCronRuns(repoRoot, maxRecentCronRuns);
        List<String> suggestedActions = buildSuggestedActions(board, recentCronRuns, recentMemories);
        String summary = buildSummary(board, recentCronRuns, recentMemories, suggestedActions);
        String reflectionBody = buildReflectionBody(now, board, recentCronRuns, recentMemories, suggestedActions);

        MemoryEntry persistedEntry = null;
        if (persistMemory) {
            persistedEntry = memoryService.save(
                    repoRoot,
                    userId,
                    new MemoryWriteRequest(
                            "Offline reflection " + TITLE_TIME.format(Instant.ofEpochMilli(now)),
                            summary,
                            MemoryType.PROJECT,
                            reflectionBody
                    )
            );
        }

        List<OfflineReflectionTaskResponse> createdTasks = createFollowUpTasks
                ? createFollowUpTasks(repoRoot, suggestedActions, maxFollowUpTasks, now)
                : List.of();

        return new OfflineReflectionResponse(
                now,
                summary,
                reflectionBody,
                persistMemory,
                persistedEntry == null ? null : MemoryEntryResponse.from(persistedEntry),
                new OfflineReflectionTaskBoardResponse(
                        board.total(),
                        board.pending(),
                        board.inProgress(),
                        board.completed(),
                        board.ready(),
                        board.blocked(),
                        board.allCompleted()
                ),
                recentCronRuns,
                recentMemories,
                suggestedActions,
                createdTasks
        );
    }

    private List<OfflineReflectionCronRunResponse> collectRecentCronRuns(String repoRoot, int limit) {
        List<OfflineReflectionCronRunResponse> runs = new ArrayList<>();
        for (AgentCronJobResponse job : cronJobService.list()) {
            Object metadataRepoRoot = job.metadata().get("repoRoot");
            if (metadataRepoRoot != null && !Objects.equals(repoRoot, String.valueOf(metadataRepoRoot))) {
                continue;
            }
            for (AgentCronRunResponse run : job.recentRuns()) {
                runs.add(new OfflineReflectionCronRunResponse(
                        job.jobId(),
                        job.name(),
                        run.triggeredAt(),
                        run.status(),
                        run.finalMessage(),
                        run.errorMessage()
                ));
            }
        }
        return runs.stream()
                .sorted(Comparator.comparingLong(OfflineReflectionCronRunResponse::triggeredAt).reversed())
                .limit(limit)
                .toList();
    }

    private List<String> buildSuggestedActions(
            TaskBoardView board,
            List<OfflineReflectionCronRunResponse> recentCronRuns,
            List<MemoryHeaderResponse> recentMemories
    ) {
        List<String> actions = new ArrayList<>();
        if (board.inProgress() > 0) {
            actions.add("恢复并收口当前 in-progress 任务，避免长期悬挂造成上下文漂移。");
        }
        if (board.blocked() > 0) {
            actions.add("优先梳理 blocked 任务的依赖，尽快把阻塞链缩短到 1-2 个关键前置项。");
        }
        if (board.ready() > 0 && board.inProgress() == 0) {
            actions.add("从 ready task 中认领下一项，保持主线持续推进。");
        }
        long failedRuns = recentCronRuns.stream()
                .filter(run -> !"succeeded".equalsIgnoreCase(run.status()))
                .count();
        if (failedRuns > 0) {
            actions.add("检查最近失败的 cron / background run，把失败原因沉淀成修复项或经验记忆。");
        }
        long recentProjectMemories = recentMemories.stream()
                .filter(memory -> "project".equalsIgnoreCase(memory.type()))
                .count();
        if (recentProjectMemories < 2) {
            actions.add("补写关键架构决策或进展摘要，避免项目亮点只留在会话上下文里。");
        }
        if (actions.isEmpty()) {
            actions.add("当前主线比较稳定，继续按任务看板推进，并定期沉淀 project memory。");
        }
        return List.copyOf(actions);
    }

    private String buildSummary(
            TaskBoardView board,
            List<OfflineReflectionCronRunResponse> recentCronRuns,
            List<MemoryHeaderResponse> recentMemories,
            List<String> suggestedActions
    ) {
        long failedRuns = recentCronRuns.stream()
                .filter(run -> !"succeeded".equalsIgnoreCase(run.status()))
                .count();
        return "离线反思已完成：当前任务总数 %d，进行中 %d，已阻塞 %d，ready %d，最近 cron 运行 %d 次（失败 %d 次），最近记忆 %d 条，建议动作 %d 项。"
                .formatted(
                        board.total(),
                        board.inProgress(),
                        board.blocked(),
                        board.ready(),
                        recentCronRuns.size(),
                        failedRuns,
                        recentMemories.size(),
                        suggestedActions.size()
                );
    }

    private String buildReflectionBody(
            long now,
            TaskBoardView board,
            List<OfflineReflectionCronRunResponse> recentCronRuns,
            List<MemoryHeaderResponse> recentMemories,
            List<String> suggestedActions
    ) {
        StringBuilder body = new StringBuilder();
        body.append("# Offline Reflection\n\n");
        body.append("- generatedAt: ").append(Instant.ofEpochMilli(now)).append('\n');
        body.append("- taskBoard: total=").append(board.total())
                .append(", pending=").append(board.pending())
                .append(", inProgress=").append(board.inProgress())
                .append(", completed=").append(board.completed())
                .append(", ready=").append(board.ready())
                .append(", blocked=").append(board.blocked())
                .append(", allCompleted=").append(board.allCompleted())
                .append("\n\n");

        body.append("## Task Signals\n\n");
        appendTaskList(body, "Ready Tasks", board.readyTasks());
        appendTaskList(body, "In Progress Tasks", board.inProgressTasks());
        appendTaskList(body, "Blocked Tasks", board.blockedTasks());

        body.append("## Cron Signals\n\n");
        if (recentCronRuns.isEmpty()) {
            body.append("- no recent cron runs\n");
        } else {
            for (OfflineReflectionCronRunResponse run : recentCronRuns) {
                body.append("- ")
                        .append(run.jobName())
                        .append(" [").append(run.status()).append("] @ ")
                        .append(Instant.ofEpochMilli(run.triggeredAt()));
                if (run.errorMessage() != null && !run.errorMessage().isBlank()) {
                    body.append(" -> error: ").append(run.errorMessage());
                } else if (run.finalMessage() != null && !run.finalMessage().isBlank()) {
                    body.append(" -> ").append(run.finalMessage());
                }
                body.append('\n');
            }
        }
        body.append('\n');

        body.append("## Recent Memory Signals\n\n");
        if (recentMemories.isEmpty()) {
            body.append("- no recent memories\n");
        } else {
            for (MemoryHeaderResponse memory : recentMemories) {
                body.append("- ")
                        .append(memory.name())
                        .append(" [").append(memory.type()).append("] -> ")
                        .append(memory.description())
                        .append('\n');
            }
        }
        body.append('\n');

        body.append("## Suggested Next Actions\n\n");
        for (String action : suggestedActions) {
            body.append("- ").append(action).append('\n');
        }
        return body.toString().trim();
    }

    private void appendTaskList(StringBuilder body, String title, List<TaskView> tasks) {
        body.append("### ").append(title).append("\n\n");
        if (tasks == null || tasks.isEmpty()) {
            body.append("- none\n\n");
            return;
        }
        for (TaskView view : tasks) {
            body.append("- ")
                    .append(view.task().id())
                    .append(": ")
                    .append(view.task().subject());
            if (!view.blockingDependencies().isEmpty()) {
                body.append(" (blockedBy=").append(view.blockingDependencies()).append(')');
            }
            body.append('\n');
        }
        body.append('\n');
    }

    private List<OfflineReflectionTaskResponse> createFollowUpTasks(
            String repoRoot,
            List<String> suggestedActions,
            int maxFollowUpTasks,
            long now
    ) {
        List<OfflineReflectionTaskResponse> created = new ArrayList<>();
        int index = 1;
        for (String action : suggestedActions.stream().limit(maxFollowUpTasks).toList()) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", "offline_reflection");
            metadata.put("generatedAt", now);
            metadata.put("suggestionIndex", index);
            String subject = "reflection follow-up " + index;
            created.add(toTaskResponse(taskService.create(
                    repoRoot,
                    new TaskCreateRequest(subject, action, List.of(), metadata)
            )));
            index++;
        }
        return List.copyOf(created);
    }

    private OfflineReflectionTaskResponse toTaskResponse(TaskView view) {
        return new OfflineReflectionTaskResponse(
                view.task().id(),
                view.task().subject(),
                view.task().status().wireValue(),
                view.task().createdAt()
        );
    }

    private OfflineReflectionTaskResponse toTaskResponse(TaskRecord task) {
        return new OfflineReflectionTaskResponse(
                task.id(),
                task.subject(),
                task.status().wireValue(),
                task.createdAt()
        );
    }

    private boolean safeBoolean(Boolean value, boolean defaultValue) {
        return value == null ? defaultValue : value;
    }

    private int clamp(Integer value, int min, int max, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return Math.max(min, Math.min(max, value));
    }
}
