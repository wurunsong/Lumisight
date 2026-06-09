package com.lumisight.core.tool.impl.terminal;

import com.lumisight.core.context.ambient.AgentToolRuntimeContext;
import com.lumisight.core.model.AgentContextItem;
import com.lumisight.core.tool.AgentToolCategory;
import com.lumisight.core.tool.AgentToolPermission;
import com.lumisight.core.tool.PermissionedAgentTool;
import com.lumisight.core.tool.ToolArg;
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
public class LocalJavaLintTool implements PermissionedAgentTool<LocalJavaLintTool.Args> {

    public record Args(
            @ToolArg(description = "单文件相对路径", example = "src/main/java/com/example/Foo.java") String sourceFile,
            @ToolArg(description = "批量匹配模式（包含匹配）", example = "agent") String filePattern,
            @ToolArg(description = "最多检查文件数", example = "10") Integer maxFiles,
            @ToolArg(description = "最多返回问题数", example = "50") Integer maxIssues
    ) {
    }

    @Override
    public String toolName() {
        return "lintJava";
    }

    @Override
    public AgentToolCategory category() {
        return AgentToolCategory.LOCAL;
    }

    @Override
    public AgentToolPermission permission() {
        return AgentToolPermission.LOCAL_FS_READ;
    }

    @Override
    public Class<Args> argsType() {
        return Args.class;
    }

    @Override
    public String description() {
        return "对指定 Java 文件集合执行编译器级静态诊断，返回错误和警告，适合快速发现语法或类型问题。";
    }

    @Override
    public List<AgentContextItem> invoke(Args args, int defaultLimit) {
        try {
            AgentToolRuntimeContext.Context context = AgentToolRuntimeContext.required();
            Path repoRoot = LocalRepoPathSupport.requireRepoRoot(context.repoRoot());
            String sourceFile = args.sourceFile() == null ? "" : args.sourceFile();
            String filePattern = args.filePattern() == null ? "" : args.filePattern();
            int maxFiles = args.maxFiles() == null ? 20 : args.maxFiles();
            int maxIssues = args.maxIssues() == null ? 200 : args.maxIssues();

            List<Path> files = resolveFiles(repoRoot, sourceFile, filePattern, maxFiles);
            if (files.isEmpty()) {
                return List.of(new AgentContextItem(
                        "local",
                        "lintJava",
                        "未找到可检查的 Java 文件",
                        Map.of("checkedFiles", 0, "issues", List.of())
                ));
            }

            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                return List.of(new AgentContextItem(
                        "tool_error",
                        "lintJava",
                        "当前运行环境不支持 JavaCompiler（可能是 JRE 而非 JDK）",
                        Map.of()
                ));
            }

            DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
            try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null)) {
                fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(tempOutputDir().toFile()));
                Iterable<? extends JavaFileObject> units = fileManager.getJavaFileObjectsFromFiles(
                        files.stream().map(Path::toFile).toList()
                );
                List<String> options = List.of(
                        "-Xlint:all",
                        "-proc:none",
                        "-encoding", "UTF-8"
                );
                JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, options, null, units);
                task.call();
            }

            List<Map<String, Object>> issues = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                if (issues.size() >= maxIssues) {
                    break;
                }
                String file = diagnostic.getSource() == null ? "" : relativizeSafely(repoRoot, Path.of(diagnostic.getSource().toUri()));
                issues.add(Map.of(
                        "file", file,
                        "line", diagnostic.getLineNumber(),
                        "column", diagnostic.getColumnNumber(),
                        "severity", diagnostic.getKind().name(),
                        "message", String.valueOf(diagnostic.getMessage(null))
                ));
            }

            long errorCount = issues.stream().filter(item -> "ERROR".equals(item.get("severity"))).count();
            long warningCount = issues.stream().filter(item -> "WARNING".equals(item.get("severity")) || "MANDATORY_WARNING".equals(item.get("severity"))).count();
            return List.of(new AgentContextItem(
                    "local",
                    "lintJava",
                    "Lint 检查完成",
                    Map.of(
                            "checkedFiles", files.size(),
                            "issuesCount", issues.size(),
                            "errorCount", errorCount,
                            "warningCount", warningCount,
                            "issues", issues
                    )
            ));
        } catch (Exception e) {
            return List.of(new AgentContextItem(
                    "tool_error",
                    "lintJava",
                    "Lint 检查失败: " + e.getMessage(),
                    Map.of()
            ));
        }
    }

    private List<Path> resolveFiles(Path repoRoot, String sourceFile, String filePattern, int maxFiles) throws Exception {
        if (!sourceFile.isBlank()) {
            Path file = LocalRepoPathSupport.resolveInRepo(repoRoot, sourceFile);
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

    private Path tempOutputDir() throws Exception {
        Path dir = Files.createTempDirectory("lumisight-lint-");
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
}
