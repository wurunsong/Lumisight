package com.lumisight.api.agent.support;

import com.lumisight.common.concurrent.NamedExecutors;
import com.lumisight.api.agent.dto.request.AgentRunRequest;
import com.lumisight.core.model.AgentDialogueMode;
import com.lumisight.core.model.AgentEvent;
import com.lumisight.core.support.AgentSessionContextStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantLock;
// todo dispatcher需要再梳理
@Component
@Slf4j
public class AgentSessionDispatcher {
    private static final String STEP_INTERRUPT = "INTERRUPT";
    private static final String STEP_QUEUE = "QUEUE";

    // 每个session一个mailbox，用来存储用户的提问，也涉及到collect、steer、follow三种模式
    private final Map<String, SessionMailbox> mailboxes = new ConcurrentHashMap<>();
    private final ExecutorService workerPool = NamedExecutors.newCachedPool("agent-session-worker");
    // agent调度类
    private final AgentInteractionOrchestrator interactionOrchestrator;
    // 会话上下文管理类
    private final AgentSessionContextStore conversationManager;

    public AgentSessionDispatcher(
            AgentInteractionOrchestrator interactionOrchestrator,
            AgentSessionContextStore conversationManager
    ) {
        this.interactionOrchestrator = interactionOrchestrator;
        this.conversationManager = conversationManager;
    }

    public void submit(AgentRunRequest request) {
        AgentRunRequest normalized = ensureIds(request);
        SessionMailbox mailbox = mailboxes.computeIfAbsent(normalized.sessionId(), SessionMailbox::new);
        mailbox.submit(new QueuedEnvelope(normalized));
    }

    public Flux<AgentEvent> subscribe(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return Flux.error(new IllegalArgumentException("sessionId is required"));
        }
        SessionMailbox mailbox = mailboxes.computeIfAbsent(sessionId.trim(), SessionMailbox::new);
        return mailbox.subscribe();
    }

    public void cancel(String sessionId, String reason) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        SessionMailbox mailbox = mailboxes.get(sessionId.trim());
        if (mailbox == null) {
            return;
        }
        mailbox.cancel(reason);
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

    /**
     * 信箱类，用于管理当前会话的用户提问以及提问状态
     */
    private final class SessionMailbox {
        private final String sessionId;
        private final BlockingQueue<QueuedEnvelope> queue = new LinkedBlockingQueue<>();
        private final Sinks.Many<AgentEvent> eventSink = Sinks.many().multicast().directBestEffort();
        private final ReentrantLock stateLock = new ReentrantLock();
        private volatile RunningExecution current;
        // 当前会话是否有正在执行的任务
        private boolean workerStarted;
        // 订阅数需要和 current / queue / workerStarted 一起参与 idle 判定，所以仍由同一把锁保护。
        private int subscriberCount;

        private SessionMailbox(String sessionId) {
            this.sessionId = sessionId;
        }

        /**
         * 提交一个新的用户提问
         * @param envelope 用户提问
         */
        private void submit(QueuedEnvelope envelope) {
            List<AgentEvent> delayedEvents = new ArrayList<>();
            RunningExecution runningToInterrupt = null;
            boolean startWorker = false;
            boolean manualInterrupt = Boolean.TRUE.equals(envelope.request.interrupt());

            stateLock.lock();
            try {
                if (manualInterrupt) {
                    clearPendingLocked(STEP_INTERRUPT, "会话已被用户手动停止。", true, delayedEvents);
                    runningToInterrupt = current;
                    if (runningToInterrupt == null) {
                        conversationManager.nextEpoch(sessionId);
                        conversationManager.interrupt(sessionId);
                    }
                    delayedEvents.add(AgentEvent.interrupted("", sessionId, 0));
                    return;
                }

                AgentDialogueMode mode = parseMode(envelope.request.dialogueMode());
                // 当前没有正在执行中的任务，并且消息队列非空
                if (current == null && !queue.isEmpty()) {
                    switch (mode) {
                        // 把新消息合并到queue的第一条消息中
                        case COLLECT -> {
                            if (mergeIntoPendingLocked(envelope)) {
                                delayedEvents.add(AgentEvent.state("", sessionId, 0, STEP_QUEUE, "queued", "COLLECT: 已合并到待启动的问题。"));
                                startWorker = markWorkerStartLocked();
                                return;
                            }
                        }
                        // 把旧消息都丢掉
                        case STEER -> {
                            clearPendingLocked(mode.name(), "被新的 STEER 请求替换。", true, delayedEvents);
                            queue.offer(envelope);
                            delayedEvents.add(AgentEvent.state("", sessionId, 0, mode.name(), "queued", "STEER: 已替换尚未启动的待处理问题。"));
                            startWorker = markWorkerStartLocked();
                            return;
                        }
                        // follow走下面的默认逻辑
                        case FOLLOW -> {
                            // no-op: keep FIFO ordering
                        }
                    }
                }
                // 当前有任务正在执行
                if (current != null) {
                    switch (mode) {
                        case FOLLOW -> {
                            queue.offer(envelope);
                            delayedEvents.add(AgentEvent.state("", sessionId, 0, STEP_QUEUE, "queued", "FOLLOW: 已进入本地会话队列，等待当前执行完成。"));
                        }
                        case COLLECT -> {
                            if (mergeIntoPendingLocked(envelope)) {
                                delayedEvents.add(AgentEvent.state("", sessionId, 0, STEP_QUEUE, "queued", "COLLECT: 已合并到同会话待处理问题。"));
                            } else {
                                queue.offer(envelope);
                                delayedEvents.add(AgentEvent.state("", sessionId, 0, STEP_QUEUE, "queued", "COLLECT: 已加入待处理队列，等待当前执行完成。"));
                            }
                        }
                        case STEER -> {
                            clearPendingLocked(mode.name(), "被新的 STEER 请求替换。", true, delayedEvents);
                            queue.offer(envelope);
                            delayedEvents.add(AgentEvent.state("", sessionId, 0, mode.name(), "queued", "STEER: 已进入优先队列，正在中断当前执行。"));
                            runningToInterrupt = current;
                        }
                    }
                    startWorker = markWorkerStartLocked();
                    return;
                }

                queue.offer(envelope);
                startWorker = markWorkerStartLocked();
            } finally {
                stateLock.unlock();
            }
            // 向用户推送状态信息
            delayedEvents.forEach(this::emit);
            if (runningToInterrupt != null) {
                if (manualInterrupt) {
                    log.info("agent_session interrupt requested, sessionId={}, hasRunning=true", sessionId);
                    runningToInterrupt.interrupt(STEP_INTERRUPT, "interrupting", "INTERRUPT: 当前执行已收到手动停止请求。", true);
                } else {
                    runningToInterrupt.interruptForSteer();
                }
            } else if (manualInterrupt) {
                log.info("agent_session interrupt requested, sessionId={}, hasRunning=false", sessionId);
            }
            // 执行agent逻辑，是一个阻塞队列操作。这里只会起一个线程，如果后面又有对话过来，startWorker=false
            if (startWorker) {
                workerPool.submit(this::drainLoop);
            }
        }

        private Flux<AgentEvent> subscribe() {
            return Flux.defer(() -> {
                stateLock.lock();
                try {
                    subscriberCount++;
                } finally {
                    stateLock.unlock();
                }
                return eventSink.asFlux().doFinally(signalType -> onSubscriberDetached());
            });
        }

        private void cancel(String reason) {
            RunningExecution running = null;
            stateLock.lock();
            try {
                if (current == null && queue.isEmpty()) {
                    return;
                }
                log.info("agent_session cancel, sessionId={}, reason={}", sessionId, reason);
                clearPendingLocked(STEP_INTERRUPT, reason, false, null);
                running = current;
                if (running == null) {
                    conversationManager.nextEpoch(sessionId);
                    conversationManager.interrupt(sessionId);
                }
            } finally {
                stateLock.unlock();
            }
            if (running != null) {
                running.interrupt(STEP_INTERRUPT, "interrupting", reason, false);
            }
        }

        private void drainLoop() {
            try {
                while (true) {
                    QueuedEnvelope envelope = queue.poll(200, TimeUnit.MILLISECONDS);
                    if (envelope == null) {
                        stateLock.lock();
                        try {
                            if (isIdleLocked()) {
                                workerStarted = false;
                                mailboxes.remove(sessionId, this);
                                return;
                            }
                        } finally {
                            stateLock.unlock();
                        }
                        continue;
                    }
                    runEnvelope(envelope);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                stateLock.lock();
                try {
                    workerStarted = false;
                    if (isIdleLocked()) {
                        mailboxes.remove(sessionId, this);
                    }
                } finally {
                    stateLock.unlock();
                }
            }
        }

        private void runEnvelope(QueuedEnvelope envelope) {
            CountDownLatch latch = new CountDownLatch(1);
            RunningExecution running = new RunningExecution(this, sessionId, envelope, latch);
            running.workerThread = Thread.currentThread();
            stateLock.lock();
            try {
                current = running;
            } finally {
                stateLock.unlock();
            }

            Disposable disposable = interactionOrchestrator.stream(envelope.request)
                    .doOnNext(this::emit)
                    .doOnError(error -> {
                        log.warn("agent_session run failed, sessionId={}, message={}", sessionId, error.getMessage());
                        emit(AgentEvent.error("agent run failed: " + error.getMessage()));
                        latch.countDown();
                    })
                    .doOnComplete(latch::countDown)
                    .doFinally(signalType -> latch.countDown())
                    .subscribe();
            running.disposable = disposable;

            try {
                latch.await();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } finally {
                stateLock.lock();
                try {
                    current = null;
                } finally {
                    stateLock.unlock();
                }
            }
        }

        private boolean mergeIntoPendingLocked(QueuedEnvelope incoming) {
            QueuedEnvelope target = findMergeTarget();
            if (target == null) {
                return false;
            }
            target.merge(incoming);
            return true;
        }

        private QueuedEnvelope findMergeTarget() {
            QueuedEnvelope target = null;
            for (QueuedEnvelope candidate : queue) {
                target = candidate;
            }
            return target;
        }

        private void clearPendingLocked(String step, String reason, boolean emitEvents, List<AgentEvent> delayedEvents) {
            List<QueuedEnvelope> dropped = new ArrayList<>();
            queue.drainTo(dropped);
            if (emitEvents && !dropped.isEmpty() && delayedEvents != null) {
                delayedEvents.add(AgentEvent.state("", sessionId, 0, step, "interrupted", reason));
            }
        }

        private void emit(AgentEvent event) {
            eventSink.tryEmitNext(event);
        }

        private void onSubscriberDetached() {
            stateLock.lock();
            try {
                subscriberCount = Math.max(0, subscriberCount - 1);
                if (isIdleLocked()) {
                    mailboxes.remove(sessionId, this);
                }
            } finally {
                stateLock.unlock();
            }
        }

        private boolean isIdleLocked() {
            // mailbox 回收依赖联合状态判断；这里只要拆成独立原子变量，就容易把“是否还能安全删除”看错。
            return current == null && queue.isEmpty() && subscriberCount == 0;
        }

        private boolean markWorkerStartLocked() {
            if (workerStarted) {
                return false;
            }
            workerStarted = true;
            return true;
        }
    }

    private AgentDialogueMode parseMode(String raw) {
        if (!StringUtils.hasText(raw)) {
            return AgentDialogueMode.FOLLOW;
        }
        try {
            return AgentDialogueMode.valueOf(raw.trim().toUpperCase());
        } catch (Exception ignored) {
            log.warn("agent_session dialogue mode parse failed, raw={}", raw);
            return AgentDialogueMode.FOLLOW;
        }
    }

    private static final class QueuedEnvelope {
        private AgentRunRequest request;

        private QueuedEnvelope(AgentRunRequest request) {
            this.request = request;
        }

        private void merge(QueuedEnvelope incoming) {
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

    }

    /**
     * 用于管理当前会话正在执行的Agent任务
     * 相比简单的workerStarted，这里还包含了当前会话的状态信息disposable，以及当前会话的执行线程workerThread
     */
    private final class RunningExecution {
        private final SessionMailbox mailbox;
        private final String sessionId;
        private final QueuedEnvelope envelope;
        private final CountDownLatch latch;
        private volatile Disposable disposable;
        private volatile Thread workerThread;

        private RunningExecution(SessionMailbox mailbox, String sessionId, QueuedEnvelope envelope, CountDownLatch latch) {
            this.mailbox = mailbox;
            this.sessionId = sessionId;
            this.envelope = envelope;
            this.latch = latch;
        }

        private void interruptForSteer() {
            interrupt(AgentDialogueMode.STEER.name(), "interrupting", "STEER: 当前执行即将让出给最新问题。", true);
        }

        private void interrupt(String stage, String status, String reason, boolean emitEvents) {
            conversationManager.nextEpoch(sessionId);
            conversationManager.interrupt(sessionId);
            if (emitEvents) {
                mailbox.emit(AgentEvent.state("", sessionId, 0, stage, status, reason));
                mailbox.emit(AgentEvent.interrupted("", sessionId, 0));
            }
            Thread executingThread = workerThread;
            if (executingThread != null) {
                log.info("agent_session interrupting worker thread, sessionId={}, thread={}", sessionId, executingThread.getName());
                executingThread.interrupt();
            }
            if (disposable != null && !disposable.isDisposed()) {
                disposable.dispose();
            }
            latch.countDown();
        }
    }
}
