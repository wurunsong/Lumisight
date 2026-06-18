package com.lumisight.api.agent.support;

import com.lumisight.api.agent.dto.request.AgentRunRequest;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.AgentEvent;
import com.lumisight.core.model.TodoTask;
import com.lumisight.core.model.ToolDecision;
import com.lumisight.core.support.AgentConversationManager;
import com.lumisight.core.support.AgentSessionContextStore;
import com.lumisight.core.support.context.AgentContextSession;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentSessionDispatcherTest {

    @Test
    void steerInterruptsRunningRequestAndStartsLatestQuestion() throws Exception {
        CountDownLatch firstSubscribed = new CountDownLatch(1);
        CountDownLatch firstCancelled = new CountDownLatch(1);
        CountDownLatch secondSubscribed = new CountDownLatch(1);
        CountDownLatch secondCancelled = new CountDownLatch(1);
        CountDownLatch thirdSubscribed = new CountDownLatch(1);
        AgentInteractionOrchestrator orchestrator = new TestInteractionOrchestrator(
                firstSubscribed,
                firstCancelled,
                secondSubscribed,
                secondCancelled,
                thirdSubscribed
        );

        InMemorySessionContextStore contextStore = new InMemorySessionContextStore();
        AgentSessionDispatcher dispatcher = new AgentSessionDispatcher(orchestrator, contextStore);
        BlockingQueue<AgentEvent> events = new LinkedBlockingQueue<>();
        dispatcher.subscribe("session-steer").subscribe(events::offer);

        dispatcher.submit(request("first", "FOLLOW"));
        assertTrue(firstSubscribed.await(1, TimeUnit.SECONDS), "first request should start before STEER");

        dispatcher.submit(request("second", "STEER"));

        assertTrue(firstCancelled.await(1, TimeUnit.SECONDS), "STEER should cancel the running request");
        assertTrue(secondSubscribed.await(1, TimeUnit.SECONDS), "STEER should start the second request");

        dispatcher.submit(request("third", "STEER"));

        assertTrue(secondCancelled.await(1, TimeUnit.SECONDS), "consecutive STEER should cancel the second request");
        assertTrue(thirdSubscribed.await(1, TimeUnit.SECONDS), "consecutive STEER should start the latest request");
        assertEquals(0, contextStore.interruptCalls(), "STEER should supersede by epoch without marking the session manually interrupted");
        assertFalse(events.stream().anyMatch(event -> "interrupting".equals(event.status())), "STEER should not publish old-run interrupting as session state");
    }

    private static final class TestInteractionOrchestrator extends AgentInteractionOrchestrator {
        private final CountDownLatch firstSubscribed;
        private final CountDownLatch firstCancelled;
        private final CountDownLatch secondSubscribed;
        private final CountDownLatch secondCancelled;
        private final CountDownLatch thirdSubscribed;
        private final AtomicInteger callCount = new AtomicInteger(0);

        private TestInteractionOrchestrator(
                CountDownLatch firstSubscribed,
                CountDownLatch firstCancelled,
                CountDownLatch secondSubscribed,
                CountDownLatch secondCancelled,
                CountDownLatch thirdSubscribed
        ) {
            super(null, null);
            this.firstSubscribed = firstSubscribed;
            this.firstCancelled = firstCancelled;
            this.secondSubscribed = secondSubscribed;
            this.secondCancelled = secondCancelled;
            this.thirdSubscribed = thirdSubscribed;
        }

        @Override
        public Flux<AgentEvent> stream(AgentRunRequest runRequest) {
            int call = callCount.incrementAndGet();
            if (call == 1) {
                return Flux.<AgentEvent>never()
                        .doOnSubscribe(subscription -> firstSubscribed.countDown())
                        .doOnCancel(firstCancelled::countDown);
            }
            if (call == 2) {
                return Flux.<AgentEvent>never()
                        .doOnSubscribe(subscription -> secondSubscribed.countDown())
                        .doOnCancel(secondCancelled::countDown);
            }
            return Flux.just(AgentEvent.finalText("third done"))
                    .delaySubscription(Duration.ofMillis(20))
                    .doOnSubscribe(subscription -> thirdSubscribed.countDown());
        }
    }

    private static AgentRunRequest request(String question, String dialogueMode) {
        return new AgentRunRequest(
                "CHAT",
                "/tmp/repo",
                question,
                null,
                "user",
                "session-steer",
                false,
                false,
                false,
                false,
                false,
                null,
                "NORMAL",
                dialogueMode
        );
    }

    private static final class InMemorySessionContextStore implements AgentSessionContextStore {
        private final AtomicLong epoch = new AtomicLong(0);
        private final AtomicInteger interruptCalls = new AtomicInteger(0);

        @Override
        public AgentConversationManager.ConversationState get(String sessionId) {
            return null;
        }

        @Override
        public void saveWaiting(String sessionId, String baseQuestion, List<AgentContextItem> contexts, AgentContextSession contextSession, int nextRound) {
        }

        @Override
        public void saveWaitingForGate(String sessionId, String baseQuestion, List<AgentContextItem> contexts, AgentContextSession contextSession, int nextRound, ToolDecision pendingDecision) {
        }

        @Override
        public void saveRunning(String sessionId, String baseQuestion, List<AgentContextItem> contexts, AgentContextSession contextSession, int nextRound) {
        }

        @Override
        public void interrupt(String sessionId) {
            interruptCalls.incrementAndGet();
        }

        @Override
        public void clear(String sessionId) {
        }

        @Override
        public void updateTodos(String sessionId, List<TodoTask> todos, int round) {
        }

        @Override
        public Optional<AgentContextItem> todoReminderContext(String sessionId, int round) {
            return Optional.empty();
        }

        @Override
        public long nextEpoch(String sessionId) {
            return epoch.incrementAndGet();
        }

        @Override
        public long currentEpoch(String sessionId) {
            return epoch.get();
        }

        @Override
        public boolean isActiveEpoch(String sessionId, long epoch) {
            return this.epoch.get() == epoch;
        }

        @Override
        public int purgeExpiredSessions() {
            return 0;
        }

        private int interruptCalls() {
            return interruptCalls.get();
        }
    }
}
