package com.lumisight.core.tool.impl.lsp;

import com.lumisight.common.concurrent.NamedExecutors;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageServer;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class JdtlsSessionManager {

    private static final Duration INIT_TIMEOUT = Duration.ofSeconds(20);
    private static final ExecutorService STDERR_DRAIN_POOL = NamedExecutors.newFixedPool("jdtls-stderr-drain", 1);

    private final Map<String, JdtlsSession> sessions = new ConcurrentHashMap<>();

    @PreDestroy
    public void shutdownAll() {
        sessions.values().forEach(JdtlsSession::closeQuietly);
        sessions.clear();
        STDERR_DRAIN_POOL.shutdownNow();
    }

    public Map<String, List<org.eclipse.lsp4j.Diagnostic>> collectDiagnostics(Path repoRoot, List<Path> files, Duration waitTimeout) {
        String key = repoRoot.toAbsolutePath().normalize().toString();
        JdtlsSession session = sessions.compute(key, (k, old) -> {
            if (old != null && old.alive()) {
                return old;
            }
            if (old != null) {
                old.closeQuietly();
            }
            return createSession(repoRoot);
        });

        synchronized (session) {
            session.client.clear();
            session.openOrChangeDocuments(files);
            try {
                Thread.sleep(waitTimeout.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return session.client.snapshot();
        }
    }

    private JdtlsSession createSession(Path repoRoot) {
        try {
            Process process = startJdtlsProcess(repoRoot);
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
            server.initialize(init).get(INIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            server.initialized(new InitializedParams());

            return new JdtlsSession(repoRoot, process, server, client);
        } catch (Exception e) {
            throw new IllegalStateException("创建 jdtls 会话失败: " + e.getMessage(), e);
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

    private void drainErrorStream(Process process) {
        STDERR_DRAIN_POOL.submit(() -> {
            try (var in = process.getErrorStream()) {
                byte[] buf = new byte[1024];
                while (in.read(buf) >= 0) {
                    // drain only
                }
            } catch (Exception ignored) {
            }
        });
    }

    private static final class JdtlsSession {
        private final Path repoRoot;
        private final Process process;
        private final LanguageServer server;
        private final JdtlsDiagnosticsCollector client;
        private final Set<String> openedUris = ConcurrentHashMap.newKeySet();
        private final AtomicInteger versionCounter = new AtomicInteger(1);

        private JdtlsSession(Path repoRoot, Process process, LanguageServer server, JdtlsDiagnosticsCollector client) {
            this.repoRoot = repoRoot;
            this.process = process;
            this.server = server;
            this.client = client;
        }

        private boolean alive() {
            return process.isAlive();
        }

        private void openOrChangeDocuments(List<Path> files) {
            for (Path file : files) {
                try {
                    String uri = file.toUri().toString();
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    if (openedUris.add(uri)) {
                        TextDocumentItem item = new TextDocumentItem(uri, "java", versionCounter.getAndIncrement(), content);
                        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(item));
                    } else {
                        VersionedTextDocumentIdentifier id = new VersionedTextDocumentIdentifier(uri, versionCounter.getAndIncrement());
                        TextDocumentContentChangeEvent event = new TextDocumentContentChangeEvent(content);
                        server.getTextDocumentService().didChange(new DidChangeTextDocumentParams(id, List.of(event)));
                    }
                } catch (Exception ignored) {
                }
            }
        }

        private void closeQuietly() {
            try {
                server.shutdown().get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
            try {
                server.exit();
            } catch (Exception ignored) {
            }
            if (process.isAlive()) {
                process.destroy();
            }
        }
    }
}
