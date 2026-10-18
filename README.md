# TwinMirror

Android 局域网远程投屏控制器：在控制端手机上实时查看并操控另一台 Android 手机，**目标手机无需安装任何 App**，只靠 ADB。

## 功能

- 实时查看目标手机屏幕（截图轮询刷新）
- 触摸点击、滑动、长按（自动映射坐标到目标分辨率）
- 导航按键：返回 / 主页 / 最近任务
- 双击唤醒目标手机屏幕
- WiFi 局域网 ADB 连接（也可用 USB 调试 + 无线调试开启）

## 工作原理

```
控制端手机                          目标手机
┌─────────────┐    WiFi/局域网    ┌─────────────┐
│ RemoteScreen │◄── adb server ──►│ adbd :5555  │
│  内置 adb 二进制 │                 │  screencap  │
└─────────────┘                   └─────────────┘
```

1. App 内置了完整的 `adb` 二进制，启动连目标手机 `IP:5555`（目标需要已开启"USB 调试（网络 adb）"，或先 USB 连一次执行 `adb tcpip 5555`）
2. 画面：循环执行 `adb shell screencap` 抓 PNG，解码显示（约 1-2 fps）
3. 触摸/按键：把屏幕坐标映射到目标分辨率后执行 `adb shell input tap/swipe/keyevent`
4. 双击唤醒：`input keyevent KEYCODE_WAKEUP` + `wm dismiss-keyguard`

## 使用方法

**目标手机（被投屏端）准备**（一次即可）：
- 打开开发者选项 → USB 调试 → 允许"网络 ADB 调试"
- 或先用数据线连电脑执行 `adb tcpip 5555`

**控制端手机**：
1. 安装 APK，打开 App（Android 11+ 会请求 nearby devices 权限）
2. 输入目标手机 IP（默认端口 5555），点连接
3. 首次连接目标手机会弹出 ADB 授权对话框，点允许
4. 开始投屏，触摸直接操作

## 构建

```bash
export ANDROID_HOME=~/Library/Android/sdk
gradle assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

- minSdk 21 / targetSdk 28
- 依赖：无第三方库，全部系统 API

## 项目结构

```
app/src/main/
├── AndroidManifest.xml
├── assets/adb                 # 内置 adb 二进制（安卓版，无需 root）
└── java/com/example/remotescreen/
    ├── MainActivity.kt        # 界面、触摸手势回调、双击唤醒
    ├── ScrcpyController.kt    # 投屏核心：截图轮询线程 + 命令队列
    └── AdbClient.kt           # adb 封装：connect / shell / exec-out
```

## 已知限制

- 画面刷新率约 1-2 fps（screencap 轮询方案），只适合验证/演示，不适合看视频
- 需要目标手机开启"网络 ADB 调试"，adb 授权对话框需手动确认一次
- 局域网内使用（不跨公网）

## 分支说明

- `master`：本 README 所述方案（ADB 截图轮询，稳定可用）
- `scrcpy-wip`：实验分支——基于 scrcpy v1.18 协议的 H.264 实时流方案（视频可达 30+ fps、点击延迟 ~10ms），已完成视频通路与注入协议验证，工程侧还差收尾工作
