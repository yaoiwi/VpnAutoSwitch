# 第三方组件与许可

本项目（VpnAutoSwitch）以 **Apache License 2.0** 发布。下表列出运行期使用或参考的开源组件、
各自许可与版权归属。分发本项目的二进制或源码时，请一并保留本文件与 `LICENSE`。

| 组件 | 版本 | 许可 | 版权 | 用途 |
| --- | --- | --- | --- | --- |
| [HyperIsland](https://github.com/1812z/HyperIsland) | 3.2.1 | MIT | © 2026 1812z | 液态玻璃导航栏、Lens 折射着色器、模糊栏、页面骨架与信息架构（已按本项目需求修改） |
| [Miuix](https://github.com/compose-miuix-ui/miuix) | 0.9.4 | Apache-2.0 | © compose-miuix-ui contributors | Compose UI 组件库（Card / TopAppBar / Switch / 图标 / 模糊 / 着色器） |
| [Jetpack Compose / AndroidX](https://developer.android.com/jetpack/androidx) | 1.12.0 / 1.13.0 | Apache-2.0 | © The Android Open Source Project | UI 运行时、Activity Compose、Pager、Lifecycle |
| [Kotlin](https://kotlinlang.org) · [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | 2.4.20 | Apache-2.0 | © JetBrains s.r.o. | 开发语言与协程调度 |
| [Xposed API](https://github.com/LSPosed/LSPosed) | api 82 | Apache-2.0 | © rovo89, LSPosed contributors | 模块注入接口（`compileOnly`，运行时由 LSPosed 提供） |
| [Android Open Source Project](https://source.android.com) | — | Apache-2.0 | © Google LLC | `Settings.Secure`、`ActivityManager`、`PackageManager` 等系统接口 |

## 说明

- 本项目**不分发** LSPosed 本体（GPL-3.0），仅在运行时依赖其提供的框架与 Xposed API。
- HyperIsland 为 MIT 许可，与本项目的 Apache-2.0 **兼容**；相关文件在移植时已做修改，
  修改内容包括：包名、颜色令牌改为 Miuix 主题色、移除与本项目无关的页面与资源。
- Miuix、Compose、AndroidX、Kotlin 均为 Apache-2.0，与本项目许可一致。

## MIT License（HyperIsland）

```
MIT License

Copyright (c) 2026 1812z

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Apache License 2.0

本项目及其 Apache-2.0 依赖的完整许可文本见根目录 [`LICENSE`](LICENSE)，
或 <http://www.apache.org/licenses/LICENSE-2.0>。
