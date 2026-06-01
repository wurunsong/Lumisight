# Agent Protocol Contract

## 1. Shared Execution Semantics
- Core orchestration is protocol-agnostic.
- Both SSE and WebSocket eventually execute the same `AgentRequest -> Flux<AgentEvent>` pipeline.
- Session scheduling semantics (`FOLLOW/COLLECT/STEER`, `HUMAN_GATE`, `resume`, `interrupt`) are identical across transports.

## 2. SSE Contract

### Endpoint
- `POST /api/lumisight/agent/stream`
- `Content-Type: application/json`
- Response: `text/event-stream`

### Request Body (`AgentRunRequest`)
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

### SSE Event
- Event name: `event.type`
- Data: serialized `AgentEvent`

## 3. WebSocket Contract

### Endpoint
- `ws(s)://<host>/ws/lumisight/agent`

### Inbound (`WsAgentCommand`)
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

### Command Types
- `START`: start a new run
- `RESUME`: resume an existing session
- `INTERRUPT`: interrupt an existing session
- `PING`: keepalive probe

### Outbound (`WsAgentMessage`)
```json
{
  "type": "EVENT",
  "requestId": "req-1",
  "event": { "type": "TOKEN", "message": "..." },
  "message": null,
  "timestamp": 1710000000000
}
```

### Outbound Types
- `ACK`: command accepted
- `EVENT`: normal `AgentEvent`
- `PONG`: response to `PING`
- `ERROR`: protocol/runtime failure

## 4. Session Timeout and Cleanup
- `WAITING_USER` and `WAITING_GATE` are TTL-controlled.
- Expired waiting sessions are cleaned automatically by scheduled cleanup job.
- Default TTL:
  - waiting user: 900s
  - waiting gate: 1800s

## 5. WebSocket Governance
- Connection limit, message size, allowed origins, and per-minute message rate are configurable.
- On rate-limit exceed, server sends `ERROR` and closes with policy violation.

