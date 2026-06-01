package com.lumisight.hooks;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class ConfigurableAgentHook implements AgentHook {

    private static final Logger log = LoggerFactory.getLogger(ConfigurableAgentHook.class);
    private static final String DEFAULT_CONFIG_PATH = ".codeflicker/config.json";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<PreToolUseRule> preToolUseRules;

    public ConfigurableAgentHook() {
        this.preToolUseRules = loadPreToolUseRules();
        log.info("configurable_hook loaded, preToolUseRules={}", preToolUseRules.size());
    }

    @Override
    public int order() {
        return -200;
    }

    @Override
    public boolean supports(AgentHookPoint point) {
        return point == AgentHookPoint.BEFORE_TOOL_CALL && !preToolUseRules.isEmpty();
    }

    @Override
    public void onHook(AgentHookPoint point, AgentHookContext context) {
        if (point != AgentHookPoint.BEFORE_TOOL_CALL) {
            return;
        }
        String toolName = context.toolName() == null ? "" : context.toolName();
        for (PreToolUseRule rule : preToolUseRules) {
            if (!rule.matcher().matcher(toolName).find()) {
                continue;
            }
            for (CommandHook hook : rule.hooks()) {
                runCommandHook(hook, context);
            }
        }
    }

    private List<PreToolUseRule> loadPreToolUseRules() {
        String configuredPath = System.getProperty("lumisight.hooks.config", DEFAULT_CONFIG_PATH);
        Path path = Path.of(configuredPath).toAbsolutePath().normalize();
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            log.info("configurable_hook config not found, path={}", path);
            return List.of();
        }
        try (InputStream in = Files.newInputStream(path)) {
            Map<String, Object> root = objectMapper.readValue(in, new TypeReference<>() {});
            Object hooksObj = root.get("hooks");
            if (!(hooksObj instanceof Map<?, ?> hooksMap)) {
                return List.of();
            }
            Object preToolUse = hooksMap.get("PreToolUse");
            if (!(preToolUse instanceof List<?> list)) {
                return List.of();
            }
            List<PreToolUseRule> result = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> ruleMap)) {
                    continue;
                }
                String matcherText = asString(ruleMap.get("matcher"), "");
                if (matcherText.isBlank()) {
                    continue;
                }
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
                    if (!command.isBlank()) {
                        commandHooks.add(new CommandHook(command));
                    }
                }
                if (!commandHooks.isEmpty()) {
                    result.add(new PreToolUseRule(Pattern.compile(matcherText), commandHooks));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("configurable_hook load failed, path={}, error={}", path, e.getMessage());
            return List.of();
        }
    }

    private void runCommandHook(CommandHook hook, AgentHookContext context) {
        Map<String, Object> payload = Map.of(
                "hook_event_name", "PreToolUse",
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
        pb.redirectErrorStream(false);
        try {
            Process process = pb.start();
            try (OutputStream os = process.getOutputStream()) {
                os.write(payloadJson.getBytes(StandardCharsets.UTF_8));
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                String reason = stderr.isBlank() ? stdout : stderr;
                throw new IllegalStateException("Hook command blocked request: " + hook.command() + " | " + reason);
            }
            if (!stdout.isBlank() && stdout.startsWith("{")) {
                Map<String, Object> result = objectMapper.readValue(stdout, new TypeReference<>() {});
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

    private record PreToolUseRule(Pattern matcher, List<CommandHook> hooks) {
    }

    private record CommandHook(String command) {
    }
}
