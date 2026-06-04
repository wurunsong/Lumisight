import Foundation

enum AgentTaskType: String, Codable, CaseIterable, Identifiable {
    case chat = "CHAT"
    case bugFix = "BUG_FIX"
    case codeExplain = "CODE_EXPLAIN"

    var id: String { rawValue }
    var label: String {
        switch self {
        case .chat: return "Chat"
        case .bugFix: return "Bug Fix"
        case .codeExplain: return "Code Explain"
        }
    }
}

enum AgentRunMode: String, Codable, CaseIterable, Identifiable {
    case normal = "NORMAL"
    case plan = "PLAN"

    var id: String { rawValue }
    var label: String {
        switch self {
        case .normal: return "Normal"
        case .plan: return "Plan"
        }
    }
}

enum AgentDialogueMode: String, Codable, CaseIterable, Identifiable {
    case follow = "FOLLOW"
    case collect = "COLLECT"
    case steer = "STEER"

    var id: String { rawValue }
    var label: String {
        switch self {
        case .follow: return "Follow"
        case .collect: return "Collect"
        case .steer: return "Steer"
        }
    }
}

enum SocketConnectionState: Equatable {
    case disconnected
    case connecting
    case connected
    case failed(String)

    var label: String {
        switch self {
        case .disconnected: return "Disconnected"
        case .connecting: return "Connecting"
        case .connected: return "Connected"
        case .failed(let message): return "Failed: \(message)"
        }
    }
}

enum JSONValue: Codable, Hashable {
    case string(String)
    case number(Double)
    case bool(Bool)
    case object([String: JSONValue])
    case array([JSONValue])
    case null

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if container.decodeNil() {
            self = .null
        } else if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode(Double.self) {
            self = .number(value)
        } else if let value = try? container.decode(String.self) {
            self = .string(value)
        } else if let value = try? container.decode([String: JSONValue].self) {
            self = .object(value)
        } else if let value = try? container.decode([JSONValue].self) {
            self = .array(value)
        } else {
            throw DecodingError.dataCorruptedError(in: container, debugDescription: "Unsupported JSON value")
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case .string(let value): try container.encode(value)
        case .number(let value): try container.encode(value)
        case .bool(let value): try container.encode(value)
        case .object(let value): try container.encode(value)
        case .array(let value): try container.encode(value)
        case .null: try container.encodeNil()
        }
    }

    var pretty: String {
        switch self {
        case .string(let value): return value
        case .number(let value): return value.formatted()
        case .bool(let value): return value ? "true" : "false"
        case .null: return "null"
        case .array(let value):
            return "[" + value.map(\.pretty).joined(separator: ", ") + "]"
        case .object(let value):
            return value
                .sorted { $0.key < $1.key }
                .map { "\($0.key): \($0.value.pretty)" }
                .joined(separator: "\n")
        }
    }
}

struct WireAgentEvent: Codable {
    let type: String
    let message: String
    let toolName: String?
    let payload: [String: JSONValue]
    let traceId: String
    let sessionId: String
    let round: Int?
    let step: String?
    let status: String?
    let timestamp: Int64?
}

struct WireRunRequest: Codable {
    let taskType: String?
    let repoRoot: String?
    let question: String?
    let skillPath: String?
    let userId: String?
    let sessionId: String?
    let approveRiskyToolCall: Bool?
    let interrupt: Bool?
    let resume: Bool?
    let includeRagContext: Bool?
    let includeKnowledgeGraphContext: Bool?
    let contextLimit: Int?
    let runMode: String?
    let dialogueMode: String?
}

struct WireSocketCommand: Codable {
    let type: String
    let requestId: String
    let request: WireRunRequest?
}

struct WireSocketMessage: Codable {
    let type: String
    let requestId: String
    let event: WireAgentEvent?
    let message: String?
    let timestamp: Int64?
}

struct SessionEvent: Identifiable, Codable, Hashable {
    let id: UUID
    let type: String
    let title: String
    let body: String
    let toolName: String?
    let step: String?
    let status: String?
    let payload: [String: JSONValue]
    let createdAt: Date

    init(
        id: UUID = UUID(),
        type: String,
        title: String,
        body: String,
        toolName: String? = nil,
        step: String? = nil,
        status: String? = nil,
        payload: [String: JSONValue] = [:],
        createdAt: Date = .now
    ) {
        self.id = id
        self.type = type
        self.title = title
        self.body = body
        self.toolName = toolName
        self.step = step
        self.status = status
        self.payload = payload
        self.createdAt = createdAt
    }
}

struct AgentSession: Identifiable, Codable, Hashable {
    let id: UUID
    var name: String
    var sessionId: String
    var userId: String
    var repoRoot: String
    var questionDraft: String
    var skillPath: String
    var taskType: AgentTaskType
    var runMode: AgentRunMode
    var dialogueMode: AgentDialogueMode
    var includeRagContext: Bool
    var includeKnowledgeGraphContext: Bool
    var contextLimit: String
    var finalOutput: String
    var lastStatus: String
    var updatedAt: Date
    var unreadCount: Int
    var events: [SessionEvent]

    static func fresh(named name: String? = nil) -> AgentSession {
        AgentSession(
            id: UUID(),
            name: name ?? "New Session",
            sessionId: "session-\(UUID().uuidString.prefix(8))",
            userId: "mac-user-\(UUID().uuidString.prefix(6))",
            repoRoot: "",
            questionDraft: "",
            skillPath: "",
            taskType: .chat,
            runMode: .normal,
            dialogueMode: .follow,
            includeRagContext: false,
            includeKnowledgeGraphContext: false,
            contextLimit: "",
            finalOutput: "",
            lastStatus: "idle",
            updatedAt: .now,
            unreadCount: 0,
            events: []
        )
    }
}
