package com.lumisight.core.agent.tool.impl.lsp;

import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.services.LanguageClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

final class JdtlsDiagnosticsCollector implements LanguageClient {

    private final Map<String, List<org.eclipse.lsp4j.Diagnostic>> diagnosticsByUri = new ConcurrentHashMap<>();

    @Override
    public void telemetryEvent(Object object) {
    }

    @Override
    public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
        diagnosticsByUri.put(diagnostics.getUri(), diagnostics.getDiagnostics());
    }

    @Override
    public void showMessage(MessageParams messageParams) {
    }

    @Override
    public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void logMessage(MessageParams message) {
    }

    Map<String, List<org.eclipse.lsp4j.Diagnostic>> snapshot() {
        return Map.copyOf(diagnosticsByUri);
    }
}
