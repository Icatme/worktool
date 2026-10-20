# WorkTool 企业内部分发现代化方案

基线核实：2026-10-20，用户 fork `Icatme/worktool`，master
`2105e6dde02c007f51f2e7cc51563187d227296f`。后续 README 更新不等于源码更新；
最后源码阶段为 2023-11-23 的 `9080e05`。三个 Android 模块为 app、baselibrary、
floatwindow，无服务端。源码版本 2.8.1 / 28011，本次不改版本号、不打标签、不发布。
仓库未发现 AGENTS.md 或 .agents/skills；许可保留 Apache-2.0。

## 定位和边界

本项目通过无障碍节点与手势操作 `com.tencent.wework`，使用 WebSocket 接收命令、
回传结果，不是普通微信适配器。Android OS 兼容与企微 UI 版本兼容是两条独立验收线。
不上 Google Play 不免除 OS 权限、后台执行、存储、录屏限制。
本轮先恢复可复现构建和文本基础，再处理平台及小范围可靠性问题。
不重写全部企微操作，不接 JZ，不新增完整服务端。

只在用户 fork 提交、推送草稿 PR；不合并，不改上游，不触发 Actions。
不安装到真实手机，不启用无障碍/录屏/设备管理权限，不登录或操作企微，
不调用现有商业服务器上传文件/聊天。安装系统组件、接受 SDK 许可必须另获授权。
测试可以使用纯 JVM、静态检查及隔离模拟器；没有设备证据时不宣称兼容完成。

## 工具链决策

| 项目 | 原值 | 本轮目标 |
| --- | --- | --- |
| JDK | 未固定 | 17，复用已有安装；运行时补丁升级由运维审批 |
| AGP | 4.0.0 | 8.13.2 |
| Gradle | 6.1.1 | 8.13，固定分发 SHA-256 |
| Kotlin | 1.3.72 | 2.3.10，移除废弃 synthetic 插件 |
| compileSdk / targetSdk | 30 / 30 | 36 / 36 |
| minSdk | 24 | 24，暂不提高 |
| Java/Kotlin 字节码 | 8 | 8，避免无关语言升级 |

AGP 8.13 支持 API 36.1，要求 Gradle 8.13、JDK 17、Build Tools 35.0.0；
8.13.2 支持 Kotlin 2.3。Kotlin 2.3.10 官方兼容范围包含该组合。
这是降低跨代迁移变量的稳定过渡组合，不声称为最新版本；AGP 9 的内置 Kotlin /
DSL 迁移作为后续独立事项。依赖只使用 Google Maven / Maven Central；
无法从可信渠道解析的遗留组件须替换、移除未用项或明确阻塞，不引入不明二进制。

保留 API 24 是因为现有核心无障碍手势本来要求 Android 7。API 24–33 仅承诺
构建最低范围，须回归再决定是否维持支持。重点设备矩阵 API 34/35/36，
另以 API 24/30/33 做边界冒烟；旧设备成本包括存储、通知、悬浮窗和企微可安装性。

## 分批交付与门槛

1. **方案与基线**：本方案、可执行验收表、工具链/第三方清单。
   验收：基线及范围可追溯，CI 未触发，无发布操作。
2. **命令与文件边界**：同包多命令不再互相删除；单指令身份区分排队/执行/完成；
   ACK 只表示接收。暂停/繁忙/重投递测试；不自动重试有副作用命令。
   GET_LOCAL_FILE 限定显式导出文件、大小和脱敏边界，异步 HTTP 结果决定成功；
   空能力明确拒绝；短 base64 日志不会越界且不泄漏内容。
   验收：离线 JVM/协议测试通过，失败不能报成功。
3. **工具链与 Android 兼容**：namespace、synthetic 替换、依赖审计；SDK 36；
   exported、动态广播、包可见性、前台服务、录屏单会话、共享路径修复。
   验收：debug 构建、单元测试、lint；合并 Manifest / APK native 检查；
   Android 14/15/16 设备检查另列，未经设备验证保持草稿。
4. **后续独立工作**：连接状态机/带抖动退避及离线结果 outbox；命令持久化/幂等协议；
   目标用户身份与重名歧义拒绝；文本测试账号最小闭环；渠道网关接口设计。
   需要协议及测试账号证据，不在本轮猜测性改写企微 UI 操作。

每批先本地复核差异与测试证据，再推送草稿 PR；堆叠 PR 明确 base，
小步提交含 `[skip ci]`，不增加工作流。任何构建/设备阻塞都记录真实状态。

## 平台兼容措施

| 范围 | 触发条件 | 措施 / 验收 |
| --- | --- | --- |
| exported | target >=31 | launcher true；内部 Activity/Service false；无障碍服务由系统签名权限保护 |
| 动态广播 | target >=34 | 应用内部接收器 NOT_EXPORTED；发送指定本包；销毁注销 |
| 通知 | Android 13+ | 用户前台操作时请求 POST_NOTIFICATIONS，拒绝仍可退出录屏 |
| MediaProjection | target >=34；新系统额外隐私控制 | 每次授权只创建一次 VirtualDisplay；持续读帧；停止释放全部资源，重新授权才可重建 |
| 录屏 FGS | target >=34 | FOREGROUND_SERVICE_MEDIA_PROJECTION；先用户授权，再从可见 Activity 启动服务并及时前台化 |
| 后台/锁屏 | 系统限制及 target 限制并存 | 不从取帧偷偷拉起授权页；停止/锁屏不自动重授权；显式入口恢复 |
| 私有缓存 | scoped storage | 关闭跨应用 Android/data 读取；不以 root/存储绕过替代；默认不推图、不录屏 |
| 文件共享 | 所有现代 Android | app 私有共享子目录 + FileProvider 临时读权限；不暴露整个外部存储 |
| 包可见性 | target >=30 | 已声明企微 queries；审计 QUERY_ALL_PACKAGES 的必要性并收窄 |
| 前台时限 | target >=35 的特定 FGS 类型 | 不把 dataSync 六小时限制套到未调用 startForeground 的无障碍服务 |
| UI | target >=35/36 | edge-to-edge inset、系统栏、宽屏布局；不可用隐藏开关声称完成 |
| native | Android 15+ 16KB 设备 | 检查最终 APK 的 ABI、ELF LOAD 对齐和 ZIP 对齐；AGP 升级不能替第三方重编译 |

## 可靠性与安全后续

接收确认、开始执行、成功/失败、结果送达必须区分。内存去重不能提供跨进程 exactly-once；
不能把 WebSocket.send 排队成功当成网络连接/业务完成。暂停或排队不应因短 TTL 消失。
重连应使用 onOpen/onFailure 驱动、可取消任务、单 client、退避上限与抖动；
本轮先修确定缺陷，后续用本地假服务测试网络抖动和进程恢复。

显示名称或首个搜索结果不足以保证收件人身份。后续协议增加企业/账号/会话稳定 ID、
候选唯一性、发送前核对与显式歧义失败，使用测试账号逐路径验证后再替换业务行为。
不扩大到拉人、踢人、群发等副作用操作。

日志禁止原始文件/base64、鉴权令牌和聊天正文；诊断导出须独立审阅后放入导出目录，
不自动上传原始日志。商业遥测、默认后端、静态密钥、明文 WS 和更新器均须内部部署前
单独审计；测试不得执行它们。Apache-2.0 LICENSE 及第三方许可/NOTICE 保留。

## 官方依据（2026-10-20 查阅）

- [AGP 8.13 兼容表与 8.13.2](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
- [Kotlin Gradle 兼容表](https://kotlinlang.org/docs/gradle-configure-project.html)
- [Gradle Java 兼容表](https://docs.gradle.org/8.13/userguide/compatibility.html)
- [Android 14 target 变化](https://developer.android.com/about/versions/14/behavior-changes-14)
- [Android 15 target 变化](https://developer.android.com/about/versions/15/behavior-changes-15)
- [Android 16 target 变化](https://developer.android.com/about/versions/16/behavior-changes-16)
- [MediaProjection 生命周期](https://developer.android.com/media/grow/media-projection)
- [16KB 页面支持](https://developer.android.com/guide/practices/page-sizes)
- [安全文件共享](https://developer.android.com/training/secure-file-sharing/setup-sharing)
