package com.lumisight.core.agent.tool.impl.lsp;

import com.lumisight.core.agent.context.AgentToolRuntimeContext;
import com.lumisight.core.agent.model.AgentContextItem;
import com.lumisight.core.agent.model.ToolArgumentSpec;
import com.lumisight.core.agent.tool.AgentToolCategory;
import com.lumisight.core.agent.tool.AgentToolPermission;
import com.lumisight.core.agent.tool.PermissionedAgentTool;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageServer;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@Component
public class JdtlsLintJavaTool implements PermissionedAgentTool {

    private static final Duration INIT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration DIAGNOSTIC_WAIT = Duration.ofMillis(1500);

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
    public List<ToolArgumentSpec> argumentSpecs() {
        return List.of(
                new ToolArgumentSpec("sourceFile", "string", false, "单文件相对路径"),
                new ToolArgumentSpec("filePattern", "string", false, "批量匹配模式"),
                new ToolArgumentSpec("maxFiles", "integer", false, "最多检查文件数")
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

        Process process = null;
        try {
            process = startJdtlsProcess(repoRoot);
            drainErrorStream(process);
            JdtlsDiagnosticsCollector client = new JdtlsDiagnosticsCollector();
            Launcher<LanguageServer> launcher = LSPLauncher.createClientLauncher(
                    client,
                    process.getInputStream(),
                    process.getOutputStream()
            );
            LanguageServer server = launcher.getRemoteProxy();
            launcher.startListening();

            InitializeParams init = new InitializeParams();
            init.setProcessId((int) ProcessHandle.current().pid());
            init.setRootUri(repoRoot.toUri().toString());
            init.setCapabilities(new ClientCapabilities());
            init.setWorkspaceFolders(List.of(new WorkspaceFolder(repoRoot.toUri().toString(), repoRoot.getFileName().toString())));
            InitializeResult ignored = server.initialize(init).get(INIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            server.initialized(new InitializedParams());

            int version = 1;
            for (Path file : files) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                TextDocumentItem item = new TextDocumentItem(file.toUri().toString(), "java", version++, content);
                server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(item));
            }

            Thread.sleep(DIAGNOSTIC_WAIT.toMillis());
            Map<String, List<org.eclipse.lsp4j.Diagnostic>> diagnostics = client.snapshot();
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

            try {
                server.shutdown().get(3, TimeUnit.SECONDS);
            } catch (Exception ignored2) {
            }
            try {
                server.exit();
            } catch (Exception ignored3) {
            }
            return List.of(new AgentContextItem(
                    "lsp_java",
                    "lintJavaByJdtls",
                    "jdtls 诊断完成",
                    Map.of("checkedFiles", files.size(), "issuesCount", issues.size(), "issues", issues)
            ));
        } catch (Exception e) {
            return error("jdtls 诊断失败: " + e.getMessage());
        } finally {
            if (process != null && process.isAlive()) {
                process.destroy();
            }
        }
    }

    private Process startJdtlsProcess(Path repoRoot) throws Exception {
        String cmd = System.getProperty("lumisight.jdtls.command");
        if (cmd == null || cmd.isBlank()) {
            cmd = System.getenv("LUMISIGHT_JDTLS_COMMAND");
        }
        if (cmd == null || cmd.isBlank()) {
            cmd = "jdtls";
        }
        List<String> parts = splitCommand(cmd);
        ProcessBuilder builder = new ProcessBuilder(parts);
        builder.directory(repoRoot.toFile());
        builder.redirectErrorStream(false);
        return builder.start();
    }

    private void drainErrorStream(Process process) {
        Thread thread = new Thread(() -> {
            try (var in = process.getErrorStream()) {
                byte[] buf = new byte[1024];
                while (in.read(buf) >= 0) {
                    // drain only
                }
            } catch (Exception ignored) {
            }
        });
        thread.setName("jdtls-stderr-drain");
        thread.setDaemon(true);
        thread.start();
    }

    private List<String> splitCommand(String cmd) {
        String[] items = cmd.trim().split("\\s+");
        List<String> parts = new ArrayList<>();
        for (String item : items) {
            if (!item.isBlank()) {
                parts.add(item);
            }
        }
        return parts;
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
