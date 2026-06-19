#!/usr/bin/env python3
import json
import sys


PROTOCOL_VERSION = "2024-11-05"


def respond(request_id, result=None, error=None):
    payload = {
        "jsonrpc": "2.0",
        "id": request_id,
    }
    if error is not None:
        payload["error"] = error
    else:
        payload["result"] = result or {}
    print(json.dumps(payload, ensure_ascii=False), flush=True)


def handle(request):
    method = request.get("method")
    request_id = request.get("id")

    if method == "initialize":
        respond(request_id, {
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {
                "tools": {}
            },
            "serverInfo": {
                "name": "lumisight-local-echo-mcp",
                "version": "0.1.0"
            }
        })
        return

    if method == "notifications/initialized":
        return

    if method == "tools/list":
        respond(request_id, {
            "tools": [
                {
                    "name": "echo",
                    "description": "Echo back the input text. This tool is only for validating the Lumisight external MCP plumbing.",
                    "inputSchema": {
                        "type": "object",
                        "properties": {
                            "text": {
                                "type": "string",
                                "description": "Text to echo back"
                            }
                        },
                        "required": ["text"]
                    }
                }
            ]
        })
        return

    if method == "tools/call":
        params = request.get("params") or {}
        arguments = params.get("arguments") or {}
        text = str(arguments.get("text", ""))
        respond(request_id, {
            "content": [
                {
                    "type": "text",
                    "text": "Lumisight local MCP echo: " + text
                }
            ],
            "isError": False
        })
        return

    respond(request_id, error={
        "code": -32601,
        "message": "Method not found: " + str(method)
    })


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            handle(json.loads(line))
        except Exception as exc:
            respond(None, error={
                "code": -32603,
                "message": str(exc)
            })


if __name__ == "__main__":
    main()
