.DEFAULT_GOAL := help

LOCAL_SDK_DIR := $(shell sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null)
SDK_DIR ?= $(if $(LOCAL_SDK_DIR),$(LOCAL_SDK_DIR),$(HOME)/Android/Sdk)
USER_GRADLE_JAVA_HOME := $(shell sed -n 's/^org\.gradle\.java\.home=//p' "$(HOME)/.gradle/gradle.properties" 2>/dev/null)
JAVA_HOME ?= $(if $(USER_GRADLE_JAVA_HOME),$(USER_GRADLE_JAVA_HOME),$(shell if [ -d "$(HOME)/jdks/temurin-18.0.2.1" ]; then printf '%s' "$(HOME)/jdks/temurin-18.0.2.1"; elif [ -d "/opt/android-studio/jbr" ]; then printf '%s' "/opt/android-studio/jbr"; fi))
REQUIRED_JAVA_MAJOR ?= 18

export JAVA_HOME
export ANDROID_HOME := $(SDK_DIR)
export ANDROID_SDK_ROOT := $(SDK_DIR)
export PATH := $(if $(JAVA_HOME),$(JAVA_HOME)/bin:,)$(SDK_DIR)/platform-tools:$(SDK_DIR)/emulator:$(SDK_DIR)/cmdline-tools/latest/bin:$(PATH)

GRADLE := ./gradlew
GRADLE_FLAGS ?= --console=plain
ADB := $(SDK_DIR)/platform-tools/adb
EMULATOR := $(SDK_DIR)/emulator/emulator
APP_ID := top.xjunz.tasker
MAIN_ACTIVITY := .ui.main.MainActivity
DEBUG_APK := app/build/outputs/apk/debug/app-debug.apk

# 可选选择器：
#   make install DEVICE=<adb 设备序列号>
#   make emulator AVD=<模拟器名称>
DEVICE ?=
ADB_DEVICE := $(if $(DEVICE),-s $(DEVICE),)
AVD ?=

.PHONY: help env check-java check-sdk doctor wrapper tasks devices emulators emulator \
	debug build release apk install install-apk reinstall uninstall run stop stop-service restart \
	test unit-test connected-test lint clean clean-build logs logcat clear-logs

help:
	@printf '%s\n' \
		'自动任务命令快捷入口' \
		'' \
		'环境检查：' \
		'  make env              查看当前使用的 JDK、Android SDK、包名和设备选择' \
		'  make check-java       检查当前 Java 是否符合项目要求' \
		'  make doctor           检查命令行环境是否能构建，并显示已连接手机/模拟器' \
		'  make devices          列出 adb 设备' \
		'  make emulators        列出可用 Android 模拟器' \
		'  make emulator AVD=x   启动指定 Android 模拟器' \
		'' \
		'构建：' \
		'  make debug            打一个可安装调试包，输出 app/build/outputs/apk/debug/app-debug.apk' \
		'  make build            和 make debug 一样，习惯输入 build 时使用' \
		'  make release          打正式包；需要本机签名配置，否则可能失败' \
		'  make lint             扫描常见代码/资源/Manifest 问题' \
		'  make test             运行 JVM 单元测试，不需要连接手机' \
		'  make clean            删除构建产物；遇到缓存/增量构建异常时先用它清理' \
		'' \
		'设备操作：' \
		'  make install          重新打 debug 包，并通过 adb 安装到当前连接的真机/模拟器' \
		'  ANDROID_SERIAL=<序列号> make install   多台设备同时连接时，指定 Gradle 安装目标' \
		'  make install-apk      不重新构建，通过 adb 把已有 debug APK 安装到当前连接的真机/模拟器' \
		'  make reinstall        不重新构建，通过 adb 覆盖安装已有 debug APK 到当前连接的真机/模拟器' \
		'  make uninstall        通过 adb 从当前连接的真机/模拟器卸载 $(APP_ID)' \
		'  make run              通过 adb 在当前连接的真机/模拟器上打开 $(APP_ID)' \
		'  make restart          通过 adb 在当前连接的真机/模拟器上强停后重新打开 $(APP_ID)' \
		'  make stop-service     杀掉 Shizuku 远程服务进程；覆盖安装后服务仍跑旧代码时使用' \
		'  make logs             通过 adb 只看当前连接的真机/模拟器上 $(APP_ID) 进程日志' \
		'  make logcat           通过 adb 查看当前连接的真机/模拟器全部日志，输出很多' \
		'  make clear-logs       通过 adb 清空当前连接的真机/模拟器日志缓冲区' \
		'' \
		'常用变量：' \
		'  DEVICE=<序列号>       多台真机/模拟器同时连接时，指定 adb 要操作哪一台' \
		'  AVD=<名称>            为 make emulator 选择模拟器' \
		'  SDK_DIR=<路径>        覆盖 Android SDK 路径' \
		'  JAVA_HOME=<路径>      覆盖 JDK 路径'

env:
	@printf 'JAVA_HOME=%s\n' "$(JAVA_HOME)"
	@printf 'ANDROID_HOME=%s\n' "$(ANDROID_HOME)"
	@printf 'ANDROID_SDK_ROOT=%s\n' "$(ANDROID_SDK_ROOT)"
	@printf 'REQUIRED_JAVA_MAJOR=%s\n' "$(REQUIRED_JAVA_MAJOR)"
	@printf 'APP_ID=%s\n' "$(APP_ID)"
	@printf 'DEVICE=%s\n' "$(DEVICE)"

check-java:
	@command -v java >/dev/null 2>&1 || { \
		echo "未找到 Java。请安装 JDK $(REQUIRED_JAVA_MAJOR)，或设置 JAVA_HOME=<JDK 路径>。"; \
		exit 1; \
	}
	@major="$$(java -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | sed -n '1p')"; \
	if [ -z "$$major" ]; then \
		echo "无法识别 Java 版本，请检查 JAVA_HOME 或 java 命令。"; \
		java -version; \
		exit 1; \
	fi; \
	if [ "$$major" != "$(REQUIRED_JAVA_MAJOR)" ]; then \
		echo "当前 Java 主版本是 $$major，但本项目需要 JDK $(REQUIRED_JAVA_MAJOR)。"; \
		echo "请设置 JAVA_HOME=<JDK $(REQUIRED_JAVA_MAJOR) 路径>，或在用户级 ~/.gradle/gradle.properties 配置 org.gradle.java.home。"; \
		exit 1; \
	fi

check-sdk:
	@test -d "$(SDK_DIR)" || { \
		echo "未找到 Android SDK：$(SDK_DIR)"; \
		echo "请在 local.properties 配置 sdk.dir=<Android SDK 路径>，或执行 make ... SDK_DIR=<Android SDK 路径>。"; \
		exit 1; \
	}
	@test -x "$(ADB)" || { echo "未找到 adb：$(ADB)"; exit 1; }

doctor: check-java check-sdk wrapper
	@printf '\n[Java]\n'
	@java -version
	@printf '\n[Gradle]\n'
	@$(GRADLE) --version
	@printf '\n[Android SDK]\n'
	@printf 'SDK_DIR=%s\n' "$(SDK_DIR)"
	@test -x "$(ADB)" && "$(ADB)" version || { echo "未找到 adb：$(ADB)"; exit 1; }
	@printf '\n[设备]\n'
	@$(ADB) devices

wrapper:
	@chmod +x $(GRADLE)

tasks: check-java check-sdk wrapper
	@$(GRADLE) tasks $(GRADLE_FLAGS)

devices: check-sdk
	@$(ADB) devices

emulators: check-sdk
	@$(EMULATOR) -list-avds

emulator: check-sdk
	@test -n "$(AVD)" || { echo "用法：make emulator AVD=<模拟器名称>"; exit 1; }
	@$(EMULATOR) -avd "$(AVD)"

debug build apk: check-java check-sdk wrapper
	@$(GRADLE) :app:assembleDebug $(GRADLE_FLAGS)
	@printf '\nDebug APK 路径：%s\n' "$(DEBUG_APK)"

release: check-java check-sdk wrapper
	@$(GRADLE) :app:assembleRelease $(GRADLE_FLAGS)

install: check-java check-sdk wrapper
	@$(GRADLE) :app:installDebug $(GRADLE_FLAGS)

install-apk reinstall: check-sdk
	@test -f "$(DEBUG_APK)" || { echo "缺少 $(DEBUG_APK)，请先运行 make debug。"; exit 1; }
	@$(ADB) $(ADB_DEVICE) install -r "$(DEBUG_APK)"

uninstall: check-sdk
	@$(ADB) $(ADB_DEVICE) uninstall "$(APP_ID)" || true

run: check-sdk
	@$(ADB) $(ADB_DEVICE) shell am start -n "$(APP_ID)/$(MAIN_ACTIVITY)"

stop: check-sdk
	@$(ADB) $(ADB_DEVICE) shell am force-stop "$(APP_ID)"

stop-service: check-sdk
	@pids="$$( $(ADB) $(ADB_DEVICE) shell pidof "$(APP_ID):service" 2>/dev/null | tr -d '\r' )"; \
	if [ -n "$$pids" ]; then \
		echo "正在结束 Shizuku 远程服务进程：$$pids"; \
		$(ADB) $(ADB_DEVICE) shell kill $$pids || true; \
	else \
		echo "未发现正在运行的 Shizuku 远程服务进程。"; \
	fi

restart: stop run

unit-test test: check-java check-sdk wrapper
	@$(GRADLE) testDebugUnitTest $(GRADLE_FLAGS)

connected-test: check-java check-sdk wrapper
	@$(GRADLE) connectedDebugAndroidTest $(GRADLE_FLAGS)

lint: check-java check-sdk wrapper
	@$(GRADLE) :app:lintDebug $(GRADLE_FLAGS)

clean: check-java wrapper
	@$(GRADLE) clean $(GRADLE_FLAGS)

clean-build:
	@rm -rf .gradle build app/build */build

logs: check-sdk
	@pid="$$( $(ADB) $(ADB_DEVICE) shell pidof "$(APP_ID)" 2>/dev/null | tr -d '\r' )"; \
	if [ -n "$$pid" ]; then \
		echo "正在显示 $(APP_ID) 的 logcat，pid=$$pid"; \
		$(ADB) $(ADB_DEVICE) logcat --pid="$$pid"; \
	else \
		echo "$(APP_ID) 当前没有运行。请先执行 make run，或使用 make logcat 查看原始日志。"; \
		exit 1; \
	fi

logcat: check-sdk
	@$(ADB) $(ADB_DEVICE) logcat

clear-logs: check-sdk
	@$(ADB) $(ADB_DEVICE) logcat -c
