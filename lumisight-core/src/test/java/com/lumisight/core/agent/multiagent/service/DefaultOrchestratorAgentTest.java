package com.lumisight.core.agent.multiagent.service;

import com.lumisight.core.agent.multiagent.model.OrchestrationContext;
import com.lumisight.core.agent.multiagent.model.OrchestrationPlan;
import com.lumisight.core.agent.multiagent.model.TopologyType;
import com.lumisight.core.agent.multiagent.port.TaskRouter;
import com.lumisight.core.model.AgentDialogueMode;
import com.lumisight.core.model.AgentRequest;
import com.lumisight.core.model.AgentRunMode;
import com.lumisight.core.model.AgentTaskType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class DefaultOrchestratorAgentTest {

    @Test
    void shouldCreateFanOutPlanForFrontendAndBackendQuestion() {
        DefaultOrchestratorAgent agent = new DefaultOrchestratorAgent(mock(TaskRouter.class), properties());

        OrchestrationPlan plan = agent.createPlan(context("请同时排查前端页面渲染问题和后端接口报错"));

        assertEquals(TopologyType.FAN_OUT_FAN_IN, plan.topology());
        assertEquals(2, plan.tasks().size());
        assertEquals("前端侧分析", plan.tasks().get(0).title());
        assertEquals("后端侧分析", plan.tasks().get(1).title());
        assertTrue(plan.tasks().stream().allMatch(task -> task.dependsOn().isEmpty()));
    }

    @Test
    void shouldCreateHybridPlanWithDependencyForFixAndTestQuestion() {
        DefaultOrchestratorAgent agent = new DefaultOrchestratorAgent(mock(TaskRouter.class), properties());

        OrchestrationPlan plan = agent.createPlan(context("请修复这个问题并评估需要补哪些测试"));

        assertEquals(TopologyType.HYBRID, plan.topology());
        assertEquals(2, plan.tasks().size());
        assertEquals("修复方案分析", plan.tasks().get(0).title());
        assertEquals("测试影响分析", plan.tasks().get(1).title());
        assertFalse(plan.tasks().get(1).dependsOn().isEmpty());
        assertEquals(plan.tasks().get(0).taskId(), plan.tasks().get(1).dependsOn().getFirst());
    }

    private MultiAgentProperties properties() {
        MultiAgentProperties properties = new MultiAgentProperties();
        properties.setMaxTasksPerPlan(8);
        properties.setSubagentMaxRounds(6);
        return properties;
    }

    private OrchestrationContext context(String question) {
        return new OrchestrationContext(
                "orch-test",
                new AgentRequest(
                        AgentTaskType.BUG_FIX,
                        "/tmp/repo",
                        question,
                        null,
                        "user",
                        "session",
                        false,
                        false,
                        false,
                        true,
                        true,
                        6,
                        AgentRunMode.MULTI_AGENT,
                        AgentDialogueMode.FOLLOW
                ),
                java.util.Map.of()
        );
    }
}
