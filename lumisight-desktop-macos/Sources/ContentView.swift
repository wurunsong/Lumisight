import SwiftUI
import AppKit

struct ContentView: View {
    @EnvironmentObject private var store: DesktopStore

    var body: some View {
        NavigationSplitView {
            SidebarView()
                .navigationSplitViewColumnWidth(min: 260, ideal: 290)
        } content: {
            ConversationColumnView()
                .navigationSplitViewColumnWidth(min: 700, ideal: 860)
        } detail: {
            InspectorColumnView()
                .navigationSplitViewColumnWidth(min: 230, ideal: 260)
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

            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .top, spacing: 12) {
                    ZStack {
                        RoundedRectangle(cornerRadius: 18, style: .continuous)
                            .fill(
                                LinearGradient(
                                    colors: [ShellPalette.accentStrong, ShellPalette.accent.opacity(0.75)],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                )
                            )
                        Image(systemName: "sparkles.rectangle.stack.fill")
                            .font(.system(size: 20, weight: .semibold))
                            .foregroundStyle(.white.opacity(0.95))
                    }
                    .frame(width: 48, height: 48)

                    VStack(alignment: .leading, spacing: 6) {
                        Text("Lumisight")
                            .font(.system(size: 24, weight: .bold, design: .rounded))
                            .foregroundStyle(ShellPalette.textPrimary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.85)
                        Text("Native agent console for local sessions")
                            .font(.system(size: 10, weight: .medium, design: .monospaced))
                            .foregroundStyle(ShellPalette.textSecondary)
                    }

                    Spacer(minLength: 8)

                    Button(action: store.createSession) {
                        Label("New", systemImage: "plus")
                    }
                    .labelStyle(.iconOnly)
                    .buttonStyle(.borderedProminent)
                    .tint(ShellPalette.accentStrong)
                }

                GlassPanel(dark: true) {
                    VStack(alignment: .leading, spacing: 14) {
                        HStack {
                            Label("Connection", systemImage: "point.3.connected.trianglepath.dotted")
                                .font(.headline)
                                .foregroundStyle(ShellPalette.textPrimary)
                            Spacer()
                            StatusPill(state: store.connectionState)
                        }

                        TextField("ws://127.0.0.1:8080/ws/lumisight/agent", text: $store.socketEndpoint)
                            .font(.system(.body, design: .monospaced))
                            .textFieldStyle(.plain)
                            .foregroundStyle(ShellPalette.textPrimary)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 10)
                            .background(ShellPalette.panelStrongFill, in: RoundedRectangle(cornerRadius: 14, style: .continuous))

                        Text("Connect the local agent first, then send commands from any session.")
                            .font(.caption)
                            .foregroundStyle(ShellPalette.textSecondary)

                        HStack(spacing: 10) {
                            Button("Connect", action: store.connect)
                                .buttonStyle(.borderedProminent)
                                .tint(ShellPalette.accentStrong)
                            Button("Disconnect", action: store.disconnect)
                                .buttonStyle(.bordered)
                                .tint(.white.opacity(0.3))
                            Spacer()
                        }
                    }
                }

                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Sessions")
                            .font(.headline)
                            .foregroundStyle(ShellPalette.textPrimary)
                        Text("\(store.sessionCount) active local session\(store.sessionCount == 1 ? "" : "s")")
                            .font(.caption)
                            .foregroundStyle(ShellPalette.textSecondary)
                    }
                    Spacer()
                }

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
            .padding(14)
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
                    WorkspaceStageView(session: session)
                    ComposerView(session: session)
                }
                .padding(14)
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
                VStack(alignment: .leading, spacing: 12) {
                    Text("Inspector")
                        .font(.system(size: 22, weight: .bold, design: .rounded))
                        .foregroundStyle(ShellPalette.textPrimary)

                    GlassPanel(dark: true) {
                        VStack(alignment: .leading, spacing: 10) {
                            Label("Connection", systemImage: "wave.3.right")
                                .font(.headline)
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

                    if let session = store.selectedSession {
                        GlassPanel(dark: true) {
                            VStack(alignment: .leading, spacing: 10) {
                                Text("Selected Session")
                                    .font(.headline)
                                    .foregroundStyle(ShellPalette.textPrimary)
                                LabeledMetaRow(label: "Name", value: session.name)
                                LabeledMetaRow(label: "Session ID", value: session.sessionId, monospaced: true)
                                LabeledMetaRow(
                                    label: "Repository",
                                    value: session.repoRoot.isEmpty ? "Not configured yet" : session.repoRoot,
                                    monospaced: !session.repoRoot.isEmpty
                                )
                                LabeledMetaRow(label: "Mode", value: "\(session.taskType.label) / \(session.runMode.label)")
                                LabeledMetaRow(label: "Dialogue", value: session.dialogueMode.label)
                            }
                        }
                    }

                    if let event = store.activeEvent {
                        GlassPanel(dark: true) {
                            VStack(alignment: .leading, spacing: 10) {
                                Text("Latest Event")
                                    .font(.headline)
                                    .foregroundStyle(ShellPalette.textPrimary)
                                Divider().overlay(ShellPalette.panelBorder)
                                Text(event.title)
                                    .font(.subheadline.weight(.semibold))
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
                    } else {
                        GlassPanel(dark: true) {
                            VStack(alignment: .leading, spacing: 10) {
                                Text("Latest Event")
                                    .font(.headline)
                                    .foregroundStyle(ShellPalette.textPrimary)
                                Text("No session traffic yet. Connect the socket and send a command to start the live trace.")
                                    .foregroundStyle(ShellPalette.textSecondary)
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
                .padding(14)
            }
        }
    }
}

private struct HeaderBar: View {
    let session: AgentSession

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 6) {
                    Text(session.name)
                        .font(.system(size: 22, weight: .bold, design: .rounded))
                        .foregroundStyle(ShellPalette.textPrimary)
                    Text("Focused workspace for one local agent session")
                        .font(.caption)
                        .foregroundStyle(ShellPalette.textSecondary)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 8) {
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

            HStack(spacing: 8) {
                HeaderMetaPill(
                    title: "Repository",
                    value: session.repoRoot.isEmpty ? "Not configured" : session.repoRoot,
                    monospaced: !session.repoRoot.isEmpty
                )
                HeaderMetaPill(title: "Task", value: session.taskType.label)
                HeaderMetaPill(title: "Mode", value: session.runMode.label)
            }
        }
        .padding(14)
        .background(panelBackground(cornerRadius: 20))
    }
}

private struct WorkspaceStageView: View {
    let session: AgentSession

    var body: some View {
        GlassPanel(dark: true) {
            VStack(alignment: .leading, spacing: 12) {
                HStack(alignment: .firstTextBaseline) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(session.events.isEmpty ? "Session Canvas" : "Live Session Feed")
                            .font(.headline)
                            .foregroundStyle(ShellPalette.textPrimary)
                        Text(session.events.isEmpty ? "Start with a repo root and a clear instruction. The live trace will appear here." : "Events, tool calls and final output stay in one scrollable timeline.")
                            .font(.caption)
                            .foregroundStyle(ShellPalette.textSecondary)
                    }
                    Spacer()
                    if !session.finalOutput.isEmpty {
                        Label("Final answer ready", systemImage: "checkmark.seal.fill")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(ShellPalette.success)
                    }
                }

                if !session.finalOutput.isEmpty {
                    FinalOutputCard(text: session.finalOutput)
                }

                if session.events.isEmpty {
                    EmptyConversationState(session: session)
                } else {
                    TranscriptView(events: session.events)
                        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                        .frame(minHeight: 220)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
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
    @State private var promptDraft = ""

    var body: some View {
        GlassPanel(dark: true) {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Command Deck")
                            .font(.headline)
                        Text("Configure the workspace once, then iterate from the same session.")
                            .font(.caption)
                            .foregroundStyle(ShellPalette.textSecondary)
                    }
                    Spacer()
                    Button("Clear", action: clearSession)
                        .buttonStyle(.borderless)
                        .foregroundStyle(ShellPalette.textSecondary)
                }
                .foregroundStyle(ShellPalette.textPrimary)

                HStack(alignment: .top, spacing: 12) {
                    LabeledTextField(
                        title: "Repository Root",
                        placeholder: "/Users/you/project",
                        text: binding(\.repoRoot),
                        monospaced: true
                    )
                    LabeledTextField(
                        title: "Skill Path",
                        placeholder: "Optional skill file or folder",
                        text: binding(\.skillPath),
                        monospaced: true
                    )
                }

                VStack(alignment: .leading, spacing: 8) {
                    Text("Prompt")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(ShellPalette.textSecondary)
                    ZStack(alignment: .topLeading) {
                        NativePromptEditor(text: $promptDraft)
                            .frame(minHeight: 104)

                        if promptDraft.isEmpty {
                            Text("Ask the agent what to do in this session")
                                .font(.system(.body, design: .rounded))
                                .foregroundStyle(ShellPalette.textTertiary)
                                .padding(.horizontal, 13)
                                .padding(.vertical, 12)
                                .allowsHitTesting(false)
                        }
                    }
                    .background(ShellPalette.panelStrongFill, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                }

                HStack(alignment: .top, spacing: 10) {
                    CompactPicker(title: "Task") {
                        Picker("Task", selection: binding(\.taskType)) {
                            ForEach(AgentTaskType.allCases) { type in
                                Text(type.label).tag(type)
                            }
                        }
                        .labelsHidden()
                        .pickerStyle(.segmented)
                    }
                    CompactPicker(title: "Run") {
                        Picker("Run", selection: binding(\.runMode)) {
                            ForEach(AgentRunMode.allCases) { mode in
                                Text(mode.label).tag(mode)
                            }
                        }
                        .labelsHidden()
                        .pickerStyle(.segmented)
                    }
                    CompactPicker(title: "Dialogue") {
                        Picker("Dialogue", selection: binding(\.dialogueMode)) {
                            ForEach(AgentDialogueMode.allCases) { mode in
                                Text(mode.label).tag(mode)
                            }
                        }
                        .labelsHidden()
                        .pickerStyle(.segmented)
                    }
                    TextField("Limit", text: binding(\.contextLimit))
                        .textFieldStyle(.plain)
                        .font(.system(.body, design: .monospaced))
                        .foregroundStyle(ShellPalette.textPrimary)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 10)
                        .background(ShellPalette.panelStrongFill, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .frame(width: 92)
                }

                HStack(spacing: 12) {
                    Toggle("RAG", isOn: binding(\.includeRagContext))
                        .toggleStyle(.switch)
                    Toggle("KG", isOn: binding(\.includeKnowledgeGraphContext))
                        .toggleStyle(.switch)

                    Spacer(minLength: 0)

                    Button("Resume", action: store.resumeSelectedSession)
                        .buttonStyle(.bordered)
                        .tint(.white.opacity(0.3))
                    Button("Interrupt", action: store.interruptSelectedSession)
                        .buttonStyle(.bordered)
                        .tint(ShellPalette.warning)
                    Button(action: sendPrompt) {
                        Label("Send", systemImage: "paperplane.fill")
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(ShellPalette.accentStrong)
                }
                .foregroundStyle(ShellPalette.textSecondary)
            }
        }
        .onAppear {
            promptDraft = session.questionDraft
        }
        .onChange(of: session.id) { _, _ in
            promptDraft = session.questionDraft
        }
    }

    private func sendPrompt() {
        store.updateSelectedSession(\.questionDraft, value: promptDraft)
        if store.sendPrompt() {
            promptDraft = ""
        }
    }

    private func clearSession() {
        store.clearSelectedSession()
        promptDraft = ""
    }

    private func binding<T>(_ keyPath: WritableKeyPath<AgentSession, T>) -> Binding<T> {
        Binding(
            get: {
                guard let index = store.selectedSessionIndex else { return session[keyPath: keyPath] }
                return store.sessions[index][keyPath: keyPath]
            },
            set: { newValue in
                store.updateSelectedSession(keyPath, value: newValue)
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
                HStack {
                    Text(session.repoRoot.isEmpty ? "No workspace" : "Workspace ready")
                        .font(.caption)
                    Spacer()
                    Text(session.updatedAt.formatted(date: .omitted, time: .shortened))
                        .font(.caption)
                }
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

private struct EmptyConversationState: View {
    let session: AgentSession

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .top, spacing: 14) {
                ZStack {
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .fill(
                            LinearGradient(
                                colors: [ShellPalette.accent.opacity(0.28), ShellPalette.panelFill],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            )
                        )
                    Image(systemName: "terminal")
                        .font(.system(size: 28, weight: .semibold))
                        .foregroundStyle(ShellPalette.accent)
                }
                .frame(width: 68, height: 68)

                VStack(alignment: .leading, spacing: 8) {
                    Text("Ready to start a real agent run")
                        .font(.system(size: 18, weight: .bold, design: .rounded))
                        .foregroundStyle(ShellPalette.textPrimary)
                    Text("This canvas is intentionally empty until the session has a workspace and a prompt. Once you send a command, event traces, tool calls and the final answer will stream into this area.")
                        .font(.caption)
                        .foregroundStyle(ShellPalette.textSecondary)
                }
            }

            LazyVGrid(columns: [GridItem(.adaptive(minimum: 170), spacing: 10)], spacing: 10) {
                HintCard(
                    title: "1. Set the repository",
                    description: session.repoRoot.isEmpty ? "Point the session at the repo you want the agent to work on." : "Workspace path is set. You can refine it any time from Command Deck.",
                    accent: session.repoRoot.isEmpty ? ShellPalette.warning : ShellPalette.success
                )
                HintCard(
                    title: "2. Choose the mode",
                    description: "Keep simple requests in Chat / Normal. Switch to Plan or Multi Agent only when the task really needs structure.",
                    accent: ShellPalette.accent
                )
                HintCard(
                    title: "3. Send a sharp prompt",
                    description: "Ask for a concrete task, bug fix or explanation. The live feed below will become your trace console.",
                    accent: ShellPalette.accentStrong
                )
            }
        }
        .frame(maxWidth: .infinity, minHeight: 210, alignment: .topLeading)
        .padding(12)
        .background(
            LinearGradient(
                colors: [Color.white.opacity(0.02), Color.white.opacity(0.01)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            ),
            in: RoundedRectangle(cornerRadius: 24, style: .continuous)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .stroke(ShellPalette.panelBorder, style: StrokeStyle(lineWidth: 1, dash: [8, 10]))
        )
    }
}

private struct HintCard: View {
    let title: String
    let description: String
    let accent: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(ShellPalette.textPrimary)
            Text(description)
                .font(.caption)
                .foregroundStyle(ShellPalette.textSecondary)
        }
        .frame(maxWidth: .infinity, minHeight: 76, alignment: .topLeading)
        .padding(12)
        .background(
            LinearGradient(
                colors: [accent.opacity(0.18), ShellPalette.panelFill],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            ),
            in: RoundedRectangle(cornerRadius: 18, style: .continuous)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(accent.opacity(0.24), lineWidth: 1)
        )
    }
}

private struct HeaderMetaPill: View {
    let title: String
    let value: String
    var monospaced = false

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(title.uppercased())
                .font(.system(size: 10, weight: .bold, design: .rounded))
                .foregroundStyle(ShellPalette.textTertiary)
            Text(value)
                .font(monospaced ? .system(size: 11, weight: .medium, design: .monospaced) : .system(size: 12, weight: .medium))
                .foregroundStyle(ShellPalette.textPrimary)
                .lineLimit(1)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(ShellPalette.panelStrongFill, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}

private struct LabeledTextField: View {
    let title: String
    let placeholder: String
    @Binding var text: String
    var monospaced = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(ShellPalette.textSecondary)
            TextField(placeholder, text: $text)
                .font(monospaced ? .system(.body, design: .monospaced) : .body)
                .textFieldStyle(.plain)
                .foregroundStyle(ShellPalette.textPrimary)
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .background(ShellPalette.panelStrongFill, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
        .frame(maxWidth: .infinity)
    }
}

private struct NativePromptEditor: NSViewRepresentable {
    @Binding var text: String

    func makeCoordinator() -> Coordinator {
        Coordinator(text: $text)
    }

    func makeNSView(context: Context) -> FocusableTextView {
        let textView = FocusableTextView()
        textView.delegate = context.coordinator
        textView.string = text
        textView.isEditable = true
        textView.isSelectable = true
        textView.allowsUndo = true
        textView.isRichText = false
        textView.importsGraphics = false
        textView.drawsBackground = false
        textView.textColor = NSColor.white.withAlphaComponent(0.96)
        textView.insertionPointColor = NSColor.controlAccentColor
        textView.font = NSFont.systemFont(ofSize: 15)
        textView.textContainerInset = NSSize(width: 12, height: 10)
        textView.textContainer?.widthTracksTextView = true
        textView.textContainer?.heightTracksTextView = false
        textView.textContainer?.containerSize = NSSize(
            width: CGFloat.greatestFiniteMagnitude,
            height: CGFloat.greatestFiniteMagnitude
        )
        textView.isHorizontallyResizable = false
        textView.isVerticallyResizable = true
        context.coordinator.textView = textView
        return textView
    }

    func updateNSView(_ textView: FocusableTextView, context: Context) {
        guard let textView = context.coordinator.textView else { return }
        if textView.string != text {
            textView.string = text
        }
    }

    final class Coordinator: NSObject, NSTextViewDelegate {
        @Binding private var text: String
        weak var textView: NSTextView?

        init(text: Binding<String>) {
            self._text = text
        }

        func textDidChange(_ notification: Notification) {
            guard let textView = notification.object as? NSTextView else { return }
            text = textView.string
        }
    }

    final class FocusableTextView: NSTextView {
        override var acceptsFirstResponder: Bool { true }

        override func mouseDown(with event: NSEvent) {
            window?.makeFirstResponder(self)
            super.mouseDown(with: event)
        }
    }
}

private struct CompactPicker<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(ShellPalette.textSecondary)
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct PickerCard<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title)
                .font(.caption.weight(.semibold))
                .foregroundStyle(ShellPalette.textSecondary)
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(ShellPalette.panelFill)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .stroke(ShellPalette.panelBorder, lineWidth: 1)
        )
    }
}

private struct LabeledMetaRow: View {
    let label: String
    let value: String
    var monospaced = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(.caption2.weight(.bold))
                .foregroundStyle(ShellPalette.textTertiary)
            Text(value)
                .font(monospaced ? .system(.caption, design: .monospaced) : .caption)
                .foregroundStyle(ShellPalette.textSecondary)
                .textSelection(.enabled)
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
