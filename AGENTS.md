# 项目开发约定

## 目标与沟通

- 默认用中文沟通，修改代码前说明改动点。
- 本仓库是“米家净化”LSPosed 模块，保持现有纯 Java、Modern Xposed API 102 实现。
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
./gradlew.bat --gradle-user-home .gradle-user-home :app:assembleDebug :app:lintDebug :app:verifyPatch
python scripts/verify_apk.py
```

macOS / Linux 使用 `bash gradlew`、`python3`。如有自行提取的真实插件，可给 Gradle 命令增加 `-PcookerBundle=<main.bundle 的绝对路径>`；不得把样本提交到仓库。APK 输出为 `app/build/outputs/apk/debug/app-debug.apk`。

## 修改前的检查

- 先看 Git 状态，保留用户已有修改和未跟踪文件，不擅自重置、清理、暂存或提交。
- 根目录存在 `.codegraph/` 时，定位和理解代码优先使用 CodeGraph；没有时直接使用 `rg`，不自行创建索引。
- 阅读目标方法及调用点，确认进程、线程、执行时序、缓存和页面重建行为后再改动。
- 宿主混淆类名、方法、字段和资源 ID 必须来自目标版本的静态分析或实机证据，禁止猜测。
- 反编译输出可能按 Kotlin 元数据重命名包名。Hook 使用 DEX 中的真实名称，不能直接复制 JADX 的别名。

## 当前适配证据

以下映射仅针对米家 11.8.707（110817071），升级后必须重新核对。

| 功能 | 入口与约束 |
| --- | --- |
| 产品 Tab | `TabPageIndicatorNew$TabView.setTabInfo(_m_j.j87)`；`OooO00o` 为 Tab ID，隐藏 `Brand` 并保留原索引 |
| 商城中心 | `SettingMainPageV2.onViewCreated(View, Bundle)` 完成后隐藏字段 `Oooo0o` 指向的整行；资源 ID 为 `2131432991`。过早在 `onCreateView` 中隐藏会被后续初始化恢复 |
| 广告策略 | `_m_j.y64.OooO00o(String)` 返回策略；六个广告开关及加载等待归零，其他字段保留；注册后使用宿主解析器自检 |
| 开屏等待 | `_m_j.sz8` 构造完成后，在 `_m_j.in1.OooO0o()` 确认已同意隐私条款时发出无广告事件；`OooO00o(boolean)` 固定传 false |
| 电饭煲默认页 | `CatalystInstanceImpl.loadScriptFromFile(String, String, boolean)`，只处理 `chunmi.cooker.cmwy3` 的插件路径 `1016529/1034887/android/main.bundle` |

模块仅在米家主进程与 `:plugin0`～`:plugin4` 加载；页面入口和广告规则仅在主进程注册。推送、摄像头等其他进程不加载当前规则。

## 实现约束

- 普通 Java 实例字段使用 `m` 前缀，如 `mMainProcess`；DTO、VO、Entity、配置及序列化对象使用普通驼峰命名。常量沿用大写下划线风格。
- 禁止通过可变入参传出结果。单结果直接返回，多个结果优先复用现有类型，必要时新增最小结果类。
- 只为非显然逻辑和约束写简短中文注释。已知版本限制要写明；避免只有一行逻辑的多余封装。
- 不为几行代码增加依赖。Xposed API 保持 `compileOnly`，不打包进 APK；不要混用旧版 `assets/xposed_init`。
- Hook 必须限制目标包、进程和具体入口；避免全局拦截 View、网络或计时器来解决局部问题。
- 不跳过隐私同意、登录、设备认证、错误处理或实际设备控制保护。
- 不将全部初始化延时归为广告延时。保留首页就绪条件和必要兜底，区分首次绘制、开屏撤除、设备数据就绪。
- 电饭煲补丁校验版本路径、索引边界及唯一源码特征；生成独立副本，原子替换副本，保留原插件、资源基址和其他模块字节。失败时加载原插件并记录错误。
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
