# 项目开发约定

## 目标与沟通

- 默认用中文沟通，修改代码前说明改动点。
- 本仓库是 ad-hunter（原“米家净化”）LSPosed 模块，保持现有纯 Java、Modern Xposed API 102 实现。
- 优先做能完整满足需求的最小修改，不引入未要求的设置界面、通用 Hook 框架或兼容层。
- 修改生产依赖、执行数据库迁移或删除用户已有文件前，确认具体目标和影响。同一对话中的已有授权继续有效；只读检查和清理本次生成的临时文件无需重复确认。
- 优先使用 Gradle Wrapper 和现有脚本。引入新工具前，说明必要性、替代方案和影响并取得确认。

## 目录与构建

| 路径 | 用途 |
| --- | --- |
| `app/src/main/java/io/github/hunter/mihomepurifier/MiHomeModule.java` | 包名、进程过滤及 Hook 注册 |
| `app/src/main/java/io/github/hunter/mihomepurifier/CookerBundlePatch.java` | 电饭煲 indexed RAM bundle 补丁 |
| `app/src/main/resources/META-INF/xposed/` | API 版本、模块入口与固定作用域 |
| `app/src/test/java/` | 无 Android 依赖的断言校验 |
| `scripts/verify_apk.py` | 实际 APK 元数据、DEX 入口和依赖打包检查 |
| `work/` | 本地 APK、反编译结果、日志、截图和分析证据，不提交 |

环境基线：JDK 21、Android SDK Platform 37、Build Tools 36.0.0。SDK 路径通过环境变量或未提交的 `local.properties` 配置，禁止写死开发者机器路径。

```powershell
./gradlew.bat --gradle-user-home .gradle-user-home :app:assembleDebug :app:lintDebug :app:verifyPatch :app:verifyMiHomeDex
python scripts/verify_apk.py
```

macOS / Linux 使用 `bash gradlew`、`python3`。可用 `-PcookerBundle=<main.bundle 的绝对路径>` 和 `-PmiHomeApks=<米家 APK 的绝对路径>` 检查真实样本。多个 APK 路径按系统路径分隔符连接，Windows 为分号，macOS / Linux 为冒号。样本不提交。APK 输出为 `app/build/outputs/apk/debug/app-debug.apk`。

## 修改前的检查

- 先看 Git 状态，保留用户已有修改和未跟踪文件，不擅自重置、清理、暂存或提交。
- 根目录存在 `.codegraph/` 时，定位和理解代码优先使用 CodeGraph；没有时直接使用 `rg`，不自行创建索引。
- 阅读目标方法及调用点，确认进程、线程、执行时序、缓存和页面重建行为后再改动。
- 宿主混淆类名、方法、字段和资源 ID 必须来自目标版本的静态分析或实机证据，禁止猜测。
- 反编译输出可能按 Kotlin 元数据重命名包名。Hook 使用 DEX 中的真实名称，不能直接复制 JADX 的别名。

## 当前适配证据

自适配规则使用米家 11.8.707（110817071）和 11.9.705（110917051）的 APK 验证，11.9.705 在一加 13 上完成页面回归。各功能根据当前宿主的入口和业务特征独立启用。

| 功能 | 入口与约束 |
| --- | --- |
| 产品 Tab | 在 `TabPageIndicatorNew$TabView.setTabInfo` 的参数中唯一定位宿主 `TabId` 字段，隐藏 `Brand` 并保留原索引 |
| 商城中心 | `SettingMainPageV2.onViewCreated(View, Bundle)` 完成后，按 `商城中心` / `Store` 文案和可点击的 `ItemOptionView` 唯一定位整行。其他语言保留原页面 |
| 广告策略 | DEX 中唯一匹配引用六个广告开关、等待时间和广告日志标记的静态解析方法。安装前用不同样本值和空样本定位字段，安装后验证归零，自检失败时撤销 Hook |
| 开屏等待 | DEX 指令内唯一匹配广告准备事件和宿主隐私判断，再校验事件对象的 Activity 构造器。仅在宿主确认已同意时发出无广告事件并将广告准备参数改为 false |
| 电饭煲默认页 | `CatalystInstanceImpl.loadScriptFromFile(String, String, boolean)` 根据插件目录结构和 model_list.json 识别仅面向电饭煲的插件，再定位唯一符合双 Tab 横滑模板的 indexed RAM bundle 模块 |

规则按包名与进程名组合隔离，页面和广告规则仅在对应主进程注册，电饭煲还允许米家 `:plugin0`～`:plugin4`。使用包就绪的加载器定位目标类。米家主进程在 Application 创建前检查 APK 及 split 中的标准 DEX，缓存由扫描规则版本和 DEX ZIP 条目指纹控制。DEX 041 容器或无法唯一确认的成员会跳过相应规则。扫描范围为已安装的 base/split APK，推送和摄像头等其他进程跳过。

## 12306 适配

- 基线：5.9.6.8（280），包名 `com.MobileTicket`。入口为 `RailwayHooks.java`。
- API 102 的 `onPackageReady` 可直接访问业务类；实测此时再 Hook 壳的 `attachBaseContext` 已错过调用，不采用该方案。
- 将 `BeanAds.haveSplashAd()`、`HomePageUtils$Companion.getCAN_SHOW_SPLASH()`、`AdUtils.splashNeedShowAgain()` 三个布尔结果设为 false，每项独立处理不兼容。
- 广告地址层：`AppHelp.getAdsUrl01(Context)` 仅在原地址属于 `ad.12306.cn` 且系统拒绝回环地址明文 HTTP 时返回 `http://127.0.0.1/`。5.9.6.8 既有记录显示约 1–7 ms 内回调失败，仍会构造请求并进入失败处理。
- 订单页 H5 广告位为 `0074`。`H5AdExposurePlugin.handleEvent(H5Event,H5BridgeContext)` 只匹配 `exposureH5Request` + POST + params.placementNo=0074，回传原桥接协议的 result=1、data.materialsList=[]，不执行广告网络请求；其他桥接请求原样执行。
- 不修改网络安全策略，不全局拦截 HttpsUtils；保留显示层处理缓存和布局。
- 首页顶部轮播使用 `CarouseAdOperator`：拦截缓存读取 `getCarouseAdData`（返回宿主 Kotlin Unit）、`updateAd(boolean)`、`restartHandlerScroll`；构造后暂停轮播并隐藏 `home_ticket_item_ad`，移除 `home_select_station` 原负顶部间距，按标题栏和系统状态栏高度重新定位。布局变化后须验证查询按钮不被遮挡。
- Hook 点参考本机 HookVIP 4.1.7 的运行时记录，独立实现；不复制其代码，不修改 `isChineseLanguage`，不扩大到购票、认证或支付逻辑。
- 验证前记录其他模块作用域；临时调整后恢复，确保最终在 HookVIP 未作用于 12306 时验证本模块。

## 航旅纵横适配限制

- 8.6.7（698）使用加固壳。实机确认仅加载本模块、不注册任何 Hook 时也会反复重启；移除作用域后恢复。根因尚未定位到具体检测函数。
- 不将未经验证的航旅纵横规则或作用域加入发布 APK。先解决注入兼容性，再验证广告拦截。实验代码和提取的业务 DEX 仅保存在 `work/umetrip/`。
- 当前显示名为 ad-hunter，安装包名和入口类保持原值以便覆盖升级；日志标签为 `AdHunter`。

## 实现约束

- 普通 Java 实例字段使用 `m` 前缀，如 `mMainProcess`；DTO、VO、Entity、配置及序列化对象使用普通驼峰命名。常量沿用大写下划线风格。
- 禁止通过可变入参传出结果。单结果直接返回，多个结果优先复用现有类型，必要时新增最小结果类。
- 只为非显然逻辑和约束写简短中文注释。已知版本限制要写明；避免只有一行逻辑的多余封装。
- 不为几行代码增加依赖。Xposed API 保持 `compileOnly`，不打包进 APK；不要混用旧版 `assets/xposed_init`。
- Hook 必须限制目标包、进程和具体入口；避免全局拦截 View、网络或计时器来解决局部问题。
- 不跳过隐私同意、登录、设备认证、错误处理或实际设备控制保护。
- 不将全部初始化延时归为广告延时。保留首页就绪条件和必要兜底，区分首次绘制、开屏撤除、设备数据就绪。
- 电饭煲补丁校验插件目录、型号类别、索引边界及唯一源码特征；生成独立副本，原子替换副本，保留原插件、资源基址和其他模块字节。失败时加载原插件并记录错误。
- 新增非平凡逻辑应配有小型测试或断言自检。简单 UI 隐藏优先实机验证，无需新增测试框架。

## 验证与交付

- 代码变更运行构建、Lint、相关补丁校验及 APK 检查。仅文档变更检查事实、链接和忽略规则，无需重建 APK。
- 安装前用 `adb devices -l` 确认设备，后续命令显式指定序列号。多设备且目标不明确时先询问。
- 更新模块后完整重启米家及插件进程；不假定热重载有效，不通过清数据、卸载米家或重启设备碰运气。
- 采集有时间或行数上限的日志，不清空系统日志。临时改变的模块状态、追踪或代理配置必须恢复并回读确认。
- 页面改动验证首次进入、退出后重进、相邻入口以及是否留下空白。电饭煲验证默认设备页和手动切回食谱，不为 UI 测试启动烹饪。
- 交付时说明实际验证范围、未验证项和版本限制；不能用规则注册日志代替功能生效证据。
- 发布模块版本时同步 `versionCode`、`versionName`、README 和必要的模块描述；纯文档改动不升版本。

## Windows 命令

- PowerShell 用于简单本地命令。Bash 语法、管道、复杂引号、远端 Linux 命令使用 Git Bash。
- Git Bash 调用 ADB 时设置 `MSYS_NO_PATHCONV=1`，避免设备路径被转为 Windows 路径。
- 二进制样本优先用 `adb pull`，必要时核对设备端和本地哈希；不要经文本管道传输二进制。

## 公开仓库边界

- 不提交原版 APK、DEX、完整反编译代码、设备插件包、签名私钥、本地凭据或真实账号数据。
- 实机截图、日志和追踪统一放入已忽略的 `work/`。公开说明仅保留脱敏后的结论和必要代码映射。
- 提交前检查 `git status --short --untracked-files=all` 和忽略规则，确认源码、Wrapper、构建配置未被误忽略；不以 `git add -f` 绕过敏感文件规则。
- 已跟踪文件不会因添加 `.gitignore` 自动消失。发现已提交凭据时先报告泄露范围，不擅自改写历史。
- 本项目原创代码使用 MIT；不得将第三方程序或反编译内容宣称为本项目 MIT 授权作品。
