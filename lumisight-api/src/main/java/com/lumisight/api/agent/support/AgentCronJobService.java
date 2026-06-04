package com.lumisight.api.agent.support;

import com.lumisight.api.agent.dto.request.AgentCronJobUpsertRequest;
import com.lumisight.api.agent.dto.request.AgentRunRequest;
import com.lumisight.api.agent.dto.response.AgentCronJobResponse;
import com.lumisight.api.agent.dto.response.AgentCronRunResponse;
import com.lumisight.core.agent.AgentExecutionEngine;
import com.lumisight.core.model.AgentEvent;
import com.lumisight.core.model.AgentRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.Disposable;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Service
public class AgentCronJobService {

    private final TaskScheduler taskScheduler;
    private final AgentCronProperties properties;
    private final AgentRequestMapper agentRequestMapper;
    private final AgentExecutionEngine agentExecutionEngine;

    private final Map<String, CronJobState> jobs = new ConcurrentHashMap<>();

    public AgentCronJobService(
            TaskScheduler agentCronTaskScheduler,
            AgentCronProperties properties,
            AgentRequestMapper agentRequestMapper,
            AgentExecutionEngine agentExecutionEngine
    ) {
        this.taskScheduler = agentCronTaskScheduler;
        this.properties = properties;
        this.agentRequestMapper = agentRequestMapper;
        this.agentExecutionEngine = agentExecutionEngine;
    }

    public List<AgentCronJobResponse> list() {
        return jobs.values().stream()
                .sorted(Comparator.comparingLong((CronJobState state) -> state.definition.updatedAt()).reversed())
                .map(this::toResponse)
                .toList();
    }

    public AgentCronJobResponse get(String jobId) {
        return toResponse(requireState(jobId));
    }

    public AgentCronJobResponse create(AgentCronJobUpsertRequest request) {
        CronJobDefinition definition = buildDefinition(null, request, System.currentTimeMillis(), null);
        CronJobState state = new CronJobState(definition, new LinkedList<>(), null, null);
        jobs.put(definition.jobId(), state);
        reschedule(definition.jobId());
        return toResponse(requireState(definition.jobId()));
    }

    public AgentCronJobResponse update(String jobId, AgentCronJobUpsertRequest request) {
        CronJobState existing = requireState(jobId);
        CronJobDefinition definition = buildDefinition(jobId, request, existing.definition.createdAt(), existing);
        CronJobState next = new CronJobState(definition, existing.runs, existing.scheduledFuture, existing.activeSubscription);
        jobs.put(jobId, next);
        cancelSchedule(existing);
        reschedule(jobId);
        return toResponse(requireState(jobId));
    }

    public void delete(String jobId) {
        CronJobState removed = jobs.remove(jobId);
        if (removed == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "cron job not found");
        }
        cancelSchedule(removed);
        cancelActiveRun(removed);
    }

    public AgentCronJobResponse triggerNow(String jobId) {
        executeNow(requireState(jobId), "manual");
        return toResponse(requireState(jobId));
    }

    private CronJobDefinition buildDefinition(String jobId, AgentCronJobUpsertRequest request, long createdAt, CronJobState existing) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request is required");
        }
        String cron = normalizeCron(request.cron());
        AgentRunRequest normalizedRunRequest = normalizeRunRequest(jobId, request.request(), existing);
        long now = System.currentTimeMillis();
        return new CronJobDefinition(
                StringUtils.hasText(jobId) ? jobId : "cron-" + UUID.randomUUID(),
                requireName(request.name()),
                cron,
                request.enabled() == null || request.enabled(),
                properties.getTimezone(),
                normalizedRunRequest,
                createdAt,
                now,
                existing == null ? null : existing.definition.lastTriggeredAt(),
                existing == null ? "idle" : existing.definition.lastStatus(),
                existing == null ? "" : existing.definition.lastMessage()
        );
    }

    private AgentRunRequest normalizeRunRequest(String jobId, AgentRunRequest request, CronJobState existing) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "run request is required");
        }
        String normalizedJobId = StringUtils.hasText(jobId)
                ? jobId
                : existing == null ? "cron-" + UUID.randomUUID() : existing.definition.jobId();
        String sessionId = StringUtils.hasText(request.sessionId())
                ? request.sessionId().trim()
                : "cron-session-" + normalizedJobId;
        String userId = StringUtils.hasText(request.userId()) ? request.userId().trim() : "cron-user";
        return new AgentRunRequest(
                request.taskType(),
                request.repoRoot(),
                request.question(),
                request.skillPath(),
                userId,
                sessionId,
                request.approveRiskyToolCall(),
                Boolean.FALSE,
                Boolean.FALSE,
                request.includeRagContext(),
                request.includeKnowledgeGraphContext(),
                request.contextLimit(),
                request.runMode(),
                request.dialogueMode()
        );
    }

    private String requireName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        return name.trim();
    }

    private String normalizeCron(String cron) {
        if (!StringUtils.hasText(cron)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cron is required");
        }
        String normalized = cron.trim();
        try {
            new CronTrigger(normalized, ZoneId.of(properties.getTimezone()));
            return normalized;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid cron: " + normalized);
        }
    }

    private void reschedule(String jobId) {
        CronJobState state = requireState(jobId);
        if (!state.definition.enabled()) {
            return;
        }
        ScheduledFuture<?> future = taskScheduler.schedule(
                () -> executeScheduled(jobId),
                new CronTrigger(state.definition.cron(), ZoneId.of(state.definition.timezone()))
        );
        jobs.computeIfPresent(jobId, (key, old) -> old.withScheduledFuture(future));
    }

    private void executeScheduled(String jobId) {
        CronJobState state = requireState(jobId);
        executeNow(state, "cron");
    }

    private void executeNow(CronJobState state, String triggerType) {
        cancelActiveRun(state);
        String runId = "run-" + UUID.randomUUID();
        long triggeredAt = System.currentTimeMillis();
        CronRunRecord runRecord = new CronRunRecord(runId, triggeredAt, null, "running", "", null);
        jobs.computeIfPresent(state.definition.jobId(), (key, old) -> old.withRunStarted(runRecord, properties.getMaxRecentRuns(), triggerType));
        AgentRequest agentRequest = agentRequestMapper.toAgentRequest(state.definition.request());
        Disposable subscription = agentExecutionEngine.execute(agentRequest)
                .doOnNext(event -> onEvent(state.definition.jobId(), runId, event))
                .doOnError(error -> onError(state.definition.jobId(), runId, error))
                .doOnComplete(() -> onComplete(state.definition.jobId(), runId))
                .subscribe();
        jobs.computeIfPresent(state.definition.jobId(), (key, old) -> old.withActiveSubscription(subscription));
    }

    private void onEvent(String jobId, String runId, AgentEvent event) {
        jobs.computeIfPresent(jobId, (key, old) -> old.onEvent(runId, event));
    }

    private void onComplete(String jobId, String runId) {
        jobs.computeIfPresent(jobId, (key, old) -> old.finishRun(runId, "succeeded", null));
    }

    private void onError(String jobId, String runId, Throwable error) {
        jobs.computeIfPresent(jobId, (key, old) -> old.finishRun(runId, "failed", error == null ? "unknown error" : error.getMessage()));
    }

    private void cancelSchedule(CronJobState state) {
        if (state.scheduledFuture != null) {
            state.scheduledFuture.cancel(false);
        }
    }

    private void cancelActiveRun(CronJobState state) {
        if (state.activeSubscription != null && !state.activeSubscription.isDisposed()) {
            state.activeSubscription.dispose();
        }
    }

    private CronJobState requireState(String jobId) {
        CronJobState state = jobs.get(jobId);
        if (state == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "cron job not found");
        }
        return state;
    }

    private AgentCronJobResponse toResponse(CronJobState state) {
        CronJobDefinition definition = state.definition;
        return new AgentCronJobResponse(
                definition.jobId(),
                definition.name(),
                definition.cron(),
                definition.enabled(),
                definition.timezone(),
                definition.request().sessionId(),
                definition.request().userId(),
                definition.request().question(),
                definition.createdAt(),
                definition.updatedAt(),
                definition.lastTriggeredAt(),
                definition.lastStatus(),
                definition.lastMessage(),
                state.runs.stream().map(this::toRunResponse).toList(),
                Map.of(
                        "taskType", definition.request().taskType(),
                        "runMode", definition.request().runMode(),
                        "dialogueMode", definition.request().dialogueMode(),
                        "repoRoot", definition.request().repoRoot(),
                        "skillPath", definition.request().skillPath()
                )
        );
    }

    private AgentCronRunResponse toRunResponse(CronRunRecord run) {
        return new AgentCronRunResponse(run.runId(), run.triggeredAt(), run.completedAt(), run.status(), run.finalMessage(), run.errorMessage());
    }

    private record CronJobDefinition(
            String jobId,
            String name,
            String cron,
            boolean enabled,
            String timezone,
            AgentRunRequest request,
            long createdAt,
            long updatedAt,
            Long lastTriggeredAt,
            String lastStatus,
            String lastMessage
    ) {
        private CronJobDefinition withRunState(long triggeredAt, String status, String message) {
            return new CronJobDefinition(jobId, name, cron, enabled, timezone, request, createdAt, System.currentTimeMillis(), triggeredAt, status, message);
        }
    }

    private static final class CronJobState {
        private final CronJobDefinition definition;
        private final Deque<CronRunRecord> runs;
        private final ScheduledFuture<?> scheduledFuture;
        private final Disposable activeSubscription;

        private CronJobState(
                CronJobDefinition definition,
                Deque<CronRunRecord> runs,
                ScheduledFuture<?> scheduledFuture,
                Disposable activeSubscription
        ) {
            this.definition = definition;
            this.runs = runs;
            this.scheduledFuture = scheduledFuture;
            this.activeSubscription = activeSubscription;
        }

        private CronJobState withScheduledFuture(ScheduledFuture<?> future) {
            return new CronJobState(definition, runs, future, activeSubscription);
        }

        private CronJobState withActiveSubscription(Disposable subscription) {
            return new CronJobState(definition, runs, scheduledFuture, subscription);
        }

        private CronJobState withRunStarted(CronRunRecord runRecord, int maxRecentRuns, String triggerType) {
            Deque<CronRunRecord> nextRuns = new LinkedList<>(runs);
            nextRuns.addFirst(runRecord);
            while (nextRuns.size() > maxRecentRuns) {
                nextRuns.removeLast();
            }
            String lastMessage = "triggered by " + triggerType;
            return new CronJobState(definition.withRunState(runRecord.triggeredAt(), "running", lastMessage), nextRuns, scheduledFuture, activeSubscription);
        }

        private CronJobState onEvent(String runId, AgentEvent event) {
            if (event == null) {
                return this;
            }
            Deque<CronRunRecord> nextRuns = new LinkedList<>();
            for (CronRunRecord run : runs) {
                if (!run.runId().equals(runId)) {
                    nextRuns.addLast(run);
                    continue;
                }
                nextRuns.addLast(run.withEvent(event));
            }
            String nextMessage = event.message() == null ? definition.lastMessage() : event.message();
            return new CronJobState(definition.withRunState(definition.lastTriggeredAt() == null ? System.currentTimeMillis() : definition.lastTriggeredAt(), definition.lastStatus(), nextMessage), nextRuns, scheduledFuture, activeSubscription);
        }

        private CronJobState finishRun(String runId, String status, String errorMessage) {
            long completedAt = System.currentTimeMillis();
            Deque<CronRunRecord> nextRuns = new LinkedList<>();
            String finalMessage = definition.lastMessage();
            for (CronRunRecord run : runs) {
                if (!run.runId().equals(runId)) {
                    nextRuns.addLast(run);
                    continue;
                }
                CronRunRecord finished = run.finish(completedAt, status, errorMessage);
                finalMessage = StringUtils.hasText(finished.finalMessage()) ? finished.finalMessage() : finalMessage;
                nextRuns.addLast(finished);
            }
            return new CronJobState(definition.withRunState(definition.lastTriggeredAt() == null ? completedAt : definition.lastTriggeredAt(), status, finalMessage), nextRuns, scheduledFuture, null);
        }
    }

    private record CronRunRecord(
            String runId,
            long triggeredAt,
            Long completedAt,
            String status,
            String finalMessage,
            String errorMessage
    ) {
        private CronRunRecord withEvent(AgentEvent event) {
            if (event == null) {
                return this;
            }
            String nextMessage = finalMessage;
            if ("FINAL".equals(event.type()) || "ASK_USER".equals(event.type()) || "HUMAN_GATE".equals(event.type())) {
                nextMessage = event.message();
            } else if (!StringUtils.hasText(nextMessage) && StringUtils.hasText(event.message())) {
                nextMessage = event.message();
            }
            return new CronRunRecord(runId, triggeredAt, completedAt, status, nextMessage, errorMessage);
        }

        private CronRunRecord finish(long nextCompletedAt, String nextStatus, String nextErrorMessage) {
            return new CronRunRecord(runId, triggeredAt, nextCompletedAt, nextStatus, finalMessage, nextErrorMessage);
        }
    }
}
