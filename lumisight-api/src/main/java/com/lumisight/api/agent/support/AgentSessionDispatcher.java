package com.lumisight.api.agent.support;

import com.lumisight.api.agent.dto.request.AgentRunRequest;
import com.lumisight.core.model.AgentDialogueMode;
import com.lumisight.core.model.AgentEvent;
import com.lumisight.core.support.AgentConversationManager;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;

@Component
public class AgentSessionDispatcher {

    private final Map<String, SessionMailbox> mailboxes = new ConcurrentHashMap<>();
    private final ExecutorService workerPool = Executors.newCachedThreadPool();
    private final AgentInteractionOrchestrator interactionOrchestrator;
    private final AgentConversationManager conversationManager;

    public AgentSessionDispatcher(
            AgentInteractionOrchestrator interactionOrchestrator,
            AgentConversationManager conversationManager
    ) {
        this.interactionOrchestrator = interactionOrchestrator;
        this.conversationManager = conversationManager;
    }

    public Flux<AgentEvent> stream(AgentRunRequest request) {
        AgentRunRequest normalized = ensureIds(request);
        Sinks.Many<AgentEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        QueuedEnvelope envelope = new QueuedEnvelope(normalized, sink);
        SessionMailbox mailbox = mailboxes.computeIfAbsent(normalized.sessionId(), SessionMailbox::new);
        mailbox.submit(envelope);
        return sink.asFlux();
    }

    private AgentRunRequest ensureIds(AgentRunRequest request) {
        String sessionId = StringUtils.hasText(request.sessionId()) ? request.sessionId().trim() : UUID.randomUUID().toString();
        String userId = StringUtils.hasText(request.userId()) ? request.userId().trim() : "debug-user";
        return new AgentRunRequest(
                request.taskType(),
                request.repoRoot(),
                request.question(),
                request.skillPath(),
                userId,
                sessionId,
                request.approveRiskyToolCall(),
                request.interrupt(),
                request.resume(),
                request.includeRagContext(),
                request.includeKnowledgeGraphContext(),
                request.contextLimit(),
                request.runMode(),
                request.dialogueMode()
        );
    }

    private final class SessionMailbox {
        private final String sessionId;
        private final BlockingQueue<QueuedEnvelope> queue = new LinkedBlockingQueue<>();
        private volatile RunningExecution current;
        private volatile boolean workerStarted;

        private SessionMailbox(String sessionId) {
            this.sessionId = sessionId;
        }

        private synchronized void submit(QueuedEnvelope envelope) {
            if (envelope.request.interrupt()) {
                handleInterrupt(envelope);
                return;
            }
            AgentDialogueMode mode = parseMode(envelope.request.dialogueMode());
            if (current == null && !queue.isEmpty()) {
                switch (mode) {
                    case COLLECT -> {
                        QueuedEnvelope target = findMergeTarget();
                        if (target != null) {
                            target.merge(envelope);
                            envelope.emit(AgentEvent.state("", sessionId, 0, "QUEUE", "queued", "COLLECT: 已合并到待启动的问题。"));
                            ensureWorker();
                            return;
                        }
                    }
                    case STEER -> {
                        clearPending("被新的 STEER 请求替换。");
                        queue.offer(envelope);
                        envelope.emit(AgentEvent.state("", sessionId, 0, "STEER", "queued", "STEER: 已替换尚未启动的待处理问题。"));
                        ensureWorker();
                        return;
                    }
                    case FOLLOW -> {
                        // no-op: keep FIFO ordering
                    }
                }
            }
            if (current != null) {
                switch (mode) {
                    case FOLLOW -> {
                        envelope.emit(AgentEvent.state("", sessionId, 0, "QUEUE", "queued", "FOLLOW: 已进入本地会话队列，等待当前执行完成。"));
                        queue.offer(envelope);
                    }
                    case COLLECT -> {
                        QueuedEnvelope target = findMergeTarget();
                        if (target == null) {
                            envelope.emit(AgentEvent.state("", sessionId, 0, "QUEUE", "queued", "COLLECT: 已加入待处理队列，等待当前执行完成。"));
                            queue.offer(envelope);
                        } else {
                            target.merge(envelope);
                            envelope.emit(AgentEvent.state("", sessionId, 0, "QUEUE", "queued", "COLLECT: 已合并到同会话待处理问题。"));
                        }
                    }
                    case STEER -> {
                        clearPending("被新的 STEER 请求替换。");
                        queue.offer(envelope);
                        envelope.emit(AgentEvent.state("", sessionId, 0, "STEER", "queued", "STEER: 已进入优先队列，正在中断当前执行。"));
                        current.interruptForSteer();
                    }
                }
                ensureWorker();
                return;
            }

            queue.offer(envelope);
            ensureWorker();
        }

        private void handleInterrupt(QueuedEnvelope envelope) {
            clearPending("会话已被用户手动停止。");
            RunningExecution running = current;
            if (running != null) {
                running.interrupt("INTERRUPT", "interrupting", "INTERRUPT: 当前执行已收到手动停止请求。");
            } else {
                conversationManager.nextEpoch(sessionId);
                conversationManager.interrupt(sessionId);
            }
            envelope.emit(AgentEvent.interrupted("", sessionId, 0));
            envelope.complete();
        }

        private synchronized void ensureWorker() {
            if (workerStarted) {
                return;
            }
            workerStarted = true;
            workerPool.submit(this::drainLoop);
        }

        private void drainLoop() {
            try {
                while (true) {
                    QueuedEnvelope envelope = queue.poll(200, TimeUnit.MILLISECONDS);
                    if (envelope == null) {
                        synchronized (this) {
                            if (current == null && queue.isEmpty()) {
                                workerStarted = false;
                                mailboxes.remove(sessionId, this);
                                return;
                            }
                        }
                        continue;
                    }
                    runEnvelope(envelope);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                synchronized (this) {
                    workerStarted = false;
                    if (current == null && queue.isEmpty()) {
                        mailboxes.remove(sessionId, this);
                    }
                }
            }
        }

        private void runEnvelope(QueuedEnvelope envelope) {
            CountDownLatch latch = new CountDownLatch(1);
            RunningExecution running = new RunningExecution(sessionId, envelope, latch);
            synchronized (this) {
                current = running;
            }

            Disposable disposable = interactionOrchestrator.stream(envelope.request)
                    .doOnNext(envelope::emit)
                    .doOnError(error -> {
                        envelope.error(error);
                        latch.countDown();
                    })
                    .doOnComplete(() -> {
                        envelope.complete();
                        latch.countDown();
                    })
                    .doFinally(signalType -> latch.countDown())
                    .subscribe();
            running.disposable = disposable;

            try {
                latch.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                synchronized (this) {
                    current = null;
                }
            }
        }

        private QueuedEnvelope findMergeTarget() {
            for (QueuedEnvelope candidate : queue) {
                return candidate;
            }
            return null;
        }

        private void clearPending(String reason) {
            List<QueuedEnvelope> dropped = new ArrayList<>();
            queue.drainTo(dropped);
            for (QueuedEnvelope envelope : dropped) {
                envelope.emit(AgentEvent.state("", sessionId, 0, "STEER", "interrupted", reason));
                envelope.complete();
            }
        }
    }

    private AgentDialogueMode parseMode(String raw) {
        if (!StringUtils.hasText(raw)) {
            return AgentDialogueMode.FOLLOW;
        }
        try {
            return AgentDialogueMode.valueOf(raw.trim().toUpperCase());
        } catch (Exception ignored) {
            return AgentDialogueMode.FOLLOW;
        }
    }

    private static final class QueuedEnvelope {
        private final List<Sinks.Many<AgentEvent>> sinks = new ArrayList<>();
        private AgentRunRequest request;

        private QueuedEnvelope(AgentRunRequest request, Sinks.Many<AgentEvent> sink) {
            this.request = request;
            this.sinks.add(sink);
        }

        private synchronized void merge(QueuedEnvelope incoming) {
            String mergedQuestion = mergeQuestions(request.question(), incoming.request.question());
            request = new AgentRunRequest(
                    request.taskType(),
                    request.repoRoot(),
                    mergedQuestion,
                    request.skillPath(),
                    request.userId(),
                    request.sessionId(),
                    request.approveRiskyToolCall(),
                    request.interrupt(),
                    request.resume(),
                    request.includeRagContext(),
                    request.includeKnowledgeGraphContext(),
                    request.contextLimit(),
                    request.runMode(),
                    request.dialogueMode()
            );
            sinks.addAll(incoming.sinks);
        }

        private static String mergeQuestions(String base, String extra) {
            if (!StringUtils.hasText(base)) {
                return extra;
            }
            if (!StringUtils.hasText(extra)) {
                return base;
            }
            return base + "\n用户追加问题: " + extra;
        }

        private synchronized void emit(AgentEvent event) {
            for (Sinks.Many<AgentEvent> sink : sinks) {
                sink.tryEmitNext(event);
            }
        }

        private synchronized void error(Throwable error) {
            for (Sinks.Many<AgentEvent> sink : sinks) {
                sink.tryEmitError(error);
            }
        }

        private synchronized void complete() {
            for (Sinks.Many<AgentEvent> sink : sinks) {
                sink.tryEmitComplete();
            }
        }
    }

    private final class RunningExecution {
        private final String sessionId;
        private final QueuedEnvelope envelope;
        private final CountDownLatch latch;
        private volatile Disposable disposable;

        private RunningExecution(String sessionId, QueuedEnvelope envelope, CountDownLatch latch) {
            this.sessionId = sessionId;
            this.envelope = envelope;
            this.latch = latch;
        }

        private void interruptForSteer() {
            interrupt("STEER", "interrupting", "STEER: 当前执行即将让出给最新问题。");
        }

        private void interrupt(String stage, String status, String reason) {
            conversationManager.nextEpoch(sessionId);
            conversationManager.interrupt(sessionId);
            envelope.emit(AgentEvent.state("", sessionId, 0, stage, status, reason));
            envelope.emit(AgentEvent.interrupted("", sessionId, 0));
            if (disposable != null && !disposable.isDisposed()) {
                disposable.dispose();
            }
            latch.countDown();
        }
    }
}
