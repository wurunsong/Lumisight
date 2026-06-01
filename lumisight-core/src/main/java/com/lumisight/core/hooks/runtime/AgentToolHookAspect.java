package com.lumisight.core.hooks.runtime;

import com.lumisight.core.model.AgentToolExecutionResult;
import com.lumisight.core.model.ToolDecision;
import com.lumisight.hooks.AgentHookContext;
import com.lumisight.hooks.AgentHookDispatcher;
import com.lumisight.hooks.AgentHookPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.Map;

@Aspect
@Component
public class AgentToolHookAspect {

    private final AgentHookDispatcher agentHookDispatcher;

    public AgentToolHookAspect(AgentHookDispatcher agentHookDispatcher) {
        this.agentHookDispatcher = agentHookDispatcher;
    }

    @Around("execution(* com.lumisight.core.tool.runtime.AgentToolExecutionService.execute(..))")
    public Object aroundToolExecute(ProceedingJoinPoint joinPoint) throws Throwable {
        Object[] methodArgs = joinPoint.getArgs();
        ToolDecision decision = methodArgs.length > 0 && methodArgs[0] instanceof ToolDecision td ? td : null;
        ToolHookContext context = methodArgs.length > 3 && methodArgs[3] instanceof ToolHookContext thc
                ? thc
                : ToolHookContext.empty();
        String sessionId = context == null ? "" : context.sessionId();
        int round = context == null ? 0 : context.round();
        String question = context == null ? "" : context.question();
        String toolName = decision == null || decision.toolName() == null ? "" : decision.toolName();
        Map<String, Object> args = decision == null || decision.args() == null ? Map.of() : decision.args();

        fireHook(AgentHookPoint.BEFORE_TOOL_CALL, sessionId, round, question, toolName, args);
        try {
            Object result = joinPoint.proceed();
            if (result instanceof AgentToolExecutionResult toolResult) {
                fireHook(AgentHookPoint.AFTER_TOOL_CALL, sessionId, round, question, toolName, Map.of(
                        "status", toolResult.status(),
                        "count", toolResult.items() == null ? 0 : toolResult.items().size()
                ));
                if ("error".equals(toolResult.status())) {
                    fireHook(AgentHookPoint.ON_ERROR, sessionId, round, question, toolName, Map.of(
                            "stage", "executeTool",
                            "errorCode", String.valueOf(toolResult.metrics().getOrDefault("errorCode", "tool_error")),
                            "message", toolResult.message()
                    ));
                }
            }
            return result;
        } catch (Throwable t) {
            fireHook(AgentHookPoint.ON_ERROR, sessionId, round, question, toolName, Map.of(
                    "stage", "executeTool",
                    "errorCode", "tool_invoke_exception",
                    "message", t.getMessage() == null ? "" : t.getMessage()
            ));
            throw t;
        }
    }

    private void fireHook(AgentHookPoint point, String sessionId, int round, String question, String toolName, Map<String, Object> metadata) {
        agentHookDispatcher.fire(point, new AgentHookContext(
                sessionId,
                round,
                question,
                toolName,
                metadata == null ? Map.of() : metadata
        ));
    }
}
