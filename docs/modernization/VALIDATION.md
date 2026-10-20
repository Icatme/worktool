# 验证记录

## 环境核查

- 2026-10-20 fork HEAD：`2105e6dde02c007f51f2e7cc51563187d227296f`。
- 任务工作区新克隆，无修改既有用户 checkout；origin 只指向 Icatme/worktool。
- 项目无 AGENTS.md / .agents/skills；用户级 .agents/skills 无 Android 专用技能。
- 发现已有 Android Studio JBR 17.0.7；JDK 25 不用于本次 Gradle 8.13。
- 已有 SDK `platforms/android-36`；尚未发现 Build Tools / SDK licenses。
  不安装系统组件、不擅自接受许可。
- fork Actions workflows API 返回空列表；本次不新增 workflow。
- 尚未运行应用、连接真实账号或请求设备权限。

后续每批补充命令、结果、准确限制和 PR 链接；未执行项不标为通过。

## 第一批：指令与文件边界

- 源码恢复点：`42cb778`，base 为方案分支 `docs/android-modernization-plan`。
- `scripts/Test-Protocol.ps1 -JavaHome <existing JDK17>`：**36 项检查通过**。
  对生产 Java policy 类直接编译执行；覆盖批次位置身份、并发重复接收、
  30 秒以上排队/暂停、满队列清理、已完成身份保留、文件名/目录/大小边界、
  HTTP/业务码成功判定。没有调用 Android API 或外部服务器。
- `review-it` 的独立 `codex review`：发现并修复旧 Kotlin 语法、满队列取消、
  清队列误淘汰已完成身份、大 base64 回执复制问题。复审结果：
  “No actionable defects remain … All 36 protocol checks passed;
  Android compilation and integration behavior were not verified.”
- `git diff --check` 通过。符号链接 OS 集成、Android Handler、真实 HTTP 回调仍待测试；
  不能把 policy 测试说成完整应用测试。
- 常规 ledger 容量 4096，完成身份保留 10 分钟；额外最多 128 个控制身份。
  清理操作不淘汰副作用命令的完成身份。不是跨进程 exactly-once，也不提供结果重放。
- GET_LOCAL_FILE 有意改变契约：只接受 `files/exports` 下人工审核/脱敏的单个
  `.txt` / `.json` 文件名，1 byte–1 MiB，不自动导出原始日志。
  上传要求 HTTP 2xx 且 JSON `code=200`；未确认的服务端协议不会假报成功。

## 构建预检

使用已校验 SHA-256 的官方 Gradle 8.13、AGP 8.13.2、已有 JBR 17.0.7，
对独立最小 Android 工程执行 assembleDebug，设置 `android.builder.sdkDownload=false`。
实际失败：`Failed to find Build Tools revision 35.0.0`。
现有 SDK platform 36 可读，但其目录不可写且无 build-tools / licenses。
没有安装组件或接受新许可；需要用户授权安装官方 `build-tools;35.0.0`，
并接受适用 [SDK 许可](https://developer.android.com/studio/terms) 后再验证完整 APK。
原始预检日志保留在任务工作区 `.tooling/preflight.txt`，未提交含本机路径的日志。
