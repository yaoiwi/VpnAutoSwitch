<p align="center">
  <img src="docs/icon.png" width="120" alt="VpnAutoSwitch" />
</p>

<h1 align="center">VpnAutoSwitch</h1>

<p align="center">Connect the VPN when an app opens, disconnect when it is swiped away</p>
<p align="center">An LSPosed module that hooks system_server only</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+" />
  <img src="https://img.shields.io/badge/LSPosed-system__server-7B68EE?style=flat-square" alt="LSPosed" />
  <img src="https://img.shields.io/badge/Kotlin-2.4-7F52FF?style=flat-square&logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Compose-1.12-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white" alt="Compose" />
  <img src="https://img.shields.io/badge/Miuix-0.9.4-FF6B00?style=flat-square" alt="Miuix" />
  <img src="https://img.shields.io/badge/Release-1.45.0-brightgreen?style=flat-square" alt="Release" />
  <img src="https://img.shields.io/badge/License-Apache--2.0-blue?style=flat-square" alt="License" />
</p>

<p align="center">
  <a href="README.md"><img src="https://img.shields.io/badge/简体中文-Switch-6E7781?style=flat-square" alt="简体中文" /></a>
  <a href="README_EN.md"><img src="https://img.shields.io/badge/English-Current-2F6BFF?style=flat-square" alt="English" /></a>
</p>

---

## ✨ Features

| **🎯 Per-app automation** | **🛡️ Disconnect on open** |
| --- | --- |
| Connect the VPN when a target app opens<br>Disconnect when its task is removed or its main process dies | Instantly drop the VPN when banking, payment<br>or intranet apps open, and keep it off until they exit |
| **📶 Wi-Fi rules** | **🔍 Built-in self-check** |
| Force the VPN on or off for specific SSIDs<br>Takes priority over app rules and network type | Inspect module heartbeat, LSPosed scope, client status<br>current rule, always-on target and last failure |
| **🧩 Client compatibility** | **🪶 Lightweight · offline** |
| System always-on first; Clash-style external control<br>Adapter wizard tests and remembers the working mode | About 1.6 MB after R8, single dex<br>No network access, no telemetry, config stays on device |

---

## 📱 Screenshots

| Home | Settings | About |
| --- | --- | --- |
| <img src="docs/screenshots/home.jpg" width="220" alt="Home" /> | <img src="docs/screenshots/settings.jpg" width="220" alt="Settings" /> | <img src="docs/screenshots/about.jpg" width="220" alt="About" /> |

---

## 📖 Usage

1. Open [Releases](https://github.com/yaoiwi/VpnAutoSwitch/releases/latest) and download `VpnAutoSwitch-1.45.0.apk` (upgrading in place keeps your settings).
2. Open **LSPosed Manager → Modules**, enable this module, **select only the "System Framework" scope**, then reboot.
3. Open the app:
   - Pick the apps that should turn the VPN on under **Target apps**;
   - Optionally add banking or payment apps to **Disconnect on open**;
   - Choose your VPN client, then enable the module;
   - **Client adapter** detects available launch modes and can test external control / VpnService.
4. Verify: the VPN icon appears when a target app opens and disappears after swiping it away.
5. Optional: open the quick settings edit page and add the **VPN Auto Switch** tile. Tap to pause or resume automation; if the module is not running, tapping opens the app.

> If you disconnect manually from the tile or the VPN client, the module will not reconnect immediately.
> Reopening a target app is required. Most setup issues can be diagnosed from **Settings → Diagnostics → Self-check**.

Please report bugs via [Issues](https://github.com/yaoiwi/VpnAutoSwitch/issues).

The full changelog lives in [CHANGELOG.md](CHANGELOG.md) (the in-app list only shows the latest 3 entries).

---

## 🔨 Build

Requires JDK 17 and Android SDK 37:

```bash
./gradlew :app:test
./gradlew :app:assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

---

## ⚠️ Notes

| Limitation | Details |
| --- | --- |
| The client must be startable in background | System always-on is preferred; Clash-style clients use external control, a VpnService restart or a quick-settings tile. Otherwise enable auto-connect inside the client; use **Client adapter** to test the available modes |
| Manually connected tunnels need a force stop | Clearing always-on does not tear down a tunnel you started by hand. If it is still up after 1.2 s, the client process is stopped (can be disabled in Advanced) |
| Swiping a task disconnects by default | Removing a target app from recents disconnects even if a foreground service keeps its process alive. Disable **Disconnect on task removal** in Advanced to require the main process to die. If the task callback is unavailable, process death still triggers disconnect |
| Shares the system always-on VPN setting | While active, that setting points to your client and is restored on exit. Main user only; heavily customized ROMs may need log-based adjustments |

---

## ⭐ Star History

[![Star History Chart](https://api.star-history.com/svg?repos=yaoiwi/VpnAutoSwitch&type=Date)](https://star-history.com/#yaoiwi/VpnAutoSwitch&Date)

---

## 📄 License

Licensed under the [Apache License 2.0](LICENSE). Issues and pull requests are welcome.

<p align="center">Made with ❤️ for Android users</p>

<p align="center">
  <a href="https://github.com/yaoiwi/VpnAutoSwitch/stargazers">
    <img src="https://img.shields.io/github/stars/yaoiwi/VpnAutoSwitch?style=social" alt="Stars" />
  </a>
</p>
