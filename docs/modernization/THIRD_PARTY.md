# 第三方依赖与 native 初检

2026-10-20 对 `debugRuntimeClasspath` 解析的外部工件及本地 WWAPI AAR 做初检。
命令为 `./gradlew :app:thirdPartyInventory`，脚本见 `scripts/dependency-inventory.gradle`。
[dependency-inventory.json](dependency-inventory.json) 记录 75 个工件的坐标、文件名、
SHA-256、native 条目和缓存 POM 的许可声明；POM 声明不等于完成逐文件法律审计。

75 个工件中未发现 `.so`。最终未签名 Debug/Release APK 同样没有 `.so`，官方
`zipalign -c -P 16 -v 4` 均通过；没有 ELF 可检查。这不等于已经完成 16KB 设备运行验收。
以后引入 native 库必须重新检查，不能仅凭 AGP 版本认定支持。

## 来源和变更

- 项目 Apache-2.0 LICENSE 保留，不改变原作者归属。
- AndroidX、Material 来自 Google Maven；Kotlin、OkHttp/Okio、Gson、Glide、
  ZXing、QMUI、utilcodex 等来自 Maven Central。版本在 Gradle 中固定。
- 删除 jcenter、Fabric、JitPack 仓库；未下载来源不明的旧二进制来填补依赖。
- 原 TalkingData JAR 不再加入运行时依赖，Umeng 初始化及依赖移除。
  原 JAR 尚保留在 Git 历史/目录，SHA-256 为
  `e6d23f5a83fc75f67dae140449f6699a4692aa0ae7e3d687832d4734c1a28653`。
- 本地 `lib_wwapi-2.0.12.11.aar` 是原仓库工件，没有替换来源；
  SHA-256 为 `15f4188907534944e94a12d25031134f9553bcaaa08f9487dbfd06ff59064004`。
  它仍参与编译/打包，但所有 SDK 初始化和分享入口保持关闭。

## 许可证据与待办

大部分 POM 声明 Apache-2.0；必须同时保留库内 NOTICE 和个别组件其他许可。
以下缺项单独记录，不用推测填充清单：

| 工件 | 当前证据 | 发布前要求 |
| --- | --- | --- |
| ZXing core 3.5.3 | 当前 POM 未直接列 license；[官方 LICENSE](https://github.com/zxing/zxing/blob/master/LICENSE) 含 Apache-2.0 及其他子项目说明 | 核对该版本 core 的实际 LICENSE/NOTICE |
| Guava listenablefuture 1.0 | 当前子模块 POM 未直接列 license；[官方 LICENSE](https://github.com/google/guava/blob/master/LICENSE) 为 Apache-2.0 | 核对父 POM/版本许可证继承 |
| QMUI 2.0.1 | POM license 名称/URL 为 `null`；[官方 LICENSE.TXT](https://github.com/Tencent/QMUI_Android/blob/master/LICENSE.TXT) 说明 QMUI 为 MIT，包含其他 Apache-2.0 组件 | 核对 2.0.1 工件内版权与第三方声明，保留 MIT 原文 |
| WWAPI 2.0.12.11 | 原仓库 AAR 未提供可验证的许可证元数据 | 内部分发前确认腾讯 SDK 许可/再分发权；未确认则移除 SDK 路径和工件 |

本轮仅生成本地未签名 APK，未分发；清单是技术盘点而非许可批准。
运行时清单不覆盖构建插件、测试依赖或 Android 平台自身的许可。
