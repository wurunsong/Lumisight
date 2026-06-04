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
        receiveNext()
    }

    func disconnect() {
        webSocketTask?.cancel(with: .normalClosure, reason: nil)
        webSocketTask = nil
        if case .connecting = state {
            state = .disconnected
        } else if case .connected = state {
            state = .disconnected
        }
    }

    func send(_ command: WireSocketCommand) {
        guard let task = webSocketTask else {
            onErrorText?("Socket is not connected")
            return
        }
        do {
            let data = try encoder.encode(command)
            guard let string = String(data: data, encoding: .utf8) else {
                onErrorText?("Failed to encode command as UTF-8")
                return
            }
            task.send(.string(string)) { [weak self] error in
                Task { @MainActor in
                    if let error {
                        self?.state = .failed(error.localizedDescription)
                    }
                }
            }
        } catch {
            onErrorText?("Encode failed: \(error.localizedDescription)")
        }
    }

    private func receiveNext() {
        webSocketTask?.receive { [weak self] result in
            Task { @MainActor in
                guard let self else { return }
                switch result {
                case .failure(let error):
                    if case .disconnected = self.state {
                        return
                    }
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
                    self.receiveNext()
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
            if case .failed = self.state {
                return
            }
            self.state = .disconnected
        }
    }
}
