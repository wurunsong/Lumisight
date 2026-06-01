package com.lumisight.hooks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Component
public class ConfigurableAgentHook implements AgentHook {

    private static final Logger log = LoggerFactory.getLogger(ConfigurableAgentHook.class);
    private static final String DEFAULT_CONFIG_PATH = ".lumisight/hooks.json";
    private static final String LEGACY_CONFIG_PATH = ".codeflicker/config.json";
    private static final long DEFAULT_TIMEOUT_MS = 3_000L;
    private static final int DEFAULT_MAX_OUTPUT_BYTES = 64 * 1024;
    private static final Map<AgentHookPoint, String> POINT_KEYS = Map.of(
            AgentHookPoint.BEFORE_PLAN, "BeforePlan",
            AgentHookPoint.AFTER_PLAN, "AfterPlan",
            AgentHookPoint.BEFORE_DECISION, "BeforeDecision",
            AgentHookPoint.AFTER_DECISION, "AfterDecision",
            AgentHookPoint.BEFORE_TOOL_CALL, "PreToolUse",
            AgentHookPoint.AFTER_TOOL_CALL, "PostToolUse",
            AgentHookPoint.ON_ASK_USER, "OnAskUser",
            AgentHookPoint.BEFORE_FINAL, "BeforeFinal",
            AgentHookPoint.ON_ERROR, "OnError"
    );

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<AgentHookPoint, List<HookRule>> rulesByPoint;

    public ConfigurableAgentHook() {
        this.rulesByPoint = loadRulesByPoint();
        log.info("configurable_hook loaded, activePoints={}", rulesByPoint.keySet());
    }

    @Override
    public int order() {
        return -200;
    }

    @Override
    public boolean supports(AgentHookPoint point) {
        List<HookRule> rules = rulesByPoint.get(point);
        return rules != null && !rules.isEmpty();
    }

    @Override
    public void onHook(AgentHookPoint point, AgentHookContext context) {
        List<HookRule> rules = rulesByPoint.get(point);
        if (rules == null || rules.isEmpty()) {
            return;
        }
        String toolName = context.toolName() == null ? "" : context.toolName();
        for (HookRule rule : rules) {
            if (!rule.matches(toolName)) {
                continue;
            }
            for (CommandHook hook : rule.hooks()) {
                runCommandHook(hook, point, context);
            }
        }
    }

    private Map<AgentHookPoint, List<HookRule>> loadRulesByPoint() {
        String configuredPath = System.getProperty("lumisight.hooks.config");
        Path path = resolveConfigPath(configuredPath);
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            log.info("configurable_hook config not found, path={}", path);
            return Map.of();
        }
        try (InputStream in = Files.newInputStream(path)) {
            Map<String, Object> root = objectMapper.readValue(in, new TypeReference<>() {});
            Object hooksObj = root.get("hooks");
            if (!(hooksObj instanceof Map<?, ?> hooksMap)) {
                return Map.of();
            }
            Map<AgentHookPoint, List<HookRule>> result = new EnumMap<>(AgentHookPoint.class);
            for (AgentHookPoint point : AgentHookPoint.values()) {
                List<?> list = findRulesForPoint(hooksMap, point);
                if (list == null) {
                    continue;
                }
                List<HookRule> pointRules = parseHookRules(list);
                if (!pointRules.isEmpty()) {
                    result.put(point, pointRules);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("configurable_hook load failed, path={}, error={}", path, e.getMessage());
            return Map.of();
        }
    }

    private List<?> findRulesForPoint(Map<?, ?> hooksMap, AgentHookPoint point) {
        String key = POINT_KEYS.get(point);
        Object value = hooksMap.get(key);
        if (value instanceof List<?> list) {
            return list;
        }
        value = hooksMap.get(point.name());
        if (value instanceof List<?> list) {
            return list;
        }
        return null;
    }

    private List<HookRule> parseHookRules(List<?> ruleList) {
        List<HookRule> result = new ArrayList<>();
        for (Object item : ruleList) {
            if (!(item instanceof Map<?, ?> ruleMap)) {
                continue;
            }
            String matcherText = asString(ruleMap.get("matcher"), "");
            Pattern matcher = matcherText.isBlank() ? null : Pattern.compile(matcherText);
            Object hookListObj = ruleMap.get("hooks");
            if (!(hookListObj instanceof List<?> hookList)) {
                continue;
            }
            List<CommandHook> commandHooks = new ArrayList<>();
            for (Object hookObj : hookList) {
                if (!(hookObj instanceof Map<?, ?> hookMap)) {
                    continue;
                }
                String type = asString(hookMap.get("type"), "");
                if (!"command".equalsIgnoreCase(type)) {
                    continue;
                }
                String command = asString(hookMap.get("command"), "");
                long timeoutMs = asLong(hookMap.get("timeoutMs"), DEFAULT_TIMEOUT_MS);
                int maxOutputBytes = (int) asLong(hookMap.get("maxOutputBytes"), DEFAULT_MAX_OUTPUT_BYTES);
                if (!command.isBlank()) {
                    commandHooks.add(new CommandHook(command, timeoutMs, Math.max(1024, maxOutputBytes)));
                }
            }
            if (!commandHooks.isEmpty()) {
                result.add(new HookRule(matcher, commandHooks));
            }
        }
        return result;
    }

    private void runCommandHook(CommandHook hook, AgentHookPoint point, AgentHookContext context) {
        Map<String, Object> payload = Map.of(
                "hook_event_name", POINT_KEYS.getOrDefault(point, point.name()),
                "hook_point", point.name(),
                "tool_name", context.toolName() == null ? "" : context.toolName(),
                "tool_input", context.metadata() == null ? Map.of() : context.metadata(),
                "session_id", context.sessionId() == null ? "" : context.sessionId(),
                "round", context.round(),
                "question", context.question() == null ? "" : context.question()
        );
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("serialize hook payload failed: " + e.getMessage());
        }

        ProcessBuilder pb = new ProcessBuilder("bash", "-lc", hook.command());
        pb.redirectErrorStream(true);
        try {
            Process process = pb.start();
            ByteArrayOutputStream mergedOutput = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> readLimited(process.getInputStream(), mergedOutput, hook.maxOutputBytes()), "hook-output-reader");
            reader.setDaemon(true);
            reader.start();
            try (OutputStream os = process.getOutputStream()) {
                os.write(payloadJson.getBytes(StandardCharsets.UTF_8));
            }
            boolean finished = process.waitFor(hook.timeoutMs(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("Hook command timeout (" + hook.timeoutMs() + "ms): " + hook.command());
            }
            reader.join(Math.min(300L, hook.timeoutMs()));
            String output = mergedOutput.toString(StandardCharsets.UTF_8).trim();
            int exitCode = process.exitValue();

            if (exitCode != 0) {
                throw new IllegalStateException("Hook command blocked request: " + hook.command() + " | " + output);
            }
            if (!output.isBlank() && output.startsWith("{")) {
                Map<String, Object> result = objectMapper.readValue(output, new TypeReference<>() {});
                Object cont = result.get("continue");
                if (cont instanceof Boolean bool && !bool) {
                    String reason = asString(result.get("reason"), "blocked by hook");
                    throw new IllegalStateException(reason);
                }
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Hook command execution failed: " + hook.command() + " | " + e.getMessage());
        }
    }

    private String asString(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? defaultValue : text;
    }

    private long asLong(Object value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private void readLimited(InputStream in, ByteArrayOutputStream out, int limitBytes) {
        byte[] buffer = new byte[1024];
        int total = 0;
        try (in; out) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (total >= limitBytes) {
                    continue;
                }
                int remain = limitBytes - total;
                int writeSize = Math.min(remain, read);
                out.write(buffer, 0, writeSize);
                total += writeSize;
            }
        } catch (Exception ignored) {
            // hook command output read failures should not hide main error reason.
        }
    }

    private Path resolveConfigPath(String configuredPath) {
        if (configuredPath != null && !configuredPath.isBlank()) {
            return Path.of(configuredPath).toAbsolutePath().normalize();
        }
        Path defaultPath = Path.of(DEFAULT_CONFIG_PATH).toAbsolutePath().normalize();
        if (Files.exists(defaultPath) && Files.isRegularFile(defaultPath)) {
            return defaultPath;
        }
        Path legacyPath = Path.of(LEGACY_CONFIG_PATH).toAbsolutePath().normalize();
        if (Files.exists(legacyPath) && Files.isRegularFile(legacyPath)) {
            log.info("configurable_hook legacy config detected, path={}", legacyPath);
            return legacyPath;
        }
        return defaultPath;
    }

    private record HookRule(Pattern matcher, List<CommandHook> hooks) {
        boolean matches(String toolName) {
            if (matcher == null) {
                return true;
            }
            return matcher.matcher(toolName == null ? "" : toolName).find();
        }
    }

    private record CommandHook(String command, long timeoutMs, int maxOutputBytes) {
    }
}
