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

private struct SidebarView: View {
    @EnvironmentObject private var store: DesktopStore

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [Color(red: 0.95, green: 0.97, blue: 0.99), Color(red: 0.89, green: 0.94, blue: 0.98)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            VStack(alignment: .leading, spacing: 16) {
                HStack(alignment: .firstTextBaseline) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Lumisight")
                            .font(.system(size: 28, weight: .bold, design: .rounded))
                        Text("macOS Agent Shell")
                            .font(.system(size: 12, weight: .medium, design: .monospaced))
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button(action: store.createSession) {
                        Image(systemName: "plus")
                            .font(.headline)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.black.opacity(0.8))
                }

                VStack(alignment: .leading, spacing: 10) {
                    Text("Connection")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)
                    TextField("ws://127.0.0.1:8080/ws/lumisight/agent", text: $store.socketEndpoint)
                        .textFieldStyle(.roundedBorder)
                        .font(.system(.body, design: .monospaced))

                    HStack {
                        StatusPill(state: store.connectionState)
                        Spacer()
                        Button("Connect", action: store.connect)
                            .buttonStyle(.borderedProminent)
                        Button("Disconnect", action: store.disconnect)
                            .buttonStyle(.bordered)
                    }
                }
                .padding(14)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))

                Text("Sessions")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)

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
                colors: [Color(red: 0.99, green: 0.98, blue: 0.96), Color(red: 0.96, green: 0.97, blue: 1.0)],
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
            }
        }
    }
}

private struct InspectorColumnView: View {
    @EnvironmentObject private var store: DesktopStore

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [Color(red: 0.09, green: 0.11, blue: 0.15), Color(red: 0.13, green: 0.16, blue: 0.22)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()

            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Inspector")
                        .font(.system(size: 26, weight: .bold, design: .rounded))
                        .foregroundStyle(.white)

                    GlassPanel(dark: true) {
                        VStack(alignment: .leading, spacing: 10) {
                            Label("Protocol", systemImage: "wave.3.right")
                                .foregroundStyle(.white)
                            Text(store.lastProtocolMessage)
                                .foregroundStyle(Color.white.opacity(0.8))
                            Divider().overlay(Color.white.opacity(0.12))
                            Text("State: \(store.connectionState.label)")
                                .foregroundStyle(Color.white.opacity(0.7))
                            Text("Sessions: \(store.sessionCount)")
                                .foregroundStyle(Color.white.opacity(0.7))
                        }
                    }

                    if let event = store.activeEvent {
                        GlassPanel(dark: true) {
                            VStack(alignment: .leading, spacing: 10) {
                                Text(event.title)
                                    .font(.headline)
                                    .foregroundStyle(.white)
                                Text(event.body)
                                    .foregroundStyle(Color.white.opacity(0.82))
                                if !event.payload.isEmpty {
                                    Divider().overlay(Color.white.opacity(0.12))
                                    ForEach(event.payload.sorted(by: { $0.key < $1.key }), id: \.key) { entry in
                                        VStack(alignment: .leading, spacing: 4) {
                                            Text(entry.key.titleShellLabel)
                                                .font(.caption2.weight(.bold))
                                                .foregroundStyle(Color.white.opacity(0.5))
                                            Text(entry.value.pretty)
                                                .font(.system(.caption, design: .monospaced))
                                                .foregroundStyle(Color.white.opacity(0.82))
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
                                .foregroundStyle(.white)
                            ForEach(store.connectionLog.prefix(12), id: \.self) { line in
                                Text(line)
                                    .font(.system(.caption, design: .monospaced))
                                    .foregroundStyle(Color.white.opacity(0.72))
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
                Text(session.repoRoot.isEmpty ? "No repo root configured" : session.repoRoot)
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(.secondary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 6) {
                Text(session.sessionId)
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(.secondary)
                Text(session.lastStatus.titleShellLabel)
                    .font(.caption2.weight(.bold))
                    .padding(.horizontal, 10)
                    .padding(.vertical, 6)
                    .background(Color.black.opacity(0.08), in: Capsule())
            }
        }
        .padding(16)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
    }
}

private struct FinalOutputCard: View {
    let text: String

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Final Output")
                .font(.headline)
            ScrollView {
                Text(text)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .textSelection(.enabled)
            }
            .frame(minHeight: 90, maxHeight: 160)
        }
        .padding(16)
        .background(
            LinearGradient(
                colors: [Color(red: 0.96, green: 0.99, blue: 0.97), Color.white],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            ),
            in: RoundedRectangle(cornerRadius: 22, style: .continuous)
        )
        .overlay(
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .stroke(Color.black.opacity(0.06), lineWidth: 1)
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
            }

            TextField("Repository root", text: binding(\.repoRoot))
                .textFieldStyle(.roundedBorder)
                .font(.system(.body, design: .monospaced))

            TextField("Skill path (optional)", text: binding(\.skillPath))
                .textFieldStyle(.roundedBorder)

            TextEditor(text: binding(\.questionDraft))
                .font(.system(.body, design: .rounded))
                .frame(minHeight: 96)
                .padding(10)
                .background(Color.white.opacity(0.82), in: RoundedRectangle(cornerRadius: 16, style: .continuous))

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
                    .textFieldStyle(.roundedBorder)
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
                Button("Interrupt", action: store.interruptSelectedSession)
                    .buttonStyle(.bordered)
                Button(action: store.sendPrompt) {
                    Label("Send", systemImage: "paperplane.fill")
                }
                .buttonStyle(.borderedProminent)
            }
        }
        .padding(16)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
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

private extension String {
    var titleShellLabel: String {
        replacingOccurrences(of: "_", with: " ")
            .split(separator: " ")
            .map { word in
                guard let first = word.first else { return "" }
                return first.uppercased() + word.dropFirst().lowercased()
            }
            .joined(separator: " ")
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
                            .background(Color.black.opacity(0.14), in: Capsule())
                    }
                }
                Text(session.sessionId)
                    .font(.system(.caption, design: .monospaced))
                    .foregroundStyle(.secondary)
                Text(session.updatedAt.formatted(date: .omitted, time: .shortened))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                isSelected
                ? LinearGradient(colors: [Color.black.opacity(0.88), Color.blue.opacity(0.72)], startPoint: .topLeading, endPoint: .bottomTrailing)
                : LinearGradient(colors: [Color.white.opacity(0.8), Color.white.opacity(0.45)], startPoint: .topLeading, endPoint: .bottomTrailing),
                in: RoundedRectangle(cornerRadius: 18, style: .continuous)
            )
            .foregroundStyle(isSelected ? Color.white : Color.primary)
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
                Spacer()
                Text(event.createdAt.formatted(date: .omitted, time: .standard))
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Text(event.body.isEmpty ? " " : event.body)
                .font(event.type == "TOKEN" ? .system(.body, design: .monospaced) : .body)
                .foregroundStyle(.primary)
                .textSelection(.enabled)
            if let toolName = event.toolName, !toolName.isEmpty {
                Label(toolName, systemImage: "wrench.and.screwdriver")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(16)
        .background(backgroundStyle, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .stroke(Color.black.opacity(0.06), lineWidth: 1)
        )
    }

    private var backgroundStyle: LinearGradient {
        switch event.type {
        case "FINAL":
            return LinearGradient(colors: [Color(red: 0.95, green: 1.0, blue: 0.95), .white], startPoint: .topLeading, endPoint: .bottomTrailing)
        case "ERROR":
            return LinearGradient(colors: [Color(red: 1.0, green: 0.94, blue: 0.94), .white], startPoint: .topLeading, endPoint: .bottomTrailing)
        case "TOOL_CALL", "TOOL_RESULT":
            return LinearGradient(colors: [Color(red: 0.97, green: 0.97, blue: 1.0), .white], startPoint: .topLeading, endPoint: .bottomTrailing)
        case "ASK_USER", "HUMAN_GATE":
            return LinearGradient(colors: [Color(red: 1.0, green: 0.98, blue: 0.92), .white], startPoint: .topLeading, endPoint: .bottomTrailing)
        default:
            return LinearGradient(colors: [Color.white.opacity(0.94), Color.white.opacity(0.72)], startPoint: .topLeading, endPoint: .bottomTrailing)
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
        case .connected: return .green
        case .connecting: return .orange
        case .disconnected: return .secondary
        case .failed: return .red
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
                    .fill(dark ? Color.white.opacity(0.08) : Color.white.opacity(0.8))
            )
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .stroke(Color.white.opacity(dark ? 0.12 : 0.35), lineWidth: 1)
            )
    }
}
