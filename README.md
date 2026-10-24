# TwinMirror

Android 局域网远程投屏控制器：在控制端手机上实时查看并操控另一台 Android 手机，**目标手机无需安装任何 App**，只靠 ADB。

## 功能

- 实时查看目标手机屏幕（scrcpy 协议 H.264 硬件编解码，20-30 fps，支持 3D 游戏）
- 触摸点击、滑动（真实多点触控轨迹）、长按（scrcpy 控制通道直接注入，约 10ms 延迟）
- 导航按键：返回 / 主页 / 最近任务
- 双击唤醒目标手机屏幕
- WiFi 局域网 ADB 连接（也可用 USB 调试 + 无线调试开启）
- 地址记忆：输入过一次的 IP 会自动记住

## 工作原理

```
控制端手机                          目标手机
┌──────────────┐   WiFi/局域网   ┌──────────────────┐
│ TwinMirror    │◄── 直连 ADB ──►│ adbd :5555        │
│  AdbDevice    │               │  └ scrcpy-server   │
│  MediaCodec   │               │    (app_process)   │
│  控制通道注入  │               │    MediaCodec 编码 │
└──────────────┘               └──────────────────┘
```

1. **直连 ADB 协议**：App 内置了完整的 ADB 协议实现（CNXN/AUTH/OPEN/WRTE），通过 TCP 直接与目标机的 adbd 通信——**不再打包 adb 二进制**（旧方案在部分 ROM 上 server/client 都不可靠）。RSA 密钥首次生成并持久化，首次连接需在目标手机上允许一次（勾选"永远允许"后永久生效）。
2. **scrcpy-server**：把 41KB 的 scrcpy-server 推到 `/data/local/tmp`，通过 `app_process` 启动。服务端用 SurfaceControl + MediaCodec 在目标机上硬件编码屏幕，经 `localabstract:scrcpy` 通道流出（不需要 `adb forward`，adbd 的 `localabstract` 服务直通）。
3. **解码渲染**：控制端 MediaCodec 硬解，直接渲染到 SurfaceView（csd 预配置，SPS 变化自动跟踪旋转）。
4. **输入注入**：触摸/按键通过 scrcpy 控制通道由服务端直接注入事件（~10ms），滑动带完整轨迹，替代逐条 `input` 命令。
5. 目标机保持唤醒由 server 的 stayAwake 选项负责，断开连接时 server 随之退出并自动恢复。

## 构建

```bash
export ANDROID_HOME=~/Library/Android/sdk
gradle assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

- minSdk 26 / targetSdk 28
- 依赖：无第三方库（scrcpy-server v1.18 随资产分发，Apache-2.0）

## 项目结构

```
app/src/main/
├── AndroidManifest.xml
├── assets/scrcpy_server        # scrcpy v1.18 server（推送到目标机运行）
└── java/com/example/remotescreen/
    ├── MainActivity.kt         # 界面、触摸手势、地址记忆
    ├── ScrcpyController.kt     # 会话编排：推 server/启动/视频解码/控制通道
    ├── AdbDevice.kt            # 直连 ADB 协议（CNXN/AUTH/OPEN/WRTE + shell v2）
    ├── ControlChannel.kt       # scrcpy 控制消息（触摸/按键注入）
    ├── SpsParser.kt            # 从 SPS 解析分辨率（旋转跟踪）
    └── AspectRatioFrameLayout.kt  # 视频区宽高比自适应容器
```

## 已知限制

- scrcpy v1.18 server 在 Android 11 及以下验证可用（魅族 17 Pro / Android 11 ✓）；更新的 Android（12+）建议升级 scrcpy-server 到 2.x 并适配新协议
- 投屏过程旋转目标机屏幕：分辨率会跟随（SPS 跟踪），触摸映射自动切换
- 局域网内使用（不跨公网）

## 分支说明

- `master`：本 README 所述方案（直连 ADB 协议 + scrcpy-server）
- `scrcpy-wip`：旧实验分支（spawn 式 adb + forward，触摸消息错位未修复），已被 master 取代
- `backup/pre-h264-20261024`：重构前的截图轮询方案备份
