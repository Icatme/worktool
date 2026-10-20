# 验证记录

## 最终状态（2026-10-20）

本地验证通过；尚未执行 Android 设备/企微账号验收。产物仅用于检查，均未签名、未分发。
结构化证据和完整 SHA-256 见 [validation-results.json](validation-results.json)。

| 检查 | 结果 | 范围 |
| --- | --- | --- |
| `:app:assembleDebug` | 通过 | 完整三模块编译、资源、ViewBinding、Manifest、DEX、未签名 APK |
| `:app:assembleRelease` | 通过 | 应用级 R8 开启；未签名 APK；未使用生产签名 |
| `:app:testDebugUnitTest` | 7 个方法通过，0 失败/跳过 | 6 项 localhost HTTP 测试 + 1 项协议包装测试，后者含 36 项检查 |
| `:verification:test` | 同套 7 个方法通过 | 独立 JVM 模块；不能累计为 14 项不同测试 |
| 三个模块 `lintDebug` | 0 Error / Fatal | app 247、baselibrary 10、floatwindow 31 条 Warning，未关闭错误检查 |
| 源码契约脚本 | 26 项通过 | XML、入口、FGS、receiver、provider、单投影创建点；不是设备测试 |
| 依赖解析和 inventory | 通过 | 75 个运行时工件、SHA-256 和许可缺项记录 |
| 最终 APK native / ZIP | Debug、Release 均无 `.so`；16KB ZIP 对齐通过 | 无 ELF 可验；不是 16KB 设备实测 |
| 签名检查 | 两者均未签名，符合本轮要求 | apksigner 返回缺少签名；未安装/分发 |
| 独立代码审查 | 发现问题均修复，最后聚焦复审无可执行缺陷 | 详细范围见下文 |
| Android 14/15/16、旧系统及企微 | **未执行** | 没有设备授权、安装、登录、收发消息或生产服务调用 |

最终命令（日志 `android-full-7.txt`，2 分 5 秒，`BUILD SUCCESSFUL`）：

```powershell
.\gradlew.bat --no-daemon --console=plain -PunsignedValidation=true `
  :app:assembleDebug :app:testDebugUnitTest :app:lintDebug `
  :baselibrary:lintDebug :floatwindow:lintDebug :app:assembleRelease
```

其他命令：`scripts/Test-Protocol.ps1 -JavaHome <JDK17>`、
`python scripts/check_android_contracts.py`、`:app:thirdPartyInventory`、`:verification:test`。
原始报告留在各模块 `build/reports/`、JUnit XML 在 `build/test-results/`。
所有构建/审查原始日志保留于任务工作区，不提交带本机目录或主机名的日志。

合并 Manifest 的普通外部入口仅 launcher；无障碍服务由 BIND_ACCESSIBILITY_SERVICE
保护，AndroidX ProfileInstallReceiver 由系统 DUMP 权限保护。内部 Activity/Service、
provider 和 vendor H5Activity 不导出。minSdk 24、targetSdk 36 已核对。
警告主要为历史硬编码文案、内容描述、未使用资源、布局和依赖更新提示；不宣称零警告。

## 工具和许可

- fork 基线 `2105e6dde02c007f51f2e7cc51563187d227296f`；任务工作区新克隆，
  未修改已有用户 checkout，origin 只指向 Icatme/worktool。
- 仓库没有 AGENTS.md / .agents/skills；未增加 GitHub Actions workflow。
- 复用已有 Android Studio JBR 17.0.7、Android platform 36 revision 2。
- 下载官方 Gradle 8.13 并校验 SHA-256，wrapper 固定相同哈希；
  AGP 8.13.2、Kotlin 2.3.10。SDK/JDK 自动下载保持关闭。
- 初始完整构建确因缺少 Build Tools 35.0.0 阻塞；未绕过许可。
  用户随后明确授权编译所需安装和已披露 SDK 条款，使用官方 command-line tools 19.0
  在任务独立 SDK 中只安装 `build-tools;35.0.0`，退出码 0，版本文件验证为 35.0.0。
- 实际接受了该组件提示的唯一 `android-sdk-license`，条款日期 2019-01-16，
  [Android SDK License Agreement](https://developer.android.com/studio/terms)，
  许可记录 hash `24333f8a63b6825ea9c5514f83c2829b004d1fee`。
  没有批量执行 `sdkmanager --licenses` 或接受其他可选组件协议。
- 未改系统 SDK、系统环境变量、安全策略或网络设置。没有安装 platform-tools、
  模拟器镜像或设备应用；仅完成编译需要的工具准备。

首轮 Gradle 默认 debug 任务自动新建了调试 keystore。发现后停止该轮，核对此前不存在
和新文件哈希后删除，并确认不存在。后续统一加 `-PunsignedValidation=true`，
不创建或使用签名凭据；最终两个 APK 经 apksigner 确认为未签名。

## 协议与 HTTP 证据

生产 CommandLedger / ExportPolicy / UploadOutcome 直接参与测试：同包位置身份、
重复正文、并发接收、超过 30 秒的暂停/忙碌、满队列清理、保留已完成身份、
文件名/目录/大小边界和业务成功码。内存 ledger 不承诺跨进程 exactly-once、结果重放或 outbox。

HTTP 测试使用只绑定 loopback 的临时 MockWebServer，覆盖真实响应状态/业务正文、
延迟不提前完成、断连单次失败、非 2xx、chunked 超限和失败下载。没有请求商业端点。
GET_LOCAL_FILE 只接受 `files/exports` 下人工审核/脱敏的单个 txt/json 文件名，
1 byte–1 MiB；成功要求 HTTP 2xx 且业务 `code=200`，未确认的服务器协议不会假报成功。
符号链接 OS 集成、Android Handler 调度、实际服务器协议仍有独立验收门槛。

## 审查和修复

采用 review-it 的独立 `codex review`，审查未提交批次并在修复后复查：

- 协议批次：修复旧 Kotlin 跳转语法、满队列取消、完成身份误淘汰和大 base64 回执复制。
  最后完整复审无可执行缺陷，36 项协议检查通过。
- 迁移批次：修复 WWAPI 动态 receiver 启动崩溃、Material 对话框主题、未经 URI 授权的
  群二维码相册读取、共享显示名称、nullable projection、静止画面缓存、旧 Android 旋转。
  不支持的 WWAPI/群二维码路径显式关闭，不借机扩大访问权限。
- 最后聚焦复审：UUID 目录保留长文件名、过期空目录清理、文件列表空值、lint 修复、
  unsigned 构建和应用级 R8；结果为 “No actionable defects found in the focused follow-up.”
- 真实 Android 工具进一步发现并修复：API 35 集合方法绑定、PendingIntent mutability、
  Vivo cursor 列索引，以及库模块提前缩减造成 Release 缺类。未加入忽略缺类/禁用 lint 规则。

审查日志：`review-safety-2.txt`、`review-android.txt`、`review-android-2.txt`、
`review-android-3.txt`、`review-android-4.txt`。最终源码契约、ZIP 和签名检查日志分别为
`source-contracts-final.txt`、`debug/release-zipalign-final.txt`、`debug/release-signature-check.txt`。

## 尚待验收

按 [ACCEPTANCE.md](ACCEPTANCE.md) 执行 API 34/35/36 主矩阵、24/30/33 边界和 16KB 设备。
验证通知拒绝、录屏取消/持续静止帧/旋转/锁屏/系统停止/重新授权、前后台限制、
文件 URI 授权、断线重连和关闭后不重连。唯一测试联系人文本闭环必须另获测试账号与操作授权。
显示名称歧义、历史 UI 自动化、默认商业端点/静态协议密钥、旧 WWAPI 再分发许可，
仍是内部分发前的门槛；本轮不据此声明新安卓或新企微“全部兼容”。
