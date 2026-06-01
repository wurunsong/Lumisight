# Agent 协议契约

## 1. 统一执行语义
- Agent 核心编排与传输协议解耦。
- 无论是 SSE 还是 WebSocket，最终都会走同一条 `AgentRequest -> Flux<AgentEvent>` 执行链路。
- 会话调度语义（`FOLLOW/COLLECT/STEER`、`HUMAN_GATE`、`resume`、`interrupt`）在各协议下保持一致。

## 2. SSE 协议

### 2.1 接口
- `POST /api/lumisight/agent/stream`
- `Content-Type: application/json`
- 响应类型：`text/event-stream`

### 2.2 请求体（`AgentRunRequest`）
```json
{
  "taskType": "CHAT",
  "repoRoot": "/path/to/repo",
  "question": "请继续",
  "skillPath": "skill-id",
  "sessionId": "s-1",
  "approveRiskyToolCall": false,
  "interrupt": false,
  "resume": false,
  "includeRagContext": true,
  "includeKnowledgeGraphContext": false,
  "contextLimit": 5,
  "runMode": "NORMAL",
  "dialogueMode": "FOLLOW"
}
```

### 2.3 事件格式
- SSE `event` 名称：`AgentEvent.type`
- SSE `data` 内容：序列化后的 `AgentEvent` JSON

## 3. WebSocket 协议

### 3.1 连接地址
- `ws(s)://<host>/ws/lumisight/agent`

### 3.2 入站消息（`WsAgentCommand`）
```json
{
  "type": "START",
  "requestId": "req-1",
  "request": {
    "taskType": "CHAT",
    "sessionId": "s-1",
    "question": "你好",
    "dialogueMode": "FOLLOW",
    "runMode": "NORMAL"
  }
}
```

### 3.3 命令类型
- `START`：发起新一轮执行
- `RESUME`：恢复会话
- `INTERRUPT`：中断会话
- `PING`：心跳探测

### 3.4 出站消息（`WsAgentMessage`）
```json
{
  "type": "EVENT",
  "requestId": "req-1",
  "event": { "type": "TOKEN", "message": "..." },
  "message": null,
  "timestamp": 1710000000000
}
```

### 3.5 出站类型
- `ACK`：命令已受理
- `EVENT`：正常业务事件（`AgentEvent`）
- `PONG`：`PING` 的响应
- `ERROR`：协议或运行时错误

## 4. 会话超时与清理策略
- `WAITING_USER` 与 `WAITING_GATE` 受 TTL 控制。
- 过期等待会话会由定时任务自动清理。
- 默认 TTL：
  - `WAITING_USER`：900 秒
  - `WAITING_GATE`：1800 秒

## 5. WebSocket 治理策略
- 连接数上限、消息大小、允许来源、每分钟消息速率都可配置。
- 超过速率限制时，服务端会先发送 `ERROR`，随后以策略违规关闭连接。
