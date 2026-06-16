import Foundation
import SwiftUI

@MainActor
final class DesktopStore: ObservableObject {
    @Published var sessions: [AgentSession]
    @Published var selectedSessionID: AgentSession.ID?
    @Published var socketEndpoint = "ws://127.0.0.1:8080/ws/lumisight/agent"
    @Published var connectionState: SocketConnectionState = .disconnected
    @Published var lastProtocolMessage = "No traffic yet"
    @Published var connectionLog: [String] = []

    private let socketClient = AgentSocketClient()
    private let defaults = UserDefaults.standard
    private let sessionsKey = "lumisight.desktop.sessions"
    private let endpointKey = "lumisight.desktop.endpoint"

    init() {
        if
            let data = defaults.data(forKey: sessionsKey),
            let decoded = try? JSONDecoder().decode([AgentSession].self, from: data),
            !decoded.isEmpty {
            self.sessions = decoded
            self.selectedSessionID = decoded.first?.id
        } else {
            let initial = AgentSession.fresh(named: "Workspace")
            self.sessions = [initial]
            self.selectedSessionID = initial.id
        }
        if let savedEndpoint = defaults.string(forKey: endpointKey), !savedEndpoint.isEmpty {
            self.socketEndpoint = savedEndpoint
        }
        wireSocketCallbacks()
    }

    var selectedSession: AgentSession? {
        get { sessions.first(where: { $0.id == selectedSessionID }) }
        set {
            guard let newValue, let index = sessions.firstIndex(where: { $0.id == newValue.id }) else { return }
            sessions[index] = newValue
            persist()
        }
    }

    var activeEvent: SessionEvent? {
        selectedSession?.events.last
    }

    var sessionCount: Int {
        sessions.count
    }

    func connect() {
        defaults.set(socketEndpoint, forKey: endpointKey)
        appendConnectionLog("Connecting to \(socketEndpoint)")
        socketClient.connect(to: socketEndpoint)
    }

    func disconnect() {
        socketClient.disconnect()
        appendConnectionLog("Disconnected")
    }

    func createSession() {
        let next = AgentSession.fresh(named: "Session \(sessions.count + 1)")
        sessions.insert(next, at: 0)
        selectedSessionID = next.id
        persist()
    }

    func clearSelectedSession() {
        guard var session = selectedSession else { return }
        session.events.removeAll()
        session.finalOutput = ""
        session.unreadCount = 0
        session.lastStatus = "idle"
        session.updatedAt = .now
        selectedSession = session
    }

    func remove(_ session: AgentSession) {
        sessions.removeAll { $0.id == session.id }
        if sessions.isEmpty {
            let next = AgentSession.fresh()
            sessions = [next]
            selectedSessionID = next.id
        } else if selectedSessionID == session.id {
            selectedSessionID = sessions.first?.id
        }
        persist()
    }

    func sendPrompt() {
        guard var session = selectedSession else { return }
        let question = session.questionDraft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !question.isEmpty else {
            appendLocalEvent("SYSTEM", title: "Missing Question", body: "请输入问题后再发送。", sessionID: session.id)
            return
        }
        let requestId = UUID().uuidString
        let contextLimit = Int(session.contextLimit.trimmingCharacters(in: .whitespacesAndNewlines))
        let command = WireSocketCommand(
            type: "START",
            requestId: requestId,
            request: WireRunRequest(
                taskType: session.taskType.rawValue,
                repoRoot: session.repoRoot.nilIfBlank,
                question: question,
                skillPath: session.skillPath.nilIfBlank,
                userId: session.userId,
                sessionId: session.sessionId,
                approveRiskyToolCall: nil,
                interrupt: false,
                resume: false,
                includeRagContext: session.includeRagContext,
                includeKnowledgeGraphContext: session.includeKnowledgeGraphContext,
                contextLimit: contextLimit,
                runMode: session.runMode.rawValue,
                dialogueMode: session.dialogueMode.rawValue
            )
        )
        session.name = question.shortened(28)
        session.lastStatus = "queued"
        session.updatedAt = .now
        session.questionDraft = ""
        selectedSession = session
        appendLocalEvent("PROMPT", title: "Prompt", body: question, sessionID: session.id)
        socketClient.send(command)
    }

    func resumeSelectedSession() {
        sendControl(type: "RESUME", interrupt: false, resume: true, title: "Resume")
    }

    func interruptSelectedSession() {
        sendControl(type: "INTERRUPT", interrupt: true, resume: false, title: "Interrupt")
    }

    func select(_ session: AgentSession) {
        selectedSessionID = session.id
        if var active = selectedSession {
            active.unreadCount = 0
            selectedSession = active
        }
    }

    private func sendControl(type: String, interrupt: Bool, resume: Bool, title: String) {
        guard let session = selectedSession else { return }
        let command = WireSocketCommand(
            type: type,
            requestId: UUID().uuidString,
            request: WireRunRequest(
                taskType: session.taskType.rawValue,
                repoRoot: session.repoRoot.nilIfBlank,
                question: nil,
                skillPath: session.skillPath.nilIfBlank,
                userId: session.userId,
                sessionId: session.sessionId,
                approveRiskyToolCall: nil,
                interrupt: interrupt,
                resume: resume,
                includeRagContext: session.includeRagContext,
                includeKnowledgeGraphContext: session.includeKnowledgeGraphContext,
                contextLimit: Int(session.contextLimit.trimmingCharacters(in: .whitespacesAndNewlines)),
                runMode: session.runMode.rawValue,
                dialogueMode: session.dialogueMode.rawValue
            )
        )
        appendLocalEvent("SYSTEM", title: title, body: "Command sent for \(session.sessionId)", sessionID: session.id)
        socketClient.send(command)
    }

    private func wireSocketCallbacks() {
        socketClient.onStateChange = { [weak self] state in
            guard let self else { return }
            self.connectionState = state
            self.lastProtocolMessage = state.label
        }
        socketClient.onErrorText = { [weak self] errorText in
            self?.appendConnectionLog(errorText)
        }
        socketClient.onMessage = { [weak self] message in
            self?.handleSocketMessage(message)
        }
    }

    private func handleSocketMessage(_ message: WireSocketMessage) {
        lastProtocolMessage = message.message ?? message.type
        appendConnectionLog("[\(message.type)] \(message.message ?? "event")")
        switch message.type {
        case "ACK":
            appendLocalEvent("ACK", title: "Accepted", body: message.message ?? "accepted", sessionID: selectedSessionID)
        case "PONG":
            appendLocalEvent("PONG", title: "Pong", body: "Heartbeat OK", sessionID: selectedSessionID)
        case "ERROR":
            appendLocalEvent("ERROR", title: "Protocol Error", body: message.message ?? "unknown error", sessionID: selectedSessionID)
        case "EVENT":
            guard let event = message.event else { return }
            ingestAgentEvent(event)
        default:
            appendConnectionLog("Ignored message type: \(message.type)")
        }
    }

    private func ingestAgentEvent(_ event: WireAgentEvent) {
        guard let targetIndex = indexForSession(event.sessionId) else {
            appendConnectionLog("Event dropped for unknown session: \(event.sessionId)")
            return
        }
        var session = sessions[targetIndex]
        let title = eventTitle(for: event)
        let timestamp = event.timestamp.map { Date(timeIntervalSince1970: TimeInterval($0) / 1000.0) } ?? .now
        let item = SessionEvent(
            type: event.type,
            title: title,
            body: event.message,
            toolName: event.toolName,
            step: event.step,
            status: event.status,
            payload: event.payload,
            createdAt: timestamp
        )
        session.events.append(item)
        session.updatedAt = .now
        session.lastStatus = statusLabel(for: event)
        if event.type == "TOKEN" {
            session.finalOutput += event.message
        } else if event.type == "FINAL" {
            session.finalOutput = event.message
        }
        if selectedSessionID != session.id {
            session.unreadCount += 1
        }
        sessions[targetIndex] = session
        persist()
    }

    private func indexForSession(_ wireSessionID: String) -> Int? {
        if let exact = sessions.firstIndex(where: { $0.sessionId == wireSessionID }) {
            return exact
        }
        if let selectedSessionID, let selectedIndex = sessions.firstIndex(where: { $0.id == selectedSessionID }) {
            return selectedIndex
        }
        return nil
    }

    private func eventTitle(for event: WireAgentEvent) -> String {
        switch event.type {
        case "LOOP_STATE": return event.step?.titleShellLabel ?? "Loop State"
        case "DIALOGUE_MODE": return "Dialogue Mode"
        case "SKILL_SELECTED": return "Skill Selected"
        case "SKILL_ROUTE": return "Skill Route"
        case "TOKEN": return "Streaming"
        case "FINAL": return "Final Answer"
        case "TOOL_CALL": return event.toolName ?? "Tool Call"
        case "TOOL_RESULT": return event.toolName ?? "Tool Result"
        case "ASK_USER": return "Ask User"
        case "HUMAN_GATE": return "Human Gate"
        case "PLAN": return "Plan"
        case "CONTEXT_COMPRESSION": return "Context Projection"
        case "MULTI_AGENT_SELECTED": return "Multi Agent Selected"
        case "ORCHESTRATION_PLAN": return "Orchestration Plan"
        case "SUBAGENT_SPAWNED": return taskTitle(prefix: "Subagent Started", event: event)
        case "SUBAGENT_RESULT": return taskTitle(prefix: "Subagent Result", event: event)
        case "MULTI_AGENT_TASK_STATUS": return taskTitle(prefix: "Task Status", event: event)
        case "SUB_AGENT_LIFECYCLE": return lifecycleTitle(event)
        case "MULTI_AGENT_FALLBACK": return "Multi Agent Fallback"
        case "VERIFY_RESULT": return "Verify"
        case "INTERRUPTED": return "Interrupted"
        case "RESUMED": return "Resumed"
        case "ERROR": return "Error"
        default: return event.type.replacingOccurrences(of: "_", with: " ")
        }
    }

    private func statusLabel(for event: WireAgentEvent) -> String {
        if event.type == "FINAL" {
            return "done"
        }
        if event.type == "TOKEN" {
            return "streaming"
        }
        if event.type == "MULTI_AGENT_SELECTED" || event.step == "MULTI_AGENT" {
            return event.status ?? "multi-agent"
        }
        if event.type == "CONTEXT_COMPRESSION" {
            return "context"
        }
        if event.type == "VERIFY_RESULT" {
            return event.status ?? "verify"
        }
        return event.status ?? event.type.lowercased()
    }

    private func taskTitle(prefix: String, event: WireAgentEvent) -> String {
        if case .string(let taskId)? = event.payload["taskId"], !taskId.isEmpty {
            return "\(prefix): \(taskId)"
        }
        return prefix
    }

    private func lifecycleTitle(_ event: WireAgentEvent) -> String {
        let action = event.payload["action"]?.pretty
        let agentId = event.payload["agentId"]?.pretty
        if let action, let agentId, !action.isEmpty, !agentId.isEmpty {
            return "Subagent \(action): \(agentId)"
        }
        return "Subagent Lifecycle"
    }

    private func appendLocalEvent(_ type: String, title: String, body: String, sessionID: AgentSession.ID?) {
        guard let sessionID, let index = sessions.firstIndex(where: { $0.id == sessionID }) else { return }
        var session = sessions[index]
        session.events.append(SessionEvent(type: type, title: title, body: body))
        session.updatedAt = .now
        sessions[index] = session
        persist()
    }

    private func appendConnectionLog(_ line: String) {
        connectionLog.insert("\(Date.now.formatted(date: .omitted, time: .standard))  \(line)", at: 0)
        if connectionLog.count > 80 {
            connectionLog = Array(connectionLog.prefix(80))
        }
    }

    private func persist() {
        if let data = try? JSONEncoder().encode(sessions) {
            defaults.set(data, forKey: sessionsKey)
        }
    }
}

private extension String {
    var nilIfBlank: String? {
        let trimmed = trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? nil : trimmed
    }

    func shortened(_ limit: Int) -> String {
        guard count > limit else { return self }
        return String(prefix(limit)) + "..."
    }
}
