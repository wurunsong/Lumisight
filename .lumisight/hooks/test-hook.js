#!/usr/bin/env node

const fs = require("fs");
const path = require("path");

async function readStdin() {
  const chunks = [];
  for await (const chunk of process.stdin) chunks.push(chunk);
  return Buffer.concat(chunks).toString("utf-8");
}

async function main() {
  const raw = await readStdin();
  const payload = raw ? JSON.parse(raw) : {};

  const now = new Date().toISOString();
  const line = JSON.stringify({
    marker: "HOOK_TEST_OK",
    at: now,
    hook_event_name: payload.hook_event_name || "",
    hook_point: payload.hook_point || "",
    tool_name: payload.tool_name || "",
    question: payload.question || "",
    session_id: payload.session_id || ""
  });

  const logFile = path.resolve(".lumisight/hook-test.log");
  fs.mkdirSync(path.dirname(logFile), { recursive: true });
  fs.appendFileSync(logFile, line + "\n", "utf8");

  process.stderr.write(`[test-hook] HOOK_TEST_OK ${payload.hook_event_name || payload.hook_point || "UNKNOWN"}\n`);
  process.stdout.write(JSON.stringify({ continue: true, marker: "HOOK_TEST_OK" }) + "\n");
}

main().catch((error) => {
  process.stderr.write(`[test-hook] failed: ${error?.message || String(error)}\n`);
  process.exit(1);
});
