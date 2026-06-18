import Foundation

@MainActor
final class AgentSocketClient: NSObject {
    var onStateChange: ((SocketConnectionState) -> Void)?
    var onMessage: ((WireSocketMessage) -> Void)?
    var onErrorText: ((String) -> Void)?

    private var webSocketTask: URLSessionWebSocketTask?
    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.default
        configuration.waitsForConnectivity = false
        return URLSession(configuration: configuration, delegate: self, delegateQueue: .main)
    }()
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    private(set) var state: SocketConnectionState = .disconnected {
        didSet { onStateChange?(state) }
    }

    func connect(to endpoint: String) {
        guard let url = URL(string: endpoint) else {
            state = .failed("Invalid WebSocket URL")
            return
        }
        disconnect()
        state = .connecting
        let task = session.webSocketTask(with: url)
        webSocketTask = task
        task.resume()
        receiveNext(on: task)
    }

    func disconnect() {
        webSocketTask?.cancel(with: .normalClosure, reason: nil)
        webSocketTask = nil
        if case .disconnected = state {
            return
        } else {
            state = .disconnected
        }
    }

    func send(_ command: WireSocketCommand) -> Bool {
        guard case .connected = state, let task = webSocketTask else {
            onErrorText?("Socket is not connected")
            return false
        }
        return sendNow(command, task: task)
    }

    private func sendNow(_ command: WireSocketCommand, task: URLSessionWebSocketTask) -> Bool {
        do {
            let data = try encoder.encode(command)
            guard let string = String(data: data, encoding: .utf8) else {
                onErrorText?("Failed to encode command as UTF-8")
                return false
            }
            task.send(.string(string)) { [weak self] error in
                Task { @MainActor in
                    guard let self, self.isCurrent(task) else { return }
                    if let error {
                        self.webSocketTask = nil
                        self.onErrorText?("Send failed: \(error.localizedDescription)")
                        self.state = .failed(error.localizedDescription)
                    }
                }
            }
            return true
        } catch {
            onErrorText?("Encode failed: \(error.localizedDescription)")
            return false
        }
    }

    private func isCurrent(_ task: URLSessionWebSocketTask) -> Bool {
        guard let current = webSocketTask else { return false }
        return current === task
    }

    private func receiveNext(on task: URLSessionWebSocketTask) {
        task.receive { [weak self] result in
            Task { @MainActor in
                guard let self else { return }
                guard self.isCurrent(task) else { return }
                switch result {
                case .failure(let error):
                    if case .disconnected = self.state {
                        return
                    }
                    self.webSocketTask = nil
                    self.onErrorText?("Receive failed: \(error.localizedDescription)")
                    self.state = .failed(error.localizedDescription)
                case .success(let message):
                    switch message {
                    case .string(let text):
                        self.handle(text: text)
                    case .data(let data):
                        self.handle(data: data)
                    @unknown default:
                        self.onErrorText?("Received unsupported WebSocket message")
                    }
                    self.receiveNext(on: task)
                }
            }
        }
    }

    private func handle(text: String) {
        guard let data = text.data(using: .utf8) else {
            onErrorText?("Failed to decode text frame")
            return
        }
        handle(data: data)
    }

    private func handle(data: Data) {
        do {
            let message = try decoder.decode(WireSocketMessage.self, from: data)
            onMessage?(message)
        } catch {
            onErrorText?("Decode failed: \(error.localizedDescription)")
        }
    }
}

extension AgentSocketClient: URLSessionWebSocketDelegate {
    nonisolated func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didOpenWithProtocol protocol: String?
    ) {
        Task { @MainActor in
            guard self.isCurrent(webSocketTask) else { return }
            self.state = .connected
        }
    }

    nonisolated func urlSession(
        _ session: URLSession,
        webSocketTask: URLSessionWebSocketTask,
        didCloseWith closeCode: URLSessionWebSocketTask.CloseCode,
        reason: Data?
    ) {
        Task { @MainActor in
            guard self.isCurrent(webSocketTask) else { return }
            if case .failed = self.state {
                return
            }
            self.webSocketTask = nil
            let reasonText = reason.flatMap { String(data: $0, encoding: .utf8) } ?? ""
            if !reasonText.isEmpty {
                self.onErrorText?("Socket closed: \(closeCode.rawValue) \(reasonText)")
            } else {
                self.onErrorText?("Socket closed: \(closeCode.rawValue)")
            }
            self.state = .disconnected
        }
    }
}
