# 网络切换

一键切换 4G / 5G，查看本机已保存的 WiFi 密码。Android 小工具，无网络请求。

## 功能

### 网络切换
- 一键在 4G / 5G 之间切换
- 优先 Shizuku，其次 Root，都不可用时引导到系统设置手动切换
- 切换后回读系统状态确认；无法确认时如实提示，不谎报成功

### 状态信息
- 双卡信号：信号格、运营商、网络制式、数据卡标记、信号强度
- 本机 IP 列表：按 SIM 卡归属展示移动网络 / WLAN / 以太网 / VPN，点按复制

### 已保存的 WiFi 密码
- 通过 Shizuku 调用系统特权接口读取，进入页面直接显示明文
- 支持一键复制
- 开放网络、企业网没有明文密码时如实标注
- 支持中文与特殊字符 SSID
- 可选 Root 兜底：Shizuku 读取失败时用 Root 读取系统配置（默认关闭，在「授权状态」里勾选）

### 桌面小组件
- 经典 2x1：网络模式、运营商、信号 + 圆形切换按钮
- 竖排：文字在上、切换按钮在下，适合窄而高的位置
- 自由尺寸：长按任意拉伸，文字与按钮随尺寸缩放；拉高后展开双卡明细与内网 IP

### 外观
- 浅色 / 深色 / 跟随系统
- 6 种主题色，换色同时改变主卡渐变、按钮与图标底色

## 使用

1. 安装 [Shizuku](https://shizuku.rikka.app/) 并启动（或使用 Root）
2. 打开应用，按提示完成授权
3. 主界面顶部卡片一键切网；下方「连接 / 权限 / 更多」分组里查看信号、IP、已保存的 WiFi 密码、外观设置
4. 桌面长按 → 小组件 → 添加「网络切换」「网络切换-自由尺寸」或「网络切换-竖排」

## 权限

| 权限 | 用途 |
|------|------|
| `ACCESS_NETWORK_STATE` | 读取网络能力与链路信息 |
| `READ_PHONE_STATE` | 读取 SIM 卡、运营商、信号、网络制式（用户点击后才申请） |
| `READ_BASIC_PHONE_STATE` | Android 13+ 读取基础电话信息 |
| `moe.shizuku.manager.permission.API_V23` | Shizuku 授权 |

应用不申请 `INTERNET`，不发起任何网络请求；密码只在本机展示，不上传、不写入本地存储。

## 构建

需要 JDK 17、Android SDK 36、build-tools 34.0.0。

```bash
./gradlew assembleDebug      # 生成 debug 包
./gradlew testDebugUnitTest  # 单元测试
./gradlew lintDebug          # 静态检查
```

## 兼容性

- Android 12（API 31）及以上
- 已在 Android 16 模拟器与 Android 17（HyperOS 4）真机验证

## 致谢

- [Shizuku](https://shizuku.rikka.app/)
- [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass)
- [zacharee/WiFiList](https://github.com/zacharee/WiFiList)
