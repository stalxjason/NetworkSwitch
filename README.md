# NetworkToolbox

桌面网络监控与一键切换工具，支持 4G/5G 网络模式切换。

## 功能

- **桌面小组件**：2×1 紧凑布局，显示网络制式、运营商、信号强度，点击按钮切换 4G ↔ 5G
- **WiFi 信息**：查看已连接 WiFi 详情，支持密码查看与复制
- **网络状态**：实时显示当前网络模式、内网 IP
- **网络切换**：通过 Shizuku 或 Root 一键切换移动网络模式

## 切换原理

优先级从高到低：

1. **Shizuku** — 通过 `cmd phone set-allowed-network-types-for-users` 执行切换
2. **Root** — 通过 `su` 执行相同命令
3. **手动** — 以上均不可用时，引导用户手动切换

## 兼容性

- Android 8.0（API 26）及以上
- 已适配澎湃OS 桌面小组件

## 使用方法

1. 安装 [Shizuku](https://shizuku.rikka.app/) 并通过无线调试启动
2. 安装本应用，授予 Shizuku 权限（或使用 Root）
3. 长按桌面 → 小组件 → 添加「NetworkToolbox」

## 技术栈

| 项目 | 版本 |
|------|------|
| Kotlin | 1.9.22 |
| AGP | 8.2.2 |
| compileSdk | 35 |
| minSdk | 26 |
| Shizuku API | 13.1.5 |
| Coroutines | 1.7.3 |

## 编译

```bash
./gradlew assembleDebug
```

## 项目结构

```
app/src/main/java/com/network/toolbox/
├── App.kt                    # Application
├── MainActivity.kt           # 主界面
├── NetworkMode.kt            # 网络模式枚举（4G / 5G）
├── NetworkModeHelper.kt      # 核心切换逻辑
├── NetworkInfoHelper.kt      # SIM 卡 / 信号信息读取
├── NetworkWidgetProvider.kt  # 桌面小组件
├── ShizukuManager.kt         # Shizuku 生命周期管理
├── WiFiInfo.kt               # WiFi 信息数据类
├── WifiListAdapter.kt        # WiFi 列表适配器
└── WifiReader.kt             # WiFi 密码读取
```

## 许可证

MIT
