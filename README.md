
# AutoTask

一款支持[Shizuku](https://github.com/RikkaApps/Shizuku)和辅助功能的**自动任务**工具。

## 简介

本应用专注于帮助您执行自动任务，相比于其他同类产品，本应用具有以下特点：

- 支持多种启动模式（**Shizuku**和辅助功能）
- 支持自定义常驻任务任务和一次性任务
- 支持手势录制，审查布局树等
- 不需要刻意保活便可常驻后台（两种模式默认系统保活）
- 省电且占用系统资源较少（事件驱动+协程，执行长时间任务也不阻塞CPU）
- 代码开源，安全可信
- Material 3 风格UI，实用美观

## 截图

| <img src="/app/screenshots/Screenshot_light_1.png" alt="pic_main" style="zoom:25%;" /> | <img src="/app/screenshots/Screenshot_light_2.png" alt="pic_test" style="zoom:25%;" /> | <img src="/app/screenshots/Screenshot_night_1.png" style="zoom:25%;" /> | <img src="/app/screenshots/Screenshot_night_2.png" style="zoom:25%;" /> |
|----------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------|-------------------------------------------------------------------------|-------------------------------------------------------------------------|

## 实现

### Shizuku模式

利用Shizuku授予特权，使用安卓内置的 [UiAutomation](https://cs.android.com/android/platform/superproject/+/master:frameworks/base/core/java/android/app/UiAutomation.java)框架用于任务执行，详见 [ShizukuAutomatorService](https://github.com/xjunz/AutoTask/blob/master/app/src/main/java/top/xjunz/tasker/service/ShizukuAutomatorService.kt)。

> **注: **因为安卓系统只能注册一个`UiAutomation`服务，所以当自动任务服务激活时，其他`UiAutomation`会注册失败。如果您有需要（如自动化测试、Thanox），请先停止自动任务服务。反之亦然。
### 辅助功能模式

使用辅助功能自带的API框架用于实现任务执行，详见[A11yAutomatorService](https://github.com/xjunz/AutoTask/blob/master/app/src/main/java/top/xjunz/tasker/service/A11yAutomatorService.kt)。

## 构建

构建环境要求：

- JDK 18。请通过 `JAVA_HOME`、IDE 或用户级 `~/.gradle/gradle.properties` 配置本机 JDK，不要把个人路径提交到仓库。
- Android SDK Platform 36、Build Tools 36.0.0。
- Gradle 由仓库内 wrapper 管理，直接使用 `./gradlew` 或 `make` 即可。

常用命令：

```bash
make help
make debug
make test
make lint
```

如果 Android SDK 不在默认位置，可以在根目录创建未提交的 `local.properties`：

```properties
sdk.dir=/path/to/Android/Sdk
```

签名配置仅在你需要使用自己的签名打包时填写，同样放在未提交的 `local.properties`：

```properties
storeFile=xxx
storePassword=xxx
keyAlias=xxx
keyPassword=xxx
```

多台设备同时连接时，`make install-apk`、`make run` 等 adb 命令可以通过 `DEVICE=<adb 序列号>` 指定设备。

## 注意事项

请在开源协议约束范围内使用本项目，禁止用于非法用途。

## License

本项目基于 [xjunz/AutoTask](https://github.com/xjunz/AutoTask) 派生，遵循 [Apache-2.0 License](LICENSE) 开源。分发或修改时请保留原始版权与许可证声明。

*Original work Copyright 2023 xjunz*

*Modifications Copyright 2026 AutoTask contributors*
