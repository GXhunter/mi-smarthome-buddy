# 米家净化

一个面向手机端米家 App 的 LSPosed 模块，精简购物入口，关闭官方广告策略控制的广告，并调整指定电饭煲的默认页面。

需要已安装并正常运行 LSPosed 的设备，框架须支持 **Modern Xposed API 102**。本项目不是修改版米家 APK，使用时仍需安装官方米家 App。

## 功能

| 功能 | 效果 |
| --- | --- |
| 关闭官方广告策略 | 关闭底部、实时开屏、横幅、内容推广、开屏展示、个人中心六类广告开关，将广告加载等待时间设为 0 |
| 跳过开屏广告等待 | 无需等待广告准备或展示结果；保留首页必要加载和隐私确认流程 |
| 隐藏“产品”Tab | 首页底部保留“米家 / 智能 / 我的” |
| 隐藏“商城中心” | 移除“我的 → 商城中心”整行入口，不留可点击的空白行 |
| 电饭煲默认设备页 | 进入已适配电饭煲时默认显示“设备”，仍可手动切换到“食谱” |

当前没有设置界面，启用模块后上述规则自动生效。

## 兼容范围

| 项目 | 当前版本 / 要求 |
| --- | --- |
| 模块版本 | 0.3.1 |
| 米家包名 | `com.xiaomi.smarthome` |
| 已验证米家版本 | 11.8.707（110817071） |
| 框架要求 | 支持 Modern Xposed API 102 的 LSPosed |
| 最低 Android 版本 | Android 8.0（API 26），不代表该版本已实测 |
| 已验证设备 | 一加 13，Android 16 |
| 电饭煲 | 米家智能微压 IH 电饭煲 3L，型号 `chunmi.cooker.cmwy3` |
| 电饭煲插件版本 | `1016529/1034887` |

其他米家版本尚未验证。米家更新可能改变内部类名、字段或页面结构，导致部分规则失效。电饭煲默认页补丁仅作用于表中指定型号和插件版本。

## 安装与启用

1. 获取本项目构建的模块 APK；自行构建方法见下文。
2. 安装模块，在 LSPosed 管理器中启用“米家净化”。
3. 将作用域设为 **米家**（`com.xiaomi.smarthome`）。
4. 在系统应用设置中强行停止米家，再重新打开，使主进程和设备插件进程重新加载。

更新模块后也需要完整重启米家。仅返回桌面或划掉最近任务，可能保留旧插件进程。模块没有桌面图标，安装和启用状态请在 LSPosed 管理器中查看。

开发者也可以通过 ADB 安装：

```sh
adb -s <设备序列号> install -r app/build/outputs/apk/debug/app-debug.apk
```

## 使用边界

- 广告屏蔽覆盖上述官方策略控制的广告，不保证覆盖设备插件自有推广或第三方网页。广告策略配置请求本身可能继续执行，但不能通过该解析结果重新开启广告。
- 跳过广告等待不会省去米家的代码加载、首页渲染或设备数据请求，因此不保证每次启动都有明显提速。
- 电饭煲补丁生成独立副本，不覆盖原始插件文件。版本或源码特征不匹配时会加载原插件。
- 已验证页面进入、Tab 切换与入口隐藏；未通过实际烹饪操作验证设备控制功能。

## 停用与恢复

在 LSPosed 中停用“米家净化”，然后强行停止并重新打开米家，即可恢复官方页面行为。无需清除米家数据或重新绑定设备。

如果安装更新时提示签名不一致，请使用同一签名来源的模块 APK。本地 Debug 构建使用各自机器的调试签名，不同机器的产物可能无法直接覆盖安装。

## 自行构建

准备 JDK 21、Android SDK Platform 37、Build Tools 36.0.0 和 Python 3。通过 `ANDROID_HOME` 或本地 `local.properties` 配置 Android SDK 路径。

Windows PowerShell：

```powershell
./gradlew.bat --gradle-user-home .gradle-user-home :app:assembleDebug :app:lintDebug :app:verifyPatch
python scripts/verify_apk.py
```

macOS / Linux：

```sh
bash gradlew --gradle-user-home .gradle-user-home :app:assembleDebug :app:lintDebug :app:verifyPatch
python3 scripts/verify_apk.py
```

构建产物：`app/build/outputs/apk/debug/app-debug.apk`。

独立的 Gradle 用户目录用于避免全局初始化脚本干扰依赖仓库；IDE 同步时也建议使用 `.gradle-user-home`。Release 尚未配置发布签名或混淆。

## 问题反馈

反馈时请提供米家版本、Android 版本、LSPosed 版本、模块版本，以及复现步骤。电饭煲问题还需提供型号和插件版本。

可在 LSPosed 模块日志中搜索 `MiHomePurifier`，查看规则加载及不兼容信息。分享日志或截图前，请删除账号、设备 ID、令牌和其他个人信息；不要上传完整应用数据、原版 APK 或反编译目录。

开发约定与适配细节见 [AGENTS.md](AGENTS.md)。

## 许可证

本项目原创代码使用 [MIT License](LICENSE)，版权署名为 `Copyright (c) 2026 hunter`。随项目使用的第三方组件遵循各自许可证，米家及其插件不属于本项目的 MIT 授权范围。

本项目为独立项目，与小米官方无隶属或认可关系。
