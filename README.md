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
- 查看本机所有已保存 WiFi 的明文密码，点击显隐、一键复制（明文复制标记为敏感内容）
- 主通道：Shizuku 以 shell 身份反射调用特权接口 `IWifiManager.getPrivilegedConfiguredNetworks()`（兼容 Android 12～17 的签名分支）
- 兜底通道：反射受限时用 Root 读取 `WifiConfigStore.xml` 解析
- 打开 App 自动弹 Shizuku 授权 / 引导启动，授权后自动加载；返回后自动重试
- WPA3、企业网（802.1X）等无明文密码的网络如实标注
- 展开条目显示 MAC、频段信道（按 ITU-R SM.3290 计算）、是否隐藏、认证方式明细

### 状态信息
- 双卡信号行：信号格、运营商、制式、数据卡 ★、dBm；按 `dataNetworkType` 匹配对应 RAT 的信号强度，双频注册时不会拿错
- IP 列表按 **SIM 卡归属**展示（ConnectivityManager 网络 → subscriptionId 映射）：数据卡 ★、待机承载标注「待机承载」、VPN / WLAN / 以太网单独展示
- 点按任意 IP 一键复制

### 桌面小组件（两款）
- **经典款 2x1**：模式 / 运营商 / 信号 + 圆形切换按钮，点空白打开 App
- **自由尺寸款**：长按可横向、纵向任意拉伸
  - 文字与按钮大小随组件尺寸自动缩放（0.6～3.5 倍）
  - 拉宽（≥1.6 倍）信息整体水平居中，窄时靠左
  - 拉高（≥3 格）自动展开：双卡信号明细、内网 IP（数据卡 / WLAN / VPN）
- 小组件切换按钮带令牌校验，外部应用发显式广播无法触发切网

### 外观
- 浅色 / 深色 / 跟随系统三种模式，状态栏前景色自动适配
- 4 种主题色（蓝 / 绿 / 紫 / 橙），全局跟随
- 已适配澎湃OS（5x9 桌面网格）
- 全部 UI 文案集中在 `res/values/strings.xml`

## 使用方法

1. 安装 [Shizuku](https://shizuku.rikka.app/) 并通过无线调试启动（或有 Root）
2. 安装本应用，打开后按提示授权 Shizuku
3. 主界面点「切换 4G/5G」一键换网；点「查看已保存 WiFi 密码」查看密码
4. 长按桌面 → 小组件 → 添加「网络切换」或「网络切换-自由尺寸」

## 权限

| 权限 | 用途 |
|------|------|
| `ACCESS_NETWORK_STATE` | 读取网络能力与链路信息 |
| `READ_PHONE_STATE` | 读取 SIM 卡、运营商、信号、网络制式 |
| `READ_BASIC_PHONE_STATE` | Android 13+ 上同上（常量自 API 33 起才存在，运行时按版本判断） |
| `ACCESS_WIFI_STATE` | 声明性补充，密码读取实际走 Shizuku 特权接口 |
| `moe.shizuku.manager.permission.API_V23` | Shizuku 授权 |

App 自身**不发起任何网络请求**，不申请 `INTERNET`。密码仅在本机展示，不上传、不持久化。

`android.hardware.telephony` 声明为 `required="false"`，平板 / 模拟器 / ChromeOS 可正常安装。

## 切换原理

优先级从高到低：

1. **Shizuku** — 执行 `cmd phone set-allowed-network-types-for-users -s <数据卡槽位> <掩码>`
2. **Root** — `su -c` 执行同一命令
3. **系统设置** — 均不可用或校验失败时，引导用户手动切换

当前模式通过 `cmd phone get-allowed-network-types-for-users` 回读判定（兼容二进制掩码 / 十进制位掩码 / `LTE|NR` 文本三种输出格式），设置后回读校验，确保结果真实生效。掩码解析带边界保护，避免错误文本里的槽位号被误读成掩码。

## WiFi 密码原理

`getPrivilegedConfiguredNetworks()` 是特权 API，shell 用户被授予了调用权限。App 通过 Shizuku 拿到 WifiManager 的 binder，用 `ShizukuBinderWrapper` 包装后反射调用（Android 13+ 需在参数 Bundle 中携带 `AttributionSource`），以 shell 身份取得含密码的完整 `WifiConfiguration` 列表。反射依赖 [HiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) 豁免隐藏 API 限制；高版本系统反射受阻时自动降级为 Root 读取 `WifiConfigStore.xml`。

## 兼容性

- Android 12（API 31）及以上
- 已在 **小米澎湃OS 4（Android 17）** 真机验证：切换、WiFi 密码、小组件全链路
- WPA3/SAE 与企业网不回传明文密码时显示「无密码」

## 构建

### 环境

JDK 17 + Android SDK（platform 36、build-tools 34.0.0）。Wrapper 锁定 Gradle 8.9。

Windows 下先设好环境变量：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
$env:ANDROID_SDK_ROOT = 'D:\Program Files\Android\Android SDK'
```

### 打包

```bash
./gradlew assembleDebug     # 调试包（AGP 自动生成 debug 签名）
./gradlew assembleRelease   # Release 包（R8 混淆 + 资源压缩）
```

Release 与 Debug 差别很大：本项目 Release 约 2.4 MB，Debug 约 14 MB。另外 Debug 包 Manifest 里 `android:debuggable="true"`，进程可被外部 attach —— 已 root 设备上建议装 Release 包。

### 签名

Release 签名从环境变量读取，三项缺一即**静默降级为未签名包**（文件名 `app-release-unsigned.apk`，装不上），不会报错：

| 环境变量 | 含义 |
|----------|------|
| `KEYSTORE_PASSWORD` | keystore 文件口令 |
| `KEY_ALIAS` | 密钥别名 |
| `KEY_PASSWORD` | 密钥口令 |

keystore 放在 `app/networkswitch.keystore`（已被 `*.keystore` 忽略，不会进版本库）。新建：

```bash
keytool -genkeypair -keystore networkswitch.keystore -alias networkswitch \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass <口令> -keypass <口令> \
  -dname "CN=NetworkSwitch, OU=Dev, O=NetworkSwitch, L=Beijing, ST=Beijing, C=CN"
```

版本号可通过 `VERSION_CODE` / `VERSION_NAME` 覆盖，不设则用仓库默认值。

> keystore 丢了 = 以后发布的包签名对不上，用户必须卸载重装。务必离线备份，缺文件或缺口令都打不开。

### 验证

```bash
./gradlew testDebugUnitTest   # 39 个单元测试
./gradlew lintDebug           # 0 error
```

单元测试覆盖：掩码解析、频段信道换算（ITU-R SM.3290）、RAT 强度类型映射、SIM 归属、Shizuku 状态机。

## CI

推送到 `main` 或手动触发（`workflow_dispatch`，可指定版本号）自动构建：

- `testDebugUnitTest` + `lintDebug`
- 始终产出 Debug APK
- 仓库配了 `KEYSTORE_BASE64` 等 Secrets 时额外产出签名 Release APK；未配置则跳过，流水线仍然通过

前往 [Actions](https://github.com/stalxjason/NetworkSwitch/actions) 页面下载产物。

## 项目结构

```
app/src/main/java/io/github/stalxjason/networkswitch/
├── MainActivity.kt                     # 主界面（状态信息 / 切换 / 入口）
├── NetworkMode.kt                      # 网络模式枚举 + get-allowed 输出解析
├── NetworkModeHelper.kt                # 切换核心（动态槽位 / 真值判向 / 回读校验）
├── NetworkInfoHelper.kt                # 双卡信号、运营商信息（按 RAT 选强度）
├── ShizukuHelper.kt                    # Shizuku 授权与命令执行（兼容远程 Process）
├── IpHelper.kt                         # IP 列表（网络 → SIM 归属）
├── IpListAdapter.kt                    # IP 列表适配器（DiffUtil + Spannable 排版）
├── Clipboard.kt                        # 剪贴板写入（敏感标记）
├── AppTheme.kt                         # 深浅模式 + 主题色持久化
├── NetworkSwitchApplication.kt         # 全局初始化（隐藏 API 豁免 / 主题）
├── NetworkWidgetProvider.kt            # 经典 2x1 小组件
├── ResizableNetworkWidgetProvider.kt   # 自由尺寸小组件（文字随尺寸缩放）
└── wifi/
    ├── WifiPasswordProvider.kt         # 取密核心（binder 反射 + Root 兜底）
    ├── WifiListActivity.kt             # 密码列表页
    └── WifiListAdapter.kt              # 列表适配器（显隐 / 复制 / 展开详情）

app/src/main/res/
├── layout/widget_network.xml           # 经典小组件布局
├── layout/widget_network_resizable.xml # 自由尺寸小组件布局
├── layout/activity_main.xml            # 主界面
├── layout/activity_wifi_list.xml       # WiFi 密码页
├── layout/item_wifi.xml                # WiFi 条目
├── layout/item_ip.xml                  # IP 条目
├── xml/network_widget_info.xml         # 经典小组件配置
├── xml/network_widget_resizable_info.xml # 自由尺寸配置（resizeMode 开放）
├── values/                             # 文案 / 色板 / 主题
└── values-night/                       # 深色主题

app/src/test/java/                      # 39 个单元测试（5 个测试类）
```

## 技术栈

| 项目 | 版本 |
|------|------|
| Kotlin | 2.1.0 |
| AGP | 8.7.3 |
| Gradle | 8.9 |
| JDK | 17 |
| compileSdk | 36 |
| minSdk | 31 |
| Coroutines | 1.9.0 |
| Shizuku API | 13.1.5 |
| HiddenApiBypass | 4.3 |
| R8 | 已开启（Release，minify + shrinkResources） |

## 致谢

- [Shizuku](https://shizuku.rikka.app/) — rikka
- [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) — LSPosed
- WiFi 密码查看的特权接口调用方案参考了 zacharee/WiFiList 的公开技术思路，代码为独立重写

## 许可证

[MIT](LICENSE)
