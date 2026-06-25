# NetworkSwitch

一个简洁的 Android 工具，一键切换 4G / 5G 网络模式。通过 Shizuku 或 Root 执行系统命令，无需手动进设置翻找开关。

## 功能特性

- 一键切换 4G LTE / 5G NR 网络模式
- 桌面小组件（2x1），点击即切换，显示运营商和信号强度
- 内网 IP 自动显示，外网 IP 点击查询
- Shizuku / Root 双通道，自动降级
- 深色主题，Material Design 3 风格

## 桌面小组件

| 模式 | 运营商 | 信号 | 切换按钮 |
|:---:|:------:|:----:|:--------:|

- 2x1 尺寸，适配 5x9 桌面网格
- 左侧显示当前网络模式（4G LTE / 5G NR）、运营商、信号格数
- 右侧圆形按钮一键切换，无需打开 App
- 点击小组件空白区域可打开主界面

## 主界面

- 当前网络模式（4G / 5G）大字显示
- 内网 IPv4 / IPv6 自动获取
- 外网 IPv4 / IPv6 点击查询（数据源：ipw.cn）
- Shizuku 授权状态、Root 可用状态
- 快捷操作：切换、授权 Shizuku、打开系统网络设置、刷新

## 切换原理

优先级从高到低：

1. **Shizuku** — 通过 ADB shell 执行 `cmd phone set-allowed-network-types-for-users`，无需 Root
2. **Root** — 通过 `su` 执行相同命令
3. **系统设置** — 以上均不可用时，引导用户手动切换

## 兼容性

- Android 12（API 31）及以上
- 已适配澎湃OS 3（5x9 桌面网格）

## 使用方法

1. 安装 [Shizuku](https://shizuku.rikka.app/) 并通过无线调试启动
2. 安装本应用，授予 Shizuku 权限（或使用 Root）
3. 长按桌面 → 小组件 → 添加「网络切换开关」

## 技术栈

| 项目 | 版本 |
|------|------|
| Kotlin | 2.1.0 |
| AGP | 8.7.3 |
| compileSdk | 36 |
| minSdk | 31 |
| Coroutines | 1.9.0 |
| Shizuku API | 13.1.5 |
| R8 | 已开启（Release） |

## 项目结构

```
app/src/main/java/io/github/stalxjason/networkswitch/
├── MainActivity.kt          # 主界面
├── NetworkMode.kt           # 网络模式枚举（4G / 5G）
├── NetworkModeHelper.kt     # 核心切换逻辑（Shizuku → Root → 设置）
├── NetworkInfoHelper.kt     # 信号强度、运营商、网络类型信息
├── ShizukuHelper.kt         # Shizuku 授权与命令执行
├── IpHelper.kt              # 内网/外网 IP 获取
└── NetworkWidgetProvider.kt # 桌面小组件（2x1）

app/src/main/res/
├── layout/widget_network.xml          # 小组件布局
├── xml/network_widget_info.xml        # 小组件尺寸配置
├── drawable/widget_background.xml     # 小组件背景
├── drawable/widget_btn_bg.xml         # 切换按钮背景
├── drawable/ic_toggle_switch.xml      # 切换图标
└── drawable/ic_toggle_switch_5g.xml   # 5G 切换图标
```

## 编译

```bash
./gradlew assembleRelease
```

Release 构建已开启 R8 代码混淆和资源压缩。签名配置通过环境变量读取。

## CI

推送到 `main` 分支自动触发 GitHub Actions 构建 Release APK，前往 [Actions](https://github.com/stalxjason/NetworkSwitch/actions) 页面下载。

## 许可证

[MIT](LICENSE)