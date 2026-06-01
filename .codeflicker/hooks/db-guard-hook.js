#!/usr/bin/env node
// 示例 PreToolUse Hook：拦截高风险数据库写操作命令

async function main() {
  const chunks = [];
  for await (const chunk of process.stdin) chunks.push(chunk);
  const payload = JSON.parse(Buffer.concat(chunks).toString("utf-8"));

  const command = String(payload.tool_input?.command ?? "");
  if (!command) {
    process.stdout.write(JSON.stringify({ continue: true }) + "\n");
    return;
  }

  const WRITE_PATTERNS = /\b(INSERT|UPDATE|DELETE|DROP|CREATE|ALTER|REPLACE|TRUNCATE)\b/i;
  if (WRITE_PATTERNS.test(command)) {
    process.stderr.write("[db-guard] blocked risky write SQL command\n");
    process.exit(2);
  }

  process.stdout.write(JSON.stringify({ continue: true }) + "\n");
}

main().catch((e) => {
  process.stderr.write(String(e) + "\n");
  process.exit(1);
});
