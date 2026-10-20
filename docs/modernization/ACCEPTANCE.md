# 验收运行手册

以下是待执行门槛，不是已通过结果。真实执行证据见 VALIDATION.md。

## 本地构建（不触发 CI）

使用 JDK 17、Android SDK platform 36、Build Tools 35.0.0，先由管理员安装并
接受适用许可；不要在自动化脚本中使用 `yes | sdkmanager --licenses`。

```powershell
$env:JAVA_HOME = '<existing JDK 17>'
$env:ANDROID_HOME = '<approved SDK>'
$env:GRADLE_USER_HOME = '<task-local Gradle cache>'
.\gradlew.bat --no-daemon --console=plain -PunsignedValidation=true :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

上述参数仅生成未签名 APK，不创建或使用签名凭据。
保存控制台日志、JUnit XML、lint XML/HTML、合并 Manifest、依赖树和 APK SHA-256。
SDK 组件不足时仍可执行 `:verification:test`（需要已有 platform 36、JDK 17），
以及 `python scripts/check_android_contracts.py`。前者只编译指定生产边界并运行 JVM 测试，
后者只是源码契约检查，两者均不能替代完整 Android 构建或 lint。
检查最终 Manifest 的 exported、permission、provider paths 和 FGS 类型。
检查 APK 全部 `lib/<abi>/*.so`：arm64-v8a 是否齐全，ELF LOAD p_align 是否 >=16KB；
用官方 `zipalign -c -P 16 -v 4 <apk>` 检查打包对齐。无 .so 也应记录检查结果。
release/R8 构建与签名/分发是不同事项；只验证未签名产物，不使用生产签名。

## 离线协议 / 单元

- 同一包内两条不同命令及相同正文两个位置均保留，顺序正确。
- 重投递同一 messageId+位置不重复执行；不同 messageId 的同正文不被吞。
- 暂停和忙碌超过 30 秒后重复投递仍不替换原命令；清队列只释放待执行身份。
- 无效 JSON/密文不 ACK 为接收成功；ACK 不等于执行成功。
- 执行异常不盲目重放可能已经产生副作用的命令。
- GET_LOCAL_FILE 拒绝空路径、绝对路径、../、符号链接越界、目录、空文件及超限；
  不自动导出日志；模拟 HTTP 成功/业务失败/异常只产生一个真实终态。
- 未支持命令返回明确错误；空/短/大 base64 日志不抛异常、不记录内容。

## Android 设备矩阵（全部尚待授权设备执行）

API 34、35、36 各至少一台 AOSP 模拟器或测试设备；API 24、30、33 做回归边界。
API 35/36 至少包含一台 16KB page size 环境。记录 OS build、企微版本、
安装渠道、授权状态、APK hash 和结果，不使用个人真实账号。

1. 全新安装启动：清空历史应用数据、不输入 robotId、不启用无障碍，不连接商业服务；
   无后台 FGS 异常；旧 WWAPI 和群二维码相册功能保持关闭；
   不启用无障碍/悬浮窗/录屏时页面可用；旋转、edge-to-edge、字体放大不遮挡操作。
2. 拒绝通知、取消录屏均正常返回；用户同意一次录屏后连续获取 >=20 帧，
   不重复授权、不重复 createVirtualDisplay；旋转/窗口改变后尺寸正确或明确重授权。
3. 系统停止录屏、锁屏、杀进程、通知停止按钮：资源释放，FGS 停止；
   再取帧返回不可用，用户重新进入授权页后才恢复。
4. 内部广播不能被外部测试包注入；内部 Activity/Service 不能被外部启动。
   无障碍服务仅系统通过 BIND_ACCESSIBILITY_SERVICE 绑定。
5. FileProvider 只读授权给目标包、只能读本次共享文件；不能读应用私有数据、
   任意外部目录；共享文件清理策略正常。
6. 断网、Wi-Fi 切换、服务重连、暂停/恢复、关闭服务后不再重连；
   锁屏恢复必须由用户解锁，不试图绕过系统锁屏。
7. 企微测试账号最小闭环：唯一联系人文本接收、单次发送、结果核对；
   重名时拒绝发送。必须另获测试账号与操作授权后执行。

不得把模拟器纯 UI 启动、编译成功、静态规则通过等同于真实企微兼容。
