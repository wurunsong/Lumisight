# Lumisight Desktop Mac

这是一个只面向 `macOS` 的 Lumisight 原生客户端壳子，使用 `SwiftUI` 编写，默认连接本地运行的 Agent WebSocket：

- 默认地址：`ws://127.0.0.1:8080/ws/lumisight/agent`
- 推荐启动方式：先打包成可双击打开的 `.app`

```bash
cd lumisight-desktop-macos
./scripts/package-app.sh
```

打包完成后，直接在 Finder 双击 `lumisight-desktop-macos/dist/Lumisight.app` 即可启动。

也可以从仓库根目录一键打包：

```bash
./scripts/package-mac-app.sh
```

开发调试时仍然可以继续用 `swift run`：

```bash
cd lumisight-desktop-macos
swift run
```

第一版能力：
- 会话列表
- WebSocket 连接 / 断开
- 按会话发送 Agent 命令
- `NORMAL / PLAN / MULTI_AGENT` 运行模式选择
- 中断 / 恢复
- 实时事件流展示，包括上下文投影、多 Agent 编排、子任务状态、验证结果和工具调用
- 最终输出汇总
- 右侧 Inspector 查看当前状态与最近事件

说明：
- 当前更像“漂亮的本地壳子 + 调试客户端”，还不是完整产品。
- 如果后面你要继续做成真正可分发的 macOS App，可以再补：
  - Xcode 工程与签名
  - 菜单栏与全局快捷键
  - 多窗口 / 多会话 live subscription
  - 本地文件拖拽
  - 设置页、登录态、自动更新

当前 UI TODO：
- 当前已经收敛为深色主题，后续可继续补更完整的设置页、会话历史和分发能力。
