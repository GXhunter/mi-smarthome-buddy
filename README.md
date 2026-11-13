# ad-hunter

一个用于应用广告屏蔽与界面精简的 LSPosed 模块，目前支持铁路 12306 和手机端米家 App，精简购物入口，关闭官方广告策略控制的广告，并调整指定电饭煲的默认页面。

需要已安装并正常运行 LSPosed 的设备，框架须支持 **Modern Xposed API 102**。本项目不是修改版应用 APK，使用时仍需安装相应的官方 App。

## 功能

| 功能 | 效果 |
| --- | --- |
| 12306 广告精简 | 将公共广告服务地址替换为本机地址；移除顶部轮播，关闭开屏展示，并屏蔽订单页底部广告 |
| 米家：关闭官方广告策略 | 关闭底部、实时开屏、横幅、内容推广、开屏展示、个人中心六类广告开关，将广告加载等待时间设为 0 |
| 跳过开屏广告等待 | 无需等待广告准备或展示结果；保留首页必要加载和隐私确认流程 |
| 隐藏“产品”Tab | 首页底部保留“米家 / 智能 / 我的” |
| 隐藏“商城中心” | 移除“我的 → 商城中心”整行入口，不留可点击的空白行 |
| 电饭煲默认设备页 | 进入已适配电饭煲时默认显示“设备”，仍可手动切换到“食谱” |

当前没有设置界面，启用模块后上述规则自动生效。

模块原名“米家净化”，保留安装包名 `io.github.hunter.mihomepurifier`，可覆盖升级。

## 兼容范围

| 项目 | 当前版本 / 要求 |
| --- | --- |
| 模块版本 | 0.6.3 |
| 已验证 12306 版本 | 5.9.6.8（280），包名 `com.MobileTicket` |
| 米家包名 | `com.xiaomi.smarthome` |
| 米家适配版本 | 11.8.707（110817071，历史实测）和 11.9.705（110917051，0.6.3 实测） |
| 框架要求 | 支持 Modern Xposed API 102 的 LSPosed |
| 最低 Android 版本 | Android 8.0（API 26），不代表该版本已实测 |
| 已验证设备 | 一加 13，Android 16 / ColorOS 16.1.0，LSPosed IT 2.2.1-it（7921） |
| 电饭煲 | 米家智能微压 IH 电饭煲 3L，型号 `chunmi.cooker.cmwy3` |
| 电饭煲插件版本 | `1016529/1034887` |

模块按上表的宿主版本号启用规则，其他版本保留原行为。电饭煲默认页补丁还要求匹配指定插件路径和源码特征。

### 航旅纵横适配状态

航旅纵横 8.6.7（698）尚不支持：实机对照发现仅加载模块、不注册任何 Hook 也会导致反复重启，移除作用域后恢复正常。已分析冷启动广告请求、缓存与素材下载、回前台广告逻辑，但实验规则未打包，默认作用域不包含该 App。请勿手动添加航旅纵横作用域。

## 安装与启用

1. 获取本项目构建的模块 APK；自行构建方法见下文。
2. 安装模块，在 LSPosed 管理器中启用“ad-hunter”。
3. 按需勾选 **米家**（`com.xiaomi.smarthome`）和 **铁路12306**（`com.MobileTicket`）。
4. 在系统应用设置中强行停止对应 App，再重新打开；米家还需要使设备插件进程重新加载。

更新模块后也需要完整重启对应 App。仅返回桌面或划掉最近任务，可能保留旧插件进程。模块没有桌面图标，安装和启用状态请在 LSPosed 管理器中查看。

开发者也可以通过 ADB 安装：

```sh
adb -s <设备序列号> install -r app/build/outputs/apk/debug/app-debug.apk
```

## 12306 规则说明

参考手机上 HookVIP 4.1.7 的实际 Hook 调试记录，使用 Modern Xposed API 102 独立实现三个开屏布尔判断拦截：`BeanAds.haveSplashAd()`、`HomePageUtils.Companion.getCAN_SHOW_SPLASH()`、`AdUtils.splashNeedShowAgain()`。不依赖 HookVIP，不包含其实现代码，也不修改语言判断。

本规则仅作用于 12306 5.9.6.8 主进程。系统拒绝回环地址的明文 HTTP 时，`AppHelp.getAdsUrl01(Context)` 返回 `http://127.0.0.1/`，通过该方法获取地址的开屏、轮播及天气广告请求进入失败处理。允许明文时保留宿主原广告地址。

保留开屏显示判断、轮播缓存读取及布局兜底，避免旧缓存和默认图片继续展示。当前不再使用 0.6.0 的三个加载入口拦截；没有全局拦截通用网络组件，也不修改 App 的网络安全策略。其他接口、第三方 SDK、网页及缓存广告不一定经过此地址方法。

订单页底部广告由 H5 页面直接读取广告配置并发起桥接请求，绕过上述原生地址方法。模块在 `H5AdExposurePlugin.handleEvent` 中仅匹配 `exposureH5Request` 的 POST 请求且 `params.placementNo` 为 `0074`，直接返回空素材列表。订单列表、订单详情和支付请求不匹配此规则。

## 使用边界

- 广告屏蔽覆盖上述官方策略控制的广告，不保证覆盖设备插件自有推广或第三方网页。广告策略配置请求本身可能继续执行，但不能通过该解析结果重新开启广告。
- 跳过广告等待不会省去米家的代码加载、首页渲染或设备数据请求，因此不保证每次启动都有明显提速。
- 电饭煲补丁生成独立副本，不覆盖原始插件文件。版本或源码特征不匹配时会加载原插件。
- 已验证页面进入、Tab 切换与入口隐藏；未通过实际烹饪操作验证设备控制功能。

## 停用与恢复

在 LSPosed 中停用“ad-hunter”，然后强行停止并重新打开对应 App，即可恢复官方页面行为。无需清除米家数据或重新绑定设备。

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

可在 LSPosed 模块日志中搜索 `AdHunter`，查看规则加载及不兼容信息。分享日志或截图前，请删除账号、设备 ID、令牌和其他个人信息；不要上传完整应用数据、原版 APK 或反编译目录。

开发约定与适配细节见 [AGENTS.md](AGENTS.md)。

## 许可证

本项目原创代码使用 [MIT License](LICENSE)，版权署名为 `Copyright (c) 2026 hunter`。随项目使用的第三方组件遵循各自许可证，米家及其插件不属于本项目的 MIT 授权范围。

本项目为独立项目，与小米官方无隶属或认可关系。
