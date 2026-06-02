package com.lumisight.core.tool.impl.lsp;

import com.lumisight.core.context.AgentToolRuntimeContext;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.model.ToolArgumentSpec;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Component
public class JdtlsLintJavaTool implements PermissionedAgentTool {

    private static final Duration DIAGNOSTIC_WAIT = Duration.ofMillis(1500);

    private final JdtlsSessionManager sessionManager;

    public JdtlsLintJavaTool(JdtlsSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public String toolName() {
        return "lintJavaByJdtls";
    }

    @Override
    public AgentToolCategory category() {
        return AgentToolCategory.LSP;
    }

    @Override
    public AgentToolPermission permission() {
        return AgentToolPermission.LSP_JAVA_READ;
    }

    @Override
    public String description() {
        return "通过 JDT Language Server 获取 Java 诊断信息，适合做更贴近 IDE 的语义级检查。";
    }

    @Override
    public List<ToolArgumentSpec> argumentSpecs() {
        return List.of(
                new ToolArgumentSpec("sourceFile", "string", false, "单文件相对路径"),
                new ToolArgumentSpec("filePattern", "string", false, "批量匹配模式"),
                new ToolArgumentSpec("maxFiles", "integer", false, "最多检查文件数")
        );
    }

    @Override
    public Map<String, Object> exampleArgs() {
        return Map.of(
                "filePattern", "agent",
                "maxFiles", 10
        );
    }

    @Override
    public List<AgentContextItem> invoke(Map<String, Object> args, int defaultLimit) {
        AgentToolRuntimeContext.Context context = AgentToolRuntimeContext.required();
        Path repoRoot = JavaLspPathSupport.requireRepoRoot(context.repoRoot());
        String sourceFile = stringValue(args.get("sourceFile"));
        String filePattern = stringValue(args.get("filePattern"));
        int maxFiles = intValue(args.get("maxFiles"), 20);

        List<Path> files;
        try {
            files = resolveFiles(repoRoot, sourceFile, filePattern, maxFiles);
        } catch (Exception e) {
            return error("文件解析失败: " + e.getMessage());
        }
        if (files.isEmpty()) {
            return List.of(new AgentContextItem("lsp_java", "lintJavaByJdtls", "未找到可检查的 Java 文件", Map.of("checkedFiles", 0)));
        }

        try {
            Map<String, List<org.eclipse.lsp4j.Diagnostic>> diagnostics = sessionManager.collectDiagnostics(repoRoot, files, DIAGNOSTIC_WAIT);
            List<Map<String, Object>> issues = new ArrayList<>();
            for (Map.Entry<String, List<org.eclipse.lsp4j.Diagnostic>> entry : diagnostics.entrySet()) {
                String uri = entry.getKey();
                String file = relativize(repoRoot, URI.create(uri));
                for (org.eclipse.lsp4j.Diagnostic diagnostic : entry.getValue()) {
                    issues.add(Map.of(
                            "file", file,
                            "line", diagnostic.getRange().getStart().getLine() + 1,
                            "column", diagnostic.getRange().getStart().getCharacter() + 1,
                            "severity", diagnostic.getSeverity() == null ? "UNKNOWN" : diagnostic.getSeverity().name(),
                            "message", String.valueOf(diagnostic.getMessage())
                    ));
                }
            }
            return List.of(new AgentContextItem(
                    "lsp_java",
                    "lintJavaByJdtls",
                    "jdtls 诊断完成",
                    Map.of("checkedFiles", files.size(), "issuesCount", issues.size(), "issues", issues)
            ));
        } catch (Exception e) {
            return error("jdtls 诊断失败: " + e.getMessage());
        }
    }

    private List<Path> resolveFiles(Path repoRoot, String sourceFile, String filePattern, int maxFiles) throws Exception {
        if (!sourceFile.isBlank()) {
            Path file = repoRoot.resolve(sourceFile).normalize();
            if (!file.startsWith(repoRoot)) {
                return List.of();
            }
            if (Files.exists(file) && Files.isRegularFile(file) && file.getFileName().toString().endsWith(".java")) {
                return List.of(file);
            }
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(repoRoot)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .filter(path -> filePattern.isBlank() || repoRoot.relativize(path).toString().contains(filePattern))
                    .limit(maxFiles)
                    .toList();
        }
    }

    private String relativize(Path repoRoot, URI fileUri) {
        try {
            Path file = Path.of(fileUri).toAbsolutePath().normalize();
            return repoRoot.relativize(file).toString();
        } catch (Exception e) {
            return fileUri.toString();
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private int intValue(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    private List<AgentContextItem> error(String message) {
        return List.of(new AgentContextItem("tool_error", "lintJavaByJdtls", message, Map.of()));
    }
}
