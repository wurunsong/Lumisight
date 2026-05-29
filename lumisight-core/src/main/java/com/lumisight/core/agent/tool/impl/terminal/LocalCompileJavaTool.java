package com.lumisight.core.agent.tool.impl.terminal;

import com.lumisight.core.agent.context.AgentToolRuntimeContext;
import com.lumisight.core.agent.model.AgentContextItem;
import com.lumisight.core.agent.model.ToolArgumentSpec;
import com.lumisight.core.agent.tool.AgentToolCategory;
import com.lumisight.core.agent.tool.AgentToolPermission;
import com.lumisight.core.agent.tool.PermissionedAgentTool;
import org.springframework.stereotype.Component;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@Component
public class LocalCompileJavaTool implements PermissionedAgentTool {

    @Override
    public String toolName() {
        return "compileJava";
    }

    @Override
    public AgentToolCategory category() {
        return AgentToolCategory.BUILD;
    }

    @Override
    public AgentToolPermission permission() {
        return AgentToolPermission.BUILD_COMPILE;
    }

    @Override
    public List<ToolArgumentSpec> argumentSpecs() {
        return List.of(
                new ToolArgumentSpec("sourceFile", "string", false, "单文件相对路径"),
                new ToolArgumentSpec("filePattern", "string", false, "批量匹配模式"),
                new ToolArgumentSpec("maxFiles", "integer", false, "最多编译文件数")
        );
    }

    @Override
    public List<AgentContextItem> invoke(Map<String, Object> args, int defaultLimit) {
        try {
            AgentToolRuntimeContext.Context context = AgentToolRuntimeContext.required();
            Path repoRoot = LocalRepoPathSupport.requireRepoRoot(context.repoRoot());
            String sourceFile = stringValue(args.get("sourceFile"));
            String filePattern = stringValue(args.get("filePattern"));
            int maxFiles = intValue(args.get("maxFiles"), 50);
            List<Path> files = resolveFiles(repoRoot, sourceFile, filePattern, maxFiles);
            if (files.isEmpty()) {
                return List.of(new AgentContextItem("build", "compileJava", "未找到可编译的 Java 文件", Map.of("checkedFiles", 0)));
            }

            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                return List.of(new AgentContextItem("tool_error", "compileJava", "当前运行环境不支持 JavaCompiler（需要JDK）", Map.of()));
            }

            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            boolean success;
            try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
                fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(tempOutputDir().toFile()));
                Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(files.stream().map(Path::toFile).toList());
                List<String> options = List.of("-proc:none", "-encoding", "UTF-8");
                JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, options, null, units);
                success = Boolean.TRUE.equals(task.call());
            }

            List<Map<String, Object>> issues = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                String file = diagnostic.getSource() == null ? "" : relativizeSafely(repoRoot, Path.of(diagnostic.getSource().toUri()));
                issues.add(Map.of(
                        "file", file,
                        "line", diagnostic.getLineNumber(),
                        "column", diagnostic.getColumnNumber(),
                        "severity", diagnostic.getKind().name(),
                        "message", String.valueOf(diagnostic.getMessage(null))
                ));
            }
            return List.of(new AgentContextItem(
                    "build",
                    "compileJava",
                    success ? "编译通过" : "编译失败",
                    Map.of("success", success, "checkedFiles", files.size(), "issues", issues, "issuesCount", issues.size())
            ));
        } catch (Exception e) {
            return List.of(new AgentContextItem("tool_error", "compileJava", "编译失败: " + e.getMessage(), Map.of()));
        }
    }

    private List<Path> resolveFiles(Path repoRoot, String sourceFile, String filePattern, int maxFiles) throws Exception {
        if (!sourceFile.isBlank()) {
            Path file = LocalRepoPathSupport.resolveInRepo(repoRoot, sourceFile);
            if (Files.exists(file) && Files.isRegularFile(file) && file.toString().endsWith(".java")) {
                return List.of(file);
            }
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(repoRoot)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> filePattern.isBlank() || repoRoot.relativize(path).toString().contains(filePattern))
                    .limit(maxFiles)
                    .toList();
        }
    }

    private Path tempOutputDir() throws Exception {
        Path dir = Files.createTempDirectory("lumisight-compile-");
        dir.toFile().deleteOnExit();
        return dir;
    }

    private String relativizeSafely(Path root, Path file) {
        try {
            return root.relativize(file.toAbsolutePath().normalize()).toString();
        } catch (Exception e) {
            return file.toString();
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
}
