# NetworkSwitch

一键切换 4G/5G 网络模式的桌面 Widget 应用。

## 功能

- 桌面 Widget 一键切换 4G/5G
- 支持 Shizuku 授权（无需 Root）
- 支持 Root 设备
- 兼容 Android 12 ~ Android 16 (澎湃OS 3)

## 使用方法

1. 安装 [Shizuku](https://shizuku.rikka.app/) 并通过 ADB 或无线调试启动
2. 安装本应用，点击「授权 Shizuku」
3. 长按桌面 → 添加小组件 → 选择「网络切换」

## 编译

```bash
./gradlew assembleDebug
```

## GitHub Actions

推送到 main 分支自动编译 APK，前往 Actions 页面下载。
