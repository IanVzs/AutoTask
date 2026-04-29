# 08 · 构建 / 配置 / Premium / 周边库

## 1. 构建概览

| 项 | 值 |
|----|---|
| Gradle Plugin | `com.android.tools.build:gradle:8.1.2` |
| Gradle 版本 | 由 wrapper 管理（`gradle/wrapper/gradle-wrapper.properties`） |
| Kotlin | 1.8.21 |
| `compileSdk` / `targetSdk` / `minSdk` | 34 / 34 / 24 |
| Java 目标 | 18（source + target） |
| `dataBinding` | 开启 |
| `viewBinding` | 由 DataBinding 覆盖 |
| `aidl` | 开启 |
| ABI filters | `x86`, `arm64-v8a`, `x86_64` |
| App Id | `top.xjunz.tasker` |
| Version | `2.0` / code `18`（由根目录 `gradle.properties` 的 `APP_VERSION_NAME` / `APP_VERSION_CODE` 管理） |
| 签名 | 全构建共用 `signingConfigs.xjunz`，debug 也用同一套 |

### 1.1 `local.properties`（**必填**）

根目录下新建：

```
storeFile=xxx.jks
storePassword=xxx
keyAlias=xxx
keyPassword=xxx

# 可选：GitHub Packages（dProtect 私仓凭据；注释状态时可省略）
# gpr.user=<github username>
# gpr.token=<token with read:packages>
```

缺失会报错：`FileInputStream("local.properties")` 在 `build.gradle`（根 + app）都会读。

### 1.2 Build types

| Type | `debuggable` | `minifyEnabled` | `shrinkResources` | ProGuard | AppCenter |
|------|--------------|-----------------|-------------------|----------|-----------|
| `debug` | true | false | false | 否 | 关 |
| `release` | 默认 | true | true | `proguard-android-optimize.txt` + `proguard-rules.pro` | 开（`AppCenter.start(...)`） |

debug 构建 `versionName` 自动后缀 `-debug`。

## 2. Gradle 模块（再回顾）

- `:app`：主应用
- `:tasker-engine`：纯领域引擎（含 JUnit 测试）
- `:coroutine-ui-automator`：依赖 `:ui-automator` + `:shared-library`
- `:ui-automator`：androidx.test.uiautomator 2.2.0 的改造分支（Java）
- `:shared-library`：Kotlin 小工具
- `:hidden-apis`：`compileOnly` + `dev.rikka.tools.refine` 注解处理
- `:ssl`：JNI 加解密（**不是** TLS）

## 3. 依赖一览（`app/build.gradle`）

| 分组 | 依赖 | 用途 |
|------|------|------|
| AppCenter | `com.microsoft.appcenter:appcenter-analytics/crashes:5.0.2` | 崩溃 + 分析（仅 release） |
| Shizuku | `dev.rikka.shizuku:api/provider:13.1.5` | 特权服务 SDK |
| AndroidX KTX | `core/activity/fragment` | 基础扩展 |
| Kotlin | `kotlin-reflect` | Applet option 反射扫描 |
| Design | `androidx.appcompat:1.6.1`、`com.google.android.material:1.10.0` | Material 3 |
| Coroutines | `kotlinx-coroutines-core/android:1.7.3` | 协程 |
| Ktor | `ktor-client-core/cio`、`ktor-serialization-kotlinx-json:2.3.5` | API HTTP |
| Lifecycle | `viewmodel/livedata/runtime-ktx:2.6.2` | ViewModel / LiveData |
| HiddenApiBypass | `org.lsposed.hiddenapibypass:4.3` | P+ 绕过非 SDK 限制 |
| AppIconLoader | `me.zhanghai.android.appiconloader:1.5.0` | App 列表图标缓存 |
| Test | `junit:4.13.2`、`androidx.test.ext/espresso` | 测试 |

## 4. 资源与配置

| 资源 | 路径 |
|------|------|
| Accessibility 服务配置 | `app/src/main/res/xml/automator_service.xml`（设置 activity 指到 `top.xjunz.tasker.main.MainActivity`，**该类名路径实际不存在**，是在 `ui.main.MainActivity` 下；系统会忽略这个链接） |
| FileProvider 配置 | `app/src/main/res/xml/file_paths.xml` |
| 国际化 | `res/values` + `res/values-night`（仅 night mode；**无英文资源目录**） |
| 图标 | `ic_launcher-playstore.png` + `res/mipmap-*/` |

## 5. AssetBundles

- `presets.xtsks`：内置预设任务
- `examples.xtsks`：示例任务
- `privacy-policy.html`：隐私政策
- `index-cannon.html` / `index-continuous.html`：快速自动点击器 WebView 内容
- `confetti.js`：UI 粒子动效

## 6. ProGuard / R8

- `app/proguard-rules.pro`：主规则
- `app/dprotect-rules.pro`：dProtect 规则（**dProtect classpath 当前注释**，见 `build.gradle`）
- 各子模块 `consumer-rules.pro` / `proguard-rules.pro`
- 线上版 `v1.1.1r01/r02` 两次更新都是修 R8 的问题（见 git log）；修改混淆时需要特别关注：
  - Applet 字段扫描依赖反射（保留 `@AppletOrdinal` 字段 + 字段名）
  - AIDL Stub / Proxy 保留
  - Serialization generator 保留 @Serializable

## 7. Premium 机制（`premium/`）

### 7.1 文件

- `App.kt` 初始化：
  ```kotlin
  PremiumMixin.premiumContextStoragePath =
      File(getExternalFilesDir(""), PremiumMixin.PREMIUM_CONTEXT_FILE_NAME).path
  PremiumMixin.loadPremiumFromFileSafely()
  ```
- 存盘路径：`<external-files>/<PREMIUM_CONTEXT_FILE_NAME>`
- 文件由 `ssl` 原生库（`x.f.alpha()` / `delta()`）加密

### 7.2 API

| 接口 | 说明 |
|------|------|
| `PremiumMixin.isPremium: LiveData<Boolean>` | 全局订阅 |
| `PremiumMixin.ensurePremium()` | 需要时抛 `PaymentRequiredException` |
| `@FieldOrdinal` | 标注 DTO 字段顺序（序列化 / 反射） |

### 7.3 "白嫖版"（`upForGrabs`）

`App.upForGrabs = true` 时所有 premium 能力免费放开；当前源码中此变量实际值见 `App.kt`。`PurchaseDialog` 检测到该状态会自动 `dismiss`。

### 7.4 Premium 相关门槛（已知示例）

| 功能 | 为什么需要 premium |
|------|--------------------|
| 常驻任务同时 > 3 个 | `ResidentTaskScheduler` 限制 |
| 开机自启 Shizuku 联动 | `ShizukuServiceController.bindServiceOnBoot()` |
| A11y 模式 6 小时自动停机关闭 | `A11yEventDispatcher` |
| `launchActivity` / `takeScreenshot` / shell 命令 | `AppletOption.premiumOnly = true` |

## 8. API / 后端（`api/`）

| 文件 | 说明 |
|------|------|
| `Client.kt` | **Ktor** 单例，更新检查 / 订单接口 |
| `UpdateInfo.kt` | 更新数据 DTO |
| `SecurityUtil.kt` | 调 `ssl.x.f` 加解密 + 生成 HTTP header |
| `DTOs.kt` | `BaseDTO` + 订单 / 价格 DTO |
| `DTOExt.kt` | 金额格式化 |
| `CodeBodyReply.kt` | 回复统一结构 + 兑换 / 订单常量 |

HTTPS 用 `ktor-client-cio`；没有自定义 TrustManager / SSL pinning。

### 8.1 更新检查逻辑

更新检查是**手动触发**的：打开设置/关于页里的“版本信息”对话框，点击“检查更新”按钮后，`VersionInfoDialog` 调用 `MainViewModel.checkForUpdates()`。不要在 `MainActivity.onCreate()` 中自动调用 `checkForUpdates()`，否则 App 启动后会在 `app.updateInfo` 返回新版本时立即弹出“检测到新版本！”。

调用链：

1. `ui/main/VersionInfoDialog.kt`：按钮点击入口；无缓存时调用 `MainViewModel.checkForUpdates()`，已有新版本缓存时设置 `showUpdateDialog = true` 并刷新 `app.updateInfo`。
2. `ui/main/MainViewModel.kt`：请求 `Client.checkForUpdates()`，HTTP 200 后将响应 JSON 解析为 `UpdateInfo`，写入 `app.updateInfo`。
3. `api/UpdateInfo.kt`：`hasUpdates()` 使用服务端返回的 `build` 与当前 `BuildConfig.VERSION_CODE` 比较，`build > versionCode` 即视为有新版本。
4. `ui/main/MainActivity.kt`：观察 `app.updateInfo`；当 `hasUpdates()` 且 `showUpdateDialog == true` 时弹出更新对话框。

修改检测和下载 URL 时注意：

- 检测接口是**代码写死**的，不在配置文件里：改 `api/Client.kt` 的 `checkForUpdates()`。当前请求 `https://api.bq04.com/apps/latest/6404da310d81cc43daf6b431`，并附带 `api_token=5823a317109145ad2d9257d8c81cb641` 参数。改完需要重新打包安装。
- 如果只是换同平台应用 id / token，只改 `Client.kt` 里的 URL 和 `api_token` 即可。
- 如果换成另一个更新服务接口，不只改 URL：还要同步改 `api/UpdateInfo.kt` 的 `@Serializable data class UpdateInfo` 字段，让它匹配新接口返回 JSON；同时确认 `hasUpdates()` 仍然能用某个服务端版本字段和 `BuildConfig.VERSION_CODE` 比较。
- 新版本判断也是**代码逻辑**：`UpdateInfo.hasUpdates()` 当前使用 `build.toIntOrNull() > BuildConfig.VERSION_CODE`。如果新接口不叫 `build`，或版本格式不是纯数字，要在这里改判断逻辑。
- 下载入口也是**代码写死**的，不在配置文件里：改 `ui/main/MainActivity.kt` 中更新弹窗 positive button 的 `viewUrlSafely("https://spark.appc02.com/tasker")`。当前不会使用接口返回的下载地址，永远打开这个固定网页。改完需要重新打包安装。
- 如果希望下载按钮使用接口返回地址，需要把 `MainActivity.kt` 的固定 URL 改为读取当前 `UpdateInfo`，例如在观察 `app.updateInfo` 时使用 `it.install_url` / `it.installUrl` / `it.direct_install_url`。选择哪个字段前先确认目标地址适合浏览器打开还是适合系统安装器直接下载。

## 9. `ssl` 模块（名字误导：实际是 AES）

- JNI 库 `libssl.so`（CMake 构建；源码 `ssl/src/main/cpp/{apkprotect,md5,aes,base64}.cpp`）
- Java 入口 `ssl/src/main/java/x/f.java`：
  - `alpha(bytes)` → 加密 String
  - `delta(str)` → 解密 bytes
- 用途：
  - `api/SecurityUtil` 的 API 数据加密
  - `PremiumMixin` 的 premium 文件保护

## 10. AutoStart（开机自启）

| 文件 | 说明 |
|------|------|
| `autostart/AutoStarter.kt` | `BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED` 广播。**默认 enabled=false**，由 `AutoStartUtil` 激活 |
| `autostart/AutoStartUtil.kt` | 通过特权 `IPackageManager` 开关 Shizuku Manager + AutoTask 自启组件 |

## 11. 安全 / 权限

### Manifest 声明

- `INTERNET` / `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE`
- `SYSTEM_ALERT_WINDOW`（悬浮窗）
- `VIBRATE`
- `WAKE_LOCK`
- `RECEIVE_BOOT_COMPLETED`

### `<queries>`

- `MAIN`/`LAUNCHER` App 列表查询（App 选择器）
- HOME App 查询

### 运行时权限

- `BIND_ACCESSIBILITY_SERVICE`：走系统无障碍设置
- 悬浮窗 overlay：走 `Settings.canDrawOverlays`
- Shizuku 授权：Shizuku Manager 申请

## 12. 日志 / 调试

- `shared-library`：`logcat()` / `debugLogcat()` / `logcatStackTrace()` 全局 tag `AutoTask`
- DEBUG 构建：SnapshotObserver 记录 Applet 级别的 trace
- 崩溃：`GlobalCrashHandler` → `CrashReportActivity`，可分享

## 13. 发布 / 分发

- `README.md` 指向 [fir.xcxwo.com/tasker](https://fir.xcxwo.com/tasker) 作为最新 IPA/APK 发布点
- v1.0.0 时上线酷安

## 14. 关键源码引用

- `build.gradle`（root）
- `app/build.gradle`
- `settings.gradle`
- `app/src/main/AndroidManifest.xml`
- `App.kt`（初始化）
- `premium/PremiumMixin.kt`
- `autostart/**`
- `api/**`
- `ssl/**`

## 15. 构建 checklist（新 AI / 新机器）

1. 安装 Android Studio + 对应 SDK（compileSdk 34）
2. 放置 keystore + 写 `local.properties`
3. `./gradlew :app:assembleDebug`（首次会下载所有远程依赖）
4. 如果启用 dProtect，需要去根 `build.gradle` 取消注释 classpath 并在 `local.properties` 填 `gpr.user` / `gpr.token`
5. 安装时需要同一签名（Shizuku UserService 会校验包名 + 签名）
