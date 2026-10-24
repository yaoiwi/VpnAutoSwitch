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

## 一、安装步骤

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

「备份与恢复」里可以**导出 / 导入配置**：导出会调起系统的保存面板，存成一份 JSON
（`vpnautoswitch-config.json`）；导入则从任意位置的 JSON 恢复，换机、重装都不用重新勾应用。

---

## 二、限制与注意事项

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
| 划掉后最多 5 秒内断开 | 进程死亡回调失效时，靠 5 秒一次的看门狗兜底，所以可能不是「瞬间」断开 |
| 系统改动 | 依赖 `ActivityTaskManagerService`、`ActivityRecord`、`ActivityManagerService` 的内部方法名，深度定制 ROM 若改名需要按日志调整 |

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
