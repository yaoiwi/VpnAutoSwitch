# VPN 自动开关 · LSPosed 模块

> 仓库：<https://github.com/yaoiwi/VpnAutoSwitch> · 问题反馈：<https://github.com/yaoiwi/VpnAutoSwitch/issues>

> 打开指定应用 → 自动连上系统默认 VPN；从最近任务里划掉它 → 自动断开。
> 也可以反过来：打开「关闭 VPN」列表里的应用（银行、支付等）→ 若 VPN 正在连接就自动断开。
> 可按 Wi-Fi / 移动数据分别开关，配置支持导入导出，开关 VPN 时会有 Toast 提示。

- 包名：`com.vpnautoswitch.module`
- 界面：**Miuix 0.9.4**（HyperOS 设计语言的 Compose Multiplatform 组件库）
- 最低版本：Android 8.0（API 26），Hook 点按 Android 10 ~ 14 的系统内部实现编写
- 作用域：**只需要勾选「系统框架」（system_server）**，不需要勾选目标应用

---

## 一、实现思路

模块不碰目标应用，全部逻辑跑在 `system_server` 里（LSPosed 的「系统框架」作用域），
因此天然拥有系统权限，既不需要 root 授权弹窗，也不需要给任何应用提权。

| 事件 | Hook 点 | 说明 |
| --- | --- | --- |
| 应用被打开 | `ActivityTaskManagerService.activityResumed(IBinder token, …)` | 用 `ActivityRecord.forTokenLocked(token)` 反射出包名 |
| 同上（兜底） | `ActivityRecord.setState(State.RESUMED, …)` | 部分 ROM 上 `activityResumed` 被内联时仍可用 |
| 应用被划掉 | `ActivityManagerService.handleAppDiedLocked(ProcessRecord, …)` | 划掉、强行停止、崩溃最终都会走进程死亡，只认主进程 |

VPN 的开关不是去调用某个 VPN 客户端的私有接口，而是操纵系统的「始终开启的 VPN」：

```
优先：ConnectivityManager.setAlwaysOnVpnPackageForUser(userId, pkg, lockdown, allowlist)   // 设置应用同款路径
兜底：Settings.Secure.always_on_vpn_app = <VPN 包名> / null

→ 系统自动 bind / unbind 该应用的 VpnService（VPN 连上 / 断开）
```

`ConnectivityService` / `VpnManagerService` 内部的 `Vpn` 对象持有该配置，并在
`Settings.Secure` 的 `always_on_vpn_app` 上注册了 `ContentObserver`，所以无论走 API 还是写设置，
系统都会自己完成连接/断开。system_server 的 uid 是 1000，通过 `CONTROL_VPN` 与
`WRITE_SECURE_SETTINGS` 校验都是直接放行的，不需要用户额外授权。

写设置前会把用户原有的常驻 VPN 配置备份到 `vpn_autoswitch_original_app`，
关闭时恢复；如果 system_server 中途重启，下次启动会检测 `vpn_autoswitch_active` 标记并自动清理，
不会把手机永久锁在「常驻 VPN」状态。

### 状态机

```
打开目标应用（RESUMED）
        │  aliveTargets += pkg
        ▼
   vpnOn == false ──► 写 always_on_vpn_app（VPN 连接）
        │
        │ 进程死亡（handleAppDiedLocked，且是主进程）
        ▼
   aliveTargets -= pkg
        │
        └─ 集合为空 ──► 清空设置（VPN 断开）
```

反向联动（1.24.0）：

```
打开「关闭 VPN」应用（RESUMED）
        │  offAppForeground = pkg
        ▼
   VPN 正在连接 ──► 立即断开（含手动连上的隧道）
        │
        ├─ 期间看门狗/网络切换都不再自动开启
        ▼
   该应用进程死亡 或 回到任一目标应用 ──► 解除压制
```

所有逻辑都在独立的工作线程 `VpnAutoSwitch` 上执行，AMS 的锁里只做一次「取包名 + post」，
避免在系统关键路径上做跨进程调用。

---

## 二、目录结构

```
VpnAutoSwitch/
├── app/src/main/
│   ├── assets/xposed_init                     # LSPosed 入口类名
│   ├── AndroidManifest.xml                    # xposedmodule / xposeddescription / xposedminversion
│   ├── java/com/vpnautoswitch/module/
│   │   ├── ModuleEntry.kt                     # Hook 注册（只在 system_server 生效）
│   │   ├── Config.kt                          # 配置模型（JSON）
│   │   ├── ConfigProvider.kt                  # 只读 ContentProvider，给 system_server 读配置
│   │   ├── MainActivity.kt                    # 唯一的 Activity（Compose 宿主）
│   │   ├── apps/AppsRepository.kt             # 读取已安装应用 / 图标
│   │   ├── ui/
│   │   │   ├── App.kt                         # MiuixTheme 根 + 页面切换
│   │   │   ├── AppState.kt                    # 配置与诊断状态的持有者
│   │   │   ├── DiagnosticsPolicy.kt           # 心跳新鲜度 / 模块版本判断
│   │   │   ├── DiagnosticsReport.kt           # 自检条目与可复制报告
│   │   │   ├── SelfCheckDialog.kt             # 一键自检弹窗
│   │   │   ├── MainScreen.kt                  # 设置主界面（Miuix Preference 卡片）
│   │   │   └── AppPickerScreen.kt             # 应用选择界面（搜索 + 多选）
│   │   └── system/
│   │       ├── Engine.kt                      # 状态机 + 事件回放（独立线程）
│   │       ├── EnginePolicy.kt                # 不依赖 Android 的决策与门禁
│   │       ├── RuntimeDiagnostics.kt          # 心跳 / 当前规则 / 最近失败快照
│   │       ├── ConfigRepo.kt                  # 配置缓存（3s TTL）
│   │       ├── VpnSwitcher.kt                 # 真正改 Settings.Secure 的地方
│   │       ├── ActivityRecords.kt             # ActivityRecord 反射工具
│   │       └── ProcessRecords.kt              # ProcessRecord 反射工具
│   └── test/java/...                          # JVM 单元测试（决策 / 时序 / 诊断边界）
│   └── res/values/                            # 仅保留应用名、模块描述与推荐作用域
├── build.gradle.kts / settings.gradle.kts
└── gradle/wrapper/
```

模块 App 与 system_server 是两个进程，配置通过 `content://com.vpnautoswitch.module.config/config`
这个只读 Provider 传递（不走 SharedPreferences，因为 system_server 读不到应用私有目录）。

---

## 三、编译

```bash
# 方式 1：Android Studio 直接打开工程（会自动生成 local.properties）
# 方式 2：命令行
./gradlew :app:assembleRelease     # 产物：app/build/outputs/apk/release/app-release.apk
./gradlew :app:assembleDebug       # 产物：app/build/outputs/apk/debug/app-debug.apk
```

工具链版本（Miuix 0.9.4 要求的组合）：

| 组件 | 版本 |
| --- | --- |
| AGP | 8.13.2（Gradle 8.14） |
| Kotlin / Compose Compiler | 2.4.20（`org.jetbrains.kotlin.plugin.compose`） |
| Compose 运行时 | JetBrains Compose Multiplatform 1.12.0（-android 制品） |
| compileSdk / targetSdk | 37 |
| Miuix | 0.9.4（`miuix-ui-android` + `miuix-preference-android`） |

> 说明：Compose 1.12 的 AAR 元数据声明需要 AGP 9.1+，本工程在 `app/build.gradle.kts` 里
> 关掉了 `check*AarMetadata` 任务，用 AGP 8.13.2 也能正常构建。若升级到 AGP 9.x，删掉那段
> 兼容代码即可（AGP 9 内置 Kotlin，不需要再声明 `org.jetbrains.kotlin.android`）。

Release 经 R8 压缩后是 **单 dex、约 1.3 MB**（Compose + Miuix 一起压缩进去）。
单 dex 对 Xposed 模块很有意义：模块 APK 会被整体加载进 system_server，dex 越少越不容易出问题。

已经编译好的安装包：`app-release.apk`（v1.33.0，可直接安装）。

---

## 四、安装步骤

1. 安装 `app-release.apk`（v1.33.0，覆盖旧版本即可，配置会保留）。
2. 打开 **LSPosed 管理器 → 模块 → VPN 自动开关**：
   - 启用模块；
   - **作用域只勾「系统框架」**（不要勾目标应用，勾了也没用）；
   - 重启手机（system_server 必须重启才能加载）。
3. 打开本 App：
   - 「目标应用」里勾选需要联动 VPN 的应用（可多选）；
   - 「打开时关闭 VPN」里勾选需要「一打开就断开 VPN」的应用（可多选，可留空）；
   - 「VPN 应用」里选择你的 VPN 客户端；
   - 打开「启用模块」。
4. 验证：
   - 打开目标应用 → 状态栏出现 VPN 钥匙图标；
   - 从最近任务划掉目标应用 → VPN 图标消失。

App 里的「运行状态」卡片会实时显示三项信息：

| 显示项 | 来源 | 含义 |
| --- | --- | --- |
| 模块已/尚未注入系统框架 | `vpn_autoswitch_heartbeat` | 模块每 15 秒独立续写一次心跳，不依赖是否有目标应用事件；60 秒内更新即视为仍在运行 |
| 系统当前常驻 VPN | `always_on_vpn_app` | 等于你选的 VPN 应用，说明系统已经接受了这个设置 |
| 本模块正在接管… | `vpn_autoswitch_active` | 模块正处于「已改写常驻 VPN」的接管周期中 |

下面还有「打开 LSPosed」按钮（直接跳到管理器）和「刷新」按钮，界面每 5 秒自动刷新一次。
如果 App 已更新但 system_server 仍加载旧模块，状态卡会明确提示需要重启手机。

设置页新增 **「状态诊断 → 一键自检」**：逐项检查模块心跳、LSPosed 作用域、VPN 客户端安装/启用/VpnService、
当前网络与命中规则、always-on 指向、最近失败和触发条件，并支持复制完整诊断报告。

「网络条件」里可以按网络类型分别开关本模块功能：

| 开关 | 行为 |
| --- | --- |
| Wi-Fi 网络下生效 | 关闭后，连接 Wi-Fi 时不自动开 VPN；如果 VPN 正开着且网络切到被禁用的类型，会立即断开 |
| 移动数据网络下生效 | 同上，针对移动数据 |

两个都关掉时，任何网络下都不会自动动作（界面会给出红色提示）。

「联动应用 → 打开时关闭 VPN」是反向联动：打开列表里的应用时，只要检测到 VPN 还连着
（模块接管的常驻 VPN 或你在客户端里手动连上的都算），就立刻断开，并且在它退出前不再自动连接。

| 情况 | 行为 |
| --- | --- |
| 打开列表里的应用，VPN 未连接 | 不做任何事，只记一条日志 |
| 打开列表里的应用，VPN 已连接 | 清常驻 VPN 设置 → 校验隧道 → 必要时给客户端发停止命令 / 点它的磁贴 / 强制停止客户端进程 |
| 在列表应用里停留 | 看门狗与网络切换都不会把 VPN 拉回来，避免「关了 5 秒又开」 |
| 划掉列表应用 / 回到目标应用 | 解除压制；回到目标应用时立即重新连接 |
| 同一个应用同时在「目标应用」和「打开时关闭 VPN」里 | 以**关闭**为准 |

适合加进来的典型是银行、支付、内网办公类 App——它们经常检测到 VPN 就拒绝登录或风控。

### 手动关闭 VPN 之后

在快捷设置磁贴里点断开，或者直接进 VPN 客户端点停止，模块**不会**再把 VPN 自动连回来：

| 操作 | 结果 |
| --- | --- |
| 磁贴断开 / 客户端里停止 | 看门狗停止轮询，日志出现 `vpn dropped -> stay off until a target app is reopened` |
| 继续待在这个应用里 | 保持关闭（应用内部换页、弹窗、关闭下拉面板都不算重新打开） |
| 离开后重新打开目标应用 | 立即恢复自动连接 |
| 从 VPN 客户端界面退回到目标应用 | 不触发重连，避免刚点完断开就被连回去 |
| 划掉应用 / 打开「关闭 VPN」的应用 | 不受影响，仍按原来的逻辑关闭 |

判定方式：记住上一次真正在前台的应用，只有「换了个应用再回到目标应用」才算重新打开。
判断依据是**底层链路**（VPN 自己的网络会被跳过），所以开着 VPN 切换 Wi-Fi / 移动数据也能正确识别。

「高级」里还有一个「显示开关提示（Toast）」开关（默认开启）：VPN 开启 / 关闭时会在屏幕上弹一条提示，
不需要的话关掉即可。

「模块维护」里有 **「重启使模块生效」**：安装 / 更新模块后，system_server 里跑的还是旧代码，
必须重启才会加载新版本。点它可以选择：

| 选项 | 说明 |
| --- | --- |
| 重启系统框架（推荐） | root 执行 `setprop ctl.restart zygote`，约 20 秒，只重启系统框架，不动数据与桌面 |
| 重启手机 | 先请 system_server 里的模块代为重启（不需要 root）；3 秒后 App 还活着说明模块没接手，自动退回 `su -c reboot` |
| 取消 | — |

> 重启类操作需要 root（LSPosed 用户本来就有）。第一次会弹 Magisk 授权。

「备份与恢复」里可以**导出 / 导入配置**：导出会调起系统的保存面板，存成一份 JSON
（`vpnautoswitch-config.json`）；导入则从任意位置的 JSON 恢复，换机、重装都不用重新勾应用。

---

## 五、限制与注意事项

| 限制 | 说明 |
| --- | --- |
| VPN 客户端要能被「自动拉起」 | 绝大多数客户端支持系统「始终开启的 VPN」；像 **Clash Meta** 这类不响应 always-on 的客户端，模块会自动改用**它的快捷设置磁贴**（`StatusBarManager.clickTile()`）来开关，前提是该客户端提供 QS 磁贴。两者都不支持时日志会提示，需要换客户端或在客户端里开自动连接 |
| 默认不打开 VPN 客户端界面 | 对不响应 always-on 的客户端，模块直接重启它的 `VpnService`，全程无界面；如果客户端把启动逻辑只写在 UI 里，可在「高级」打开「允许短暂打开 VPN 客户端界面」作为兜底 |
| Clash 系走「外部控制」 | Clash Meta / Clash for Android 不实现 always-on 的自动连接，模块会改用它们导出的 `START_CLASH` / `STOP_CLASH` 入口（首次打开约 12 秒，之后会记住该客户端、直接走这条路）。如果日志出现「没有 action.START_CLASH 入口」，说明该客户端版本没有这个入口，需要在客户端里打开自动连接 |
| 磁贴方式不是所有 ROM 都可用 | `StatusBarManager.clickTile` 在部分定制 ROM（如 HyperOS）上不存在，模块会依次尝试 `clickTileInternal` 与 `IStatusBarService`，都不行时自动放弃磁贴，只走 always-on；日志里会有一条「当前系统没有可用的 clickTile 接口」 |
| 手动连上的 VPN 需要强制停止才能断 | VPN 客户端里手动连上的隧道不受「始终开启的 VPN」设置控制，清除设置不会断开它；模块会在 1.5 秒后确认，必要时结束 VPN 应用进程（可在「高级」里关掉这个行为） |
| 「划掉」= 进程真的结束 | 带前台服务/常驻通知的应用（音乐、下载类）划掉后进程仍在，VPN 会继续保持，这是系统行为，不是 bug |
| 会和系统「始终开启的 VPN」设置复用同一个开关 | 模块开启期间该设置指向你的 VPN 应用，关闭时恢复原值；如果原来就把同一个 VPN 设成了常驻 VPN，那它本来就一直连着，模块没法帮你断开 |
| 只在主用户生效 | 多用户 / 工作资料里的目标应用不会触发 |
| 网络类型切换会立即生效 | 由系统的默认网络回调驱动，切到禁用类型时 VPN 会被关掉；切回允许类型且目标应用还在前台时自动重连 |
| 划掉后最多 3 秒内断开 | 进程死亡回调失效时，靠 3 秒一次的看门狗兜底，所以可能不是「瞬间」断开 |
| 系统改动 | 依赖 `ActivityTaskManagerService`、`ActivityRecord`、`ActivityManagerService` 的内部方法名，深度定制 ROM 若改名需要按日志调整 |

---

## 六、卡在「模块尚未注入系统框架」怎么办

按下面的顺序逐条确认：

1. **LSPosed 里模块开关是开着的**，并且**作用域勾选了「系统框架」**（只勾它，勾别的没用）。
2. **重启**过。system_server 只有重启才会重新加载模块，LSPosed 里的「重启系统框架」也算。
3. 打开 LSPosed 管理器 → **日志**页面，搜索 `VpnAutoSwitch`：
   - 能搜到 `hooked system_server` 说明模块加载成功；
   - 搜到 `Failed to load module` / 异常堆栈，把日志发给我；
   - 什么都没有 → 模块根本没被加载，回到第 1、2 步。
4. 日志里出现 `activityResumed hooks: 0` + `ActivityRecord.setState hooks: 0`，
   说明这个 ROM 改了 `ActivityTaskManagerService` / `ActivityRecord` 的类名或方法名。
5. 如果**日志里有 `vpn ON` 但划掉应用后没有 `vpn OFF`**，看有没有
   `watchdog: target process gone`：
   - 有 → 说明进程死亡 hook 没命中，但看门狗补上了（功能正常，只是慢 3 秒）；
   - 没有 → 目标应用划掉后进程仍然活着（带前台服务/常驻通知），VPN 保持连接属于预期行为。
6. 心跳每 15 秒独立刷新一次，和有没有打开目标应用无关。如果「状态诊断」里显示
   「超过 60 秒没有新心跳」，说明 system_server 里的模块没有运行或已被卸载，
   先检查 LSPosed 启用状态与作用域，再重启手机。不要再用「打开一次目标应用」来试探心跳。

---

## 七、日志在哪里

有三种方式，任选：

**1. App 内（最方便）**
打开 App 往下滑到「运行日志」，默认是收起的一行，点「查看运行日志」展开最近 30 条
（模块加载、开/关 VPN、进程结束、网络切换都在里面）。展开后：

- 日志上方是「复制」「刷新」「删除」三个按钮，删除会清空全部日志；
- **长按**日志区域 = 复制全部日志；
- 点「收起运行日志」即可隐藏。日志存在 `Settings.Secure` 的 `vpn_autoswitch_log` 里，
最近 60 条滚动保存，重启后仍在。

**2. LSPosed 管理器**
LSPosed → 底部「日志」→ 搜索 `VpnAutoSwitch`。

**3. adb**

```bash
adb logcat -s LSPosed-Bridge | grep VpnAutoSwitch
```

典型日志：

```
12:01:03  system_server loaded (hooks: activityResumed=1, setState=1, death=3)
12:01:05  network callback registered
12:01:30  com.example.app -> foreground
12:01:30  apply vpn ok=true (active=false, pointsToUs=false, connected=false)
12:02:10  com.example.app -> process died
12:02:10  vpn OFF (com.example.app died)
12:03:00  reconcile: vpn is off but target alive -> enable
```

> 如果 `death=0`，说明这台 ROM 的进程死亡回调换了名字，功能仍在（由 5 秒对账兜底），但会慢一点。

## 八、已知可扩展方向

- 增加「离开应用界面即断开」（现在只在进程结束时断开）；
- 同名 Wi-Fi 的 BSSID 区分（现在只按 SSID 匹配）；
- 支持多用户（`UserHandle` 维度写设置）。

---

## 版本记录

以下为近期版本记录。

| 版本 | 变更 |
| --- | --- |
| 1.33.0 | **稳定性版本**：修复延迟启动/关闭任务失效不彻底、看门狗停止后重复排队、关闭验证链误伤新会话、开机初始化顺序等问题；新增每 15 秒独立心跳与「一键自检」，可检查模块、客户端、当前规则与最近失败并复制报告 |
| 1.32.20 | 新增「指定 Wi-Fi 开 VPN / 关 VPN」：按 SSID 强制开启或关闭，优先级高于目标应用与网络类型开关 |
| 1.32.19 | 关于页去掉「开发者」分组，页面更紧凑 |
| 1.32.18 | 关于页新增「项目主页」与「问题反馈」（打开 GitHub 仓库 / Issues），去掉「检查更新」 |
| 1.32.17 | 关于页上滑加入过渡动画：头像区不再是 48dp 处硬切，而是随滚动连续**淡出 + 缩放至 92% + 0.28 倍速上移视差**，背景渐变同步渐隐；列表底部多留 140dp 滚动余量，保证淡出过程完整 |
| 1.32.16 | **移除「重启使模块生效」**：删掉设置页的「模块维护」分组、重启对话框、`softReboot` / `rebootDevice` / root 重启、`ACTION_SOFT_REBOOT` / `ACTION_REBOOT` 广播、配置轮询 `restartSeq` 与 `ack` / 保险丝等全部代码。更新模块后请直接手动重启手机（长按电源键），不再提供应用内重启 |
| 1.32.15 | **修复重启循环**：轮询用的「已处理序号」从内存变量改成持久化的 `Settings.Secure.vpn_autoswitch_restart_ack`（重启后仍有效）；首次运行只记录基线、绝不重启；`ConfigRepo.getOrNull()` 在 provider 不可用时返回 null 而不是默认 `Config()`（避免把「查不到」误当成 seq=0）；再加保险丝：10 分钟内启动超过 4 次就停用重启通道 |
| 1.32.14 | 「重启使模块生效」在模块 ≥ v58（具备配置轮询通道）时改为**一键执行**，不再弹二次确认；Toast 显示 `已请求重启（#N）`，N 为写入配置的序号，用来判断点击是否真的触发。只有模块低于 v58（还没有轮询通道）时才弹「需要手动重启」说明 |

---

## 许可证

本项目使用 **Apache License 2.0**，见 [LICENSE](LICENSE)。

```
Copyright 2026 VpnAutoSwitch contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

本项目包含或参考了第三方开源组件，各自的版权与许可见 [NOTICE](NOTICE) 与
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。

---

## 九、自动构建

推送到 `main`、推送 `v*` tag，或手动触发，都会运行 `.github/workflows/build.yml`：
GitHub 用 JDK 17 + Android SDK 运行 `:app:test` 和 `assembleRelease`，APK 在
**Actions → 最近一次 run → Artifacts** 下载；打 tag 时会自动创建 Release 并附上 APK 与 SHA-256 校验文件。

发布校验：

- tag 必须严格等于 `v` + `versionName`，例如 `v1.33.0`；
- 发布构建的签名配置必须完整，缺少或只配置一部分时直接失败，不会静默换成 debug 签名；
- 所有构建都用 `apksigner verify` 验签，不通过不会上传或发布。
