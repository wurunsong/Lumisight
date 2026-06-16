import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var store: DesktopStore

    var body: some View {
        NavigationSplitView {
            SidebarView()
                .navigationSplitViewColumnWidth(min: 250, ideal: 300)
        } content: {
            ConversationColumnView()
                .navigationSplitViewColumnWidth(min: 620, ideal: 760)
        } detail: {
            InspectorColumnView()
                .navigationSplitViewColumnWidth(min: 300, ideal: 340)
        }
    }
}

private enum ShellPalette {
    static let appBackground = Color(red: 0.06, green: 0.07, blue: 0.10)
    static let sidebarTop = Color(red: 0.12, green: 0.14, blue: 0.18)
    static let sidebarBottom = Color(red: 0.08, green: 0.09, blue: 0.13)
    static let conversationTop = Color(red: 0.10, green: 0.11, blue: 0.16)
    static let conversationBottom = Color(red: 0.07, green: 0.08, blue: 0.12)
    static let inspectorTop = Color(red: 0.11, green: 0.13, blue: 0.18)
    static let inspectorBottom = Color(red: 0.08, green: 0.09, blue: 0.14)
    static let panelFill = Color.white.opacity(0.06)
    static let panelBorder = Color.white.opacity(0.10)
    static let panelStrongFill = Color.white.opacity(0.10)
    static let accent = Color(red: 0.35, green: 0.69, blue: 1.0)
    static let accentStrong = Color(red: 0.24, green: 0.47, blue: 0.96)
    static let success = Color(red: 0.22, green: 0.72, blue: 0.49)
    static let warning = Color(red: 0.98, green: 0.71, blue: 0.27)
    static let danger = Color(red: 0.95, green: 0.36, blue: 0.37)
    static let textPrimary = Color.white.opacity(0.96)
    static let textSecondary = Color.white.opacity(0.64)
    static let textTertiary = Color.white.opacity(0.42)
}

private struct SidebarView: View {
    @EnvironmentObject private var store: DesktopStore

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [ShellPalette.sidebarTop, ShellPalette.sidebarBottom],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            VStack(alignment: .leading, spacing: 16) {
                HStack(alignment: .firstTextBaseline) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Lumisight")
                            .font(.system(size: 28, weight: .bold, design: .rounded))
                            .foregroundStyle(ShellPalette.textPrimary)
                        Text("macOS Agent Shell")
                            .font(.system(size: 12, weight: .medium, design: .monospaced))
                            .foregroundStyle(ShellPalette.textSecondary)
                    }
                    Spacer()
                    Button(action: store.createSession) {
                        Image(systemName: "plus")
                            .font(.headline)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(ShellPalette.accentStrong)
                }

                VStack(alignment: .leading, spacing: 10) {
                    Text("Connection")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(ShellPalette.textSecondary)
                    TextField("ws://127.0.0.1:8080/ws/lumisight/agent", text: $store.socketEndpoint)
                        .font(.system(.body, design: .monospaced))
                        .textFieldStyle(.plain)
                        .foregroundStyle(ShellPalette.textPrimary)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 10)
                        .background(ShellPalette.panelStrongFill, in: RoundedRectangle(cornerRadius: 14, style: .continuous))

                    HStack {
                        StatusPill(state: store.connectionState)
                        Spacer()
                        Button("Connect", action: store.connect)
                            .buttonStyle(.borderedProminent)
                            .tint(ShellPalette.accentStrong)
                        Button("Disconnect", action: store.disconnect)
                            .buttonStyle(.bordered)
                            .tint(.white.opacity(0.3))
                    }
                }
                .padding(14)
                .background(panelBackground)

                Text("Sessions")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(ShellPalette.textSecondary)

                ScrollView {
                    LazyVStack(spacing: 10) {
                        ForEach(store.sessions) { session in
                            SessionRow(
                                session: session,
                                isSelected: session.id == store.selectedSessionID,
                                onSelect: { store.select(session) },
                                onDelete: { store.remove(session) }
                            )
                        }
                    }
                }

                Spacer(minLength: 0)
            }
            .padding(18)
        }
    }
}

private struct ConversationColumnView: View {
    @EnvironmentObject private var store: DesktopStore

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [ShellPalette.conversationTop, ShellPalette.conversationBottom],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            if let session = store.selectedSession {
                VStack(spacing: 14) {
                    HeaderBar(session: session)
                    if !session.finalOutput.isEmpty {
                        FinalOutputCard(text: session.finalOutput)
                    }
                    TranscriptView(events: session.events)
                    ComposerView(session: session)
                }
                .padding(18)
            } else {
                ContentUnavailableView("No Session", systemImage: "bolt.horizontal.circle")
                    .symbolRenderingMode(.hierarchical)
                    .foregroundStyle(ShellPalette.textSecondary)
            }
        }
    }
}

private struct InspectorColumnView: View {
    @EnvironmentObject private var store: DesktopStore

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [ShellPalette.inspectorTop, ShellPalette.inspectorBottom],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Inspector")
                        .font(.system(size: 26, weight: .bold, design: .rounded))
                        .foregroundStyle(ShellPalette.textPrimary)

                    GlassPanel(dark: true) {
                        VStack(alignment: .leading, spacing: 10) {
                            Label("Protocol", systemImage: "wave.3.right")
                                .foregroundStyle(ShellPalette.textPrimary)
                            Text(store.lastProtocolMessage)
                                .foregroundStyle(ShellPalette.textSecondary)
                            Divider().overlay(ShellPalette.panelBorder)
                            Text("State: \(store.connectionState.label)")
                                .foregroundStyle(ShellPalette.textSecondary)
                            Text("Sessions: \(store.sessionCount)")
                                .foregroundStyle(ShellPalette.textSecondary)
                        }
                    }

                    if let event = store.activeEvent {
                        GlassPanel(dark: true) {
                            VStack(alignment: .leading, spacing: 10) {
                                Text(event.title)
                                    .font(.headline)
                                    .foregroundStyle(ShellPalette.textPrimary)
                                Text(event.body)
                                    .foregroundStyle(ShellPalette.textSecondary)
                                if !event.payload.isEmpty {
                                    Divider().overlay(ShellPalette.panelBorder)
                                    ForEach(event.payload.sorted(by: { $0.key < $1.key }), id: \.key) { entry in
                                        VStack(alignment: .leading, spacing: 4) {
                                            Text(entry.key.titleShellLabel)
                                                .font(.caption2.weight(.bold))
                                                .foregroundStyle(ShellPalette.textTertiary)
                                            Text(entry.value.pretty)
                                                .font(.system(.caption, design: .monospaced))
                                                .foregroundStyle(ShellPalette.textSecondary)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    GlassPanel(dark: true) {
                        VStack(alignment: .leading, spacing: 10) {
                            Text("Connection Log")
                                .font(.headline)
                                .foregroundStyle(ShellPalette.textPrimary)
                            ForEach(store.connectionLog.prefix(12), id: \.self) { line in
                                Text(line)
                                    .font(.system(.caption, design: .monospaced))
                                    .foregroundStyle(ShellPalette.textSecondary)
                            }
                        }
                    }
                }
                .padding(18)
            }
        }
    }
}

private struct HeaderBar: View {
    let session: AgentSession

    var body: some View {
        HStack(alignment: .top) {
            VStack(alignment: .leading, spacing: 6) {
                Text(session.name)
                    .font(.system(size: 26, weight: .bold, design: .rounded))
                    .foregroundStyle(ShellPalette.textPrimary)
                Text(session.repoRoot.isEmpty ? "No repo root configured" : session.repoRoot)
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(ShellPalette.textSecondary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 6) {
                Text(session.sessionId)
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(ShellPalette.textSecondary)
                Text(session.lastStatus.titleShellLabel)
                    .font(.caption2.weight(.bold))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(ShellPalette.panelStrongFill, in: Capsule())
                    .foregroundStyle(ShellPalette.textPrimary)
            }
        }
        .padding(16)
        .background(panelBackground(cornerRadius: 22))
    }
}

private struct FinalOutputCard: View {
    let text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Final Output")
                .font(.headline)
                .foregroundStyle(ShellPalette.textPrimary)
            ScrollView {
                Text(text)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .foregroundStyle(ShellPalette.textPrimary)
                    .textSelection(.enabled)
            }
            .frame(minHeight: 90, maxHeight: 160)
        }
        .padding(16)
        .background(
            LinearGradient(
                colors: [ShellPalette.success.opacity(0.28), ShellPalette.panelFill],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            ),
            in: RoundedRectangle(cornerRadius: 22, style: .continuous)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .stroke(ShellPalette.success.opacity(0.34), lineWidth: 1)
        )
    }
}

private struct TranscriptView: View {
    let events: [SessionEvent]

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(spacing: 12) {
                    ForEach(events) { event in
                        EventCard(event: event)
                            .id(event.id)
                    }
                }
                .padding(.vertical, 4)
            }
            .onChange(of: events.count) { _, _ in
                if let last = events.last?.id {
                    withAnimation(.easeOut(duration: 0.2)) {
                        proxy.scrollTo(last, anchor: .bottom)
                    }
                }
            }
        }
    }
}

private struct ComposerView: View {
    @EnvironmentObject private var store: DesktopStore
    let session: AgentSession

    var body: some View {
        VStack(spacing: 12) {
            HStack {
                Text("Command Deck")
                    .font(.headline)
                Spacer()
                Button("Clear", action: store.clearSelectedSession)
                    .buttonStyle(.borderless)
                    .foregroundStyle(ShellPalette.textSecondary)
            }
            .foregroundStyle(ShellPalette.textPrimary)

            TextField("Repository root", text: binding(\.repoRoot))
                .font(.system(.body, design: .monospaced))
                .textFieldStyle(.plain)
                .foregroundStyle(ShellPalette.textPrimary)
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .background(ShellPalette.panelStrongFill, in: RoundedRectangle(cornerRadius: 14, style: .continuous))

            TextField("Skill path (optional)", text: binding(\.skillPath))
                .textFieldStyle(.plain)
                .foregroundStyle(ShellPalette.textPrimary)
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .background(ShellPalette.panelStrongFill, in: RoundedRectangle(cornerRadius: 14, style: .continuous))

            TextEditor(text: binding(\.questionDraft))
                .font(.system(.body, design: .rounded))
                .frame(minHeight: 96)
                .padding(10)
                .scrollContentBackground(.hidden)
                .background(ShellPalette.panelStrongFill, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                .foregroundStyle(ShellPalette.textPrimary)

            HStack(spacing: 10) {
                Picker("Task", selection: binding(\.taskType)) {
                    ForEach(AgentTaskType.allCases) { type in
                        Text(type.label).tag(type)
                    }
                }
                Picker("Run", selection: binding(\.runMode)) {
                    ForEach(AgentRunMode.allCases) { mode in
                        Text(mode.label).tag(mode)
                    }
                }
                Picker("Dialogue", selection: binding(\.dialogueMode)) {
                    ForEach(AgentDialogueMode.allCases) { mode in
                        Text(mode.label).tag(mode)
                    }
                }
                TextField("Context limit", text: binding(\.contextLimit))
                    .textFieldStyle(.plain)
                    .foregroundStyle(ShellPalette.textPrimary)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .background(ShellPalette.panelStrongFill, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .frame(width: 108)
            }
            .pickerStyle(.segmented)

            HStack {
                Toggle("RAG", isOn: binding(\.includeRagContext))
                    .toggleStyle(.switch)
                Toggle("KG", isOn: binding(\.includeKnowledgeGraphContext))
                    .toggleStyle(.switch)
                Spacer()
                Button("Resume", action: store.resumeSelectedSession)
                    .buttonStyle(.bordered)
                    .tint(.white.opacity(0.3))
                Button("Interrupt", action: store.interruptSelectedSession)
                    .buttonStyle(.bordered)
                    .tint(ShellPalette.warning)
                Button(action: store.sendPrompt) {
                    Label("Send", systemImage: "paperplane.fill")
                }
                .buttonStyle(.borderedProminent)
                .tint(ShellPalette.accentStrong)
            }
            .foregroundStyle(ShellPalette.textSecondary)
        }
        .padding(16)
        .background(panelBackground(cornerRadius: 24))
    }

    private func binding<T>(_ keyPath: WritableKeyPath<AgentSession, T>) -> Binding<T> {
        Binding(
            get: { store.selectedSession?[keyPath: keyPath] ?? session[keyPath: keyPath] },
            set: { newValue in
                guard var selected = store.selectedSession else { return }
                selected[keyPath: keyPath] = newValue
                selected.updatedAt = .now
                store.selectedSession = selected
            }
        )
    }
}

private struct SessionRow: View {
    let session: AgentSession
    let isSelected: Bool
    let onSelect: () -> Void
    let onDelete: () -> Void

    var body: some View {
        Button(action: onSelect) {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text(session.name)
                        .font(.headline)
                        .lineLimit(1)
                    Spacer()
                    if session.unreadCount > 0 {
                        Text("\(session.unreadCount)")
                            .font(.caption2.weight(.bold))
                            .padding(.horizontal, 8)
                            .padding(.vertical, 4)
                            .background(Color.white.opacity(0.14), in: Capsule())
                    }
                }
                Text(session.sessionId)
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(isSelected ? Color.white.opacity(0.76) : ShellPalette.textSecondary)
                Text(session.updatedAt.formatted(date: .omitted, time: .shortened))
                    .font(.caption)
                    .foregroundStyle(isSelected ? Color.white.opacity(0.70) : ShellPalette.textSecondary)
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                isSelected
                ? LinearGradient(colors: [ShellPalette.accentStrong, ShellPalette.accent.opacity(0.82)], startPoint: .topLeading, endPoint: .bottomTrailing)
                : LinearGradient(colors: [ShellPalette.panelStrongFill, ShellPalette.panelFill], startPoint: .topLeading, endPoint: .bottomTrailing),
                in: RoundedRectangle(cornerRadius: 18, style: .continuous)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(isSelected ? Color.white.opacity(0.18) : ShellPalette.panelBorder, lineWidth: 1)
            )
            .foregroundStyle(isSelected ? Color.white : ShellPalette.textPrimary)
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button("Delete Session", role: .destructive, action: onDelete)
        }
    }
}

private struct EventCard: View {
    let event: SessionEvent

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline) {
                Text(event.title)
                    .font(.headline)
                    .foregroundStyle(ShellPalette.textPrimary)
                Spacer()
                Text(event.createdAt.formatted(date: .omitted, time: .standard))
                    .font(.caption)
                    .foregroundStyle(ShellPalette.textSecondary)
            }
            Text(event.body.isEmpty ? " " : event.body)
                .font(event.type == "TOKEN" ? .system(.body, design: .monospaced) : .body)
                .foregroundStyle(ShellPalette.textPrimary)
                .textSelection(.enabled)
            if let toolName = event.toolName, !toolName.isEmpty {
                Label(toolName, systemImage: "wrench.and.screwdriver")
                    .font(.caption)
                    .foregroundStyle(ShellPalette.textSecondary)
            }
        }
        .padding(16)
        .background(backgroundStyle, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .stroke(cardStrokeColor, lineWidth: 1)
        )
    }

    private var backgroundStyle: LinearGradient {
        switch event.type {
        case "FINAL":
            return LinearGradient(colors: [ShellPalette.success.opacity(0.22), ShellPalette.panelFill], startPoint: .topLeading, endPoint: .bottomTrailing)
        case "ERROR":
            return LinearGradient(colors: [ShellPalette.danger.opacity(0.24), ShellPalette.panelFill], startPoint: .topLeading, endPoint: .bottomTrailing)
        case "MULTI_AGENT_SELECTED", "ORCHESTRATION_PLAN", "SUBAGENT_SPAWNED", "SUBAGENT_RESULT", "MULTI_AGENT_TASK_STATUS", "SUB_AGENT_LIFECYCLE", "MULTI_AGENT_FALLBACK":
            return LinearGradient(colors: [ShellPalette.warning.opacity(0.20), ShellPalette.panelFill], startPoint: .topLeading, endPoint: .bottomTrailing)
        case "CONTEXT_COMPRESSION", "SKILL_SELECTED", "SKILL_ROUTE", "DIALOGUE_MODE":
            return LinearGradient(colors: [ShellPalette.accent.opacity(0.18), ShellPalette.panelFill], startPoint: .topLeading, endPoint: .bottomTrailing)
        case "TOOL_CALL", "TOOL_RESULT":
            return LinearGradient(colors: [ShellPalette.accentStrong.opacity(0.18), ShellPalette.panelFill], startPoint: .topLeading, endPoint: .bottomTrailing)
        case "ASK_USER", "HUMAN_GATE":
            return LinearGradient(colors: [ShellPalette.warning.opacity(0.22), ShellPalette.panelFill], startPoint: .topLeading, endPoint: .bottomTrailing)
        default:
            return LinearGradient(colors: [ShellPalette.panelStrongFill, ShellPalette.panelFill], startPoint: .topLeading, endPoint: .bottomTrailing)
        }
    }

    private var cardStrokeColor: Color {
        switch event.type {
        case "FINAL":
            return ShellPalette.success.opacity(0.30)
        case "ERROR":
            return ShellPalette.danger.opacity(0.34)
        case "MULTI_AGENT_SELECTED", "ORCHESTRATION_PLAN", "SUBAGENT_SPAWNED", "SUBAGENT_RESULT", "MULTI_AGENT_TASK_STATUS", "SUB_AGENT_LIFECYCLE", "MULTI_AGENT_FALLBACK":
            return ShellPalette.warning.opacity(0.30)
        case "CONTEXT_COMPRESSION", "SKILL_SELECTED", "SKILL_ROUTE", "DIALOGUE_MODE":
            return ShellPalette.accent.opacity(0.24)
        case "TOOL_CALL", "TOOL_RESULT":
            return ShellPalette.accent.opacity(0.22)
        case "ASK_USER", "HUMAN_GATE":
            return ShellPalette.warning.opacity(0.32)
        default:
            return ShellPalette.panelBorder
        }
    }
}

private struct StatusPill: View {
    let state: SocketConnectionState

    var body: some View {
        Label(state.label, systemImage: icon)
            .font(.caption.weight(.semibold))
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(color.opacity(0.16), in: Capsule())
            .foregroundStyle(color)
    }

    private var color: Color {
        switch state {
        case .connected: return ShellPalette.success
        case .connecting: return ShellPalette.warning
        case .disconnected: return ShellPalette.textSecondary
        case .failed: return ShellPalette.danger
        }
    }

    private var icon: String {
        switch state {
        case .connected: return "bolt.horizontal.circle.fill"
        case .connecting: return "ellipsis.circle.fill"
        case .disconnected: return "bolt.horizontal.circle"
        case .failed: return "exclamationmark.triangle.fill"
        }
    }
}

private struct GlassPanel<Content: View>: View {
    let dark: Bool
    @ViewBuilder let content: Content

    var body: some View {
        content
            .padding(14)
            .background(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(dark ? ShellPalette.panelFill : Color.white.opacity(0.8))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(dark ? ShellPalette.panelBorder : Color.white.opacity(0.35), lineWidth: 1)
            )
    }
}

private var panelBackground: some View {
    RoundedRectangle(cornerRadius: 18, style: .continuous)
        .fill(ShellPalette.panelFill)
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(ShellPalette.panelBorder, lineWidth: 1)
        )
}

private func panelBackground(cornerRadius: CGFloat) -> some View {
    RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
        .fill(ShellPalette.panelFill)
        .overlay(
            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                .stroke(ShellPalette.panelBorder, lineWidth: 1)
        )
}
