# NetworkSwitch

一键切换 4G/5G 网络模式。支持 Shizuku / Root。

## 功能

### 桌面小组件（3×1 白底）

- 左侧 4G / 中间切换按钮 / 右侧 5G
- 当前模式高亮显示，非当前模式灰显
- 点击任意位置即可切换

### 主界面

- **当前网络**：大字显示 4G / 5G 模式
- **IP 信息**：
  - 内网 IPv4 / IPv6（打开自动获取）
  - 外网 IPv4 / IPv6（点击手动查询，数据源：ipw.cn）
- **状态**：Shizuku 授权状态、Root 可用状态
- **快捷操作**：切换按钮、授权 Shizuku、打开系统网络设置

### 切换方式

| 方式 | 说明 |
|------|------|
| Shizuku | 无需 Root，通过 ADB 无线调试授权 |
| Root | 直接执行 `settings put` 命令 |

## 兼容性

- Android 12 ~ Android 16
- 澎湃OS 3（5×9 桌面网格）

## 使用方法

1. 安装 [Shizuku](https://shizuku.rikka.app/)，通过 ADB 或无线调试启动
2. 安装本应用，点击「授权 Shizuku」
3. 长按桌面 → 小组件 → 添加「网络切换」（3×1）

## 编译

```bash
./gradlew assembleRelease
```

## CI

推送到 `main` 分支自动编译 Release APK（签名），前往 Actions 页面下载。

## 许可

MIT
