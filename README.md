# NetworkSwitch

一个简洁的 Android 工具：**一键切换 4G/5G 网络模式 + 查看已保存 WiFi 密码**。通过 Shizuku 或 Root 执行系统特权操作，无需手动进设置翻找。

## 功能特性

### 网络切换
- 一键切换 4G LTE / 5G NR，桌面小组件直接切换
- **双卡跟随数据卡**：自动解析默认数据卡所在槽位，换数据卡后切换自动跟随
- **方向读系统真值**：切换前回读 `get-allowed-network-types-for-users` 判断 NR 是否放行，不依赖自记账状态
- **切换后校验**：设置完成再回读确认，系统静默拒绝时如实报失败（Shizuku 校验不过自动换 Root 通道重试）
- Shizuku / Root 双通道自动降级，均不可用时引导系统设置手动切换

### WiFi 密码查看
- 查看本机所有已保存 WiFi 的明文密码，点击显隐、一键复制
- 主通道：Shizuku 以 shell 身份反射调用特权接口 `IWifiManager.getPrivilegedConfiguredNetworks()`（兼容 Android 12～17 的签名分支）
- 兜底通道：反射受限时用 Root 读取 `WifiConfigStore.xml` 解析
- 打开 App 自动弹 Shizuku 授权 / 引导启动，授权后自动加载
- WPA3、企业网（802.1X）等无明文密码的网络如实标注

### 状态信息
- 双卡信号行：信号格、运营商、制式、数据卡 ★、dBm
- IP 列表按 **SIM 卡归属**展示（ConnectivityManager 网络 → subscriptionId 映射）：数据卡 ★、待机承载标注「待机承载」、VPN / WLAN / 以太网单独展示
- 点按任意 IP 一键复制

### 桌面小组件（两款）
- **经典款 2x1**：模式 / 运营商 / 信号 + 圆形切换按钮，点空白打开 App
- **自由尺寸款**：长按可横向、纵向任意拉伸
  - 文字与按钮大小随组件尺寸自动缩放（0.6～3.5 倍）
  - 拉宽（≥1.6 倍）信息整体水平居中，窄时靠左
  - 拉高（≥3 格）自动展开：双卡信号明细、内网 IP（数据卡 / WLAN / VPN）

### 外观
- 浅色 / 深色 / 跟随系统三种模式，状态栏前景色自动适配
- 4 种主题色（蓝 / 绿 / 紫 / 橙），全局跟随
- 已适配澎湃OS（5x9 桌面网格）

## 使用方法

1. 安装 [Shizuku](https://shizuku.rikka.app/) 并通过无线调试启动（或有 Root）
2. 安装本应用，打开后按提示授权 Shizuku
3. 主界面点「切换 4G/5G」一键换网；点「查看已保存 WiFi 密码」查看密码
4. 长按桌面 → 小组件 → 添加「网络切换」或「网络切换-自由尺寸」

## 切换原理

优先级从高到低：

1. **Shizuku** — 执行 `cmd phone set-allowed-network-types-for-users -s <数据卡槽位> <掩码>`
2. **Root** — `su -c` 执行同一命令
3. **系统设置** — 均不可用或校验失败时，引导用户手动切换

当前模式通过 `cmd phone get-allowed-network-types-for-users` 回读判定（兼容二进制掩码 / 十进制位掩码 / `LTE|NR` 文本三种输出格式），设置后回读校验，确保结果真实生效。

## WiFi 密码原理

`getPrivilegedConfiguredNetworks()` 是特权 API，shell 用户被授予了调用权限。App 通过 Shizuku 拿到 WifiManager 的 binder，用 `ShizukuBinderWrapper` 包装后反射调用（Android 13+ 需在参数 Bundle 中携带 `AttributionSource`），以 shell 身份取得含密码的完整 `WifiConfiguration` 列表。反射依赖 [HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) 豁免隐藏 API 限制；高版本系统反射受阻时自动降级为 Root 读取 `WifiConfigStore.xml`。

密码仅在本机展示，App 不联网上传任何数据。

## 兼容性

- Android 12（API 31）及以上
- 已在 **小米澎湃OS 4（Android 17）** 真机验证：切换、WiFi 密码、小组件全链路
- WPA3/SAE 与企业网不回传明文密码时显示「无密码」

## 技术栈

| 项目 | 版本 |
|------|------|
| Kotlin | 2.1.0 |
| AGP | 8.7.3 |
| compileSdk | 36 |
| minSdk | 31 |
| Coroutines | 1.9.0 |
| Shizuku API | 13.1.5 |
| HiddenApiBypass | 4.3 |
| R8 | 已开启（Release） |

## 项目结构

```
app/src/main/java/io/github/stalxjason/networkswitch/
├── MainActivity.kt                     # 主界面（状态信息 / 切换 / 入口）
├── NetworkMode.kt                      # 网络模式枚举 + get-allowed 输出解析
├── NetworkModeHelper.kt                # 切换核心（动态槽位 / 真值判向 / 回读校验）
├── NetworkInfoHelper.kt                # 双卡信号、运营商信息
├── ShizukuHelper.kt                    # Shizuku 授权与命令执行（兼容远程 Process）
├── IpHelper.kt                         # IP 列表（网络 → SIM 归属）
├── AppTheme.kt                         # 深浅模式 + 主题色持久化
├── NetworkSwitchApplication.kt         # 全局初始化（隐藏 API 豁免 / 主题）
├── NetworkWidgetProvider.kt            # 经典 2x1 小组件
├── ResizableNetworkWidgetProvider.kt   # 自由尺寸小组件（文字随尺寸缩放）
└── wifi/
    ├── WifiPasswordProvider.kt         # 取密核心（binder 反射 + Root 兜底）
    ├── WifiListActivity.kt             # 密码列表页
    └── WifiListAdapter.kt              # 列表适配器（显隐 / 复制）

app/src/main/res/
├── layout/widget_network.xml           # 经典小组件布局
├── layout/widget_network_resizable.xml # 自由尺寸小组件布局
├── xml/network_widget_info.xml         # 经典小组件配置
├── xml/network_widget_resizable_info.xml # 自由尺寸配置（resizeMode 开放）
├── layout/activity_wifi_list.xml       # WiFi 密码页
└── values-night/                       # 深色主题色板
```

## 编译

```bash
./gradlew assembleDebug     # 调试包（无需签名配置）
./gradlew assembleRelease   # 释放包（R8 混淆 + 资源压缩）
```

Release 签名通过环境变量读取（`KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`），本地无 keystore 时 debug 包自动使用默认调试签名。

## CI

推送到 `main` 分支自动触发 GitHub Actions 构建 Release APK，前往 [Actions](https://github.com/stalxjason/NetworkSwitch/actions) 页面下载。

## 致谢

- [Shizuku](https://shizuku.rikka.app/) — rikka
- [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) — LSPosed
- WiFi 密码查看的特权接口调用方案参考了 zacharee/WiFiList 的公开技术思路，代码为独立重写

## 许可证

[MIT](LICENSE)
