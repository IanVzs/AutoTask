.DEFAULT_GOAL := help

LOCAL_SDK_DIR := $(shell sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null)
SDK_DIR ?= $(if $(LOCAL_SDK_DIR),$(LOCAL_SDK_DIR),$(HOME)/Android/Sdk)
JAVA_HOME ?= $(shell if [ -d "$(HOME)/jdks/temurin-18.0.2.1" ]; then printf '%s' "$(HOME)/jdks/temurin-18.0.2.1"; elif [ -d "/opt/android-studio/jbr" ]; then printf '%s' "/opt/android-studio/jbr"; fi)

export JAVA_HOME
export ANDROID_HOME := $(SDK_DIR)
export ANDROID_SDK_ROOT := $(SDK_DIR)
export PATH := $(JAVA_HOME)/bin:$(SDK_DIR)/platform-tools:$(SDK_DIR)/emulator:$(SDK_DIR)/cmdline-tools/latest/bin:$(PATH)

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

.PHONY: help env doctor wrapper tasks devices emulators emulator \
	debug build release apk install install-apk reinstall uninstall run stop restart \
	test unit-test connected-test lint clean clean-build logs logcat clear-logs

help:
	@printf '%s\n' \
		'自动任务命令快捷入口' \
		'' \
		'环境检查：' \
		'  make env              查看当前使用的 JDK、Android SDK、包名和设备选择' \
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
		'  make install-apk      不重新构建，通过 adb 把已有 debug APK 安装到当前连接的真机/模拟器' \
		'  make reinstall        不重新构建，通过 adb 覆盖安装已有 debug APK 到当前连接的真机/模拟器' \
		'  make uninstall        通过 adb 从当前连接的真机/模拟器卸载 $(APP_ID)' \
		'  make run              通过 adb 在当前连接的真机/模拟器上打开 $(APP_ID)' \
		'  make restart          通过 adb 在当前连接的真机/模拟器上强停后重新打开 $(APP_ID)' \
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
	@printf 'APP_ID=%s\n' "$(APP_ID)"
	@printf 'DEVICE=%s\n' "$(DEVICE)"

doctor: wrapper
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

tasks: wrapper
	@$(GRADLE) tasks $(GRADLE_FLAGS)

devices:
	@$(ADB) devices

emulators:
	@$(EMULATOR) -list-avds

emulator:
	@test -n "$(AVD)" || { echo "用法：make emulator AVD=<模拟器名称>"; exit 1; }
	@$(EMULATOR) -avd "$(AVD)"

debug build apk: wrapper
	@$(GRADLE) :app:assembleDebug $(GRADLE_FLAGS)
	@printf '\nDebug APK 路径：%s\n' "$(DEBUG_APK)"

release: wrapper
	@$(GRADLE) :app:assembleRelease $(GRADLE_FLAGS)

install: wrapper
	@$(GRADLE) :app:installDebug $(GRADLE_FLAGS)

install-apk reinstall:
	@test -f "$(DEBUG_APK)" || { echo "缺少 $(DEBUG_APK)，请先运行 make debug。"; exit 1; }
	@$(ADB) $(ADB_DEVICE) install -r "$(DEBUG_APK)"

uninstall:
	@$(ADB) $(ADB_DEVICE) uninstall "$(APP_ID)" || true

run:
	@$(ADB) $(ADB_DEVICE) shell am start -n "$(APP_ID)/$(MAIN_ACTIVITY)"

stop:
	@$(ADB) $(ADB_DEVICE) shell am force-stop "$(APP_ID)"

restart: stop run

unit-test test: wrapper
	@$(GRADLE) testDebugUnitTest $(GRADLE_FLAGS)

connected-test: wrapper
	@$(GRADLE) connectedDebugAndroidTest $(GRADLE_FLAGS)

lint: wrapper
	@$(GRADLE) :app:lintDebug $(GRADLE_FLAGS)

clean: wrapper
	@$(GRADLE) clean $(GRADLE_FLAGS)

clean-build:
	@rm -rf .gradle build app/build */build

logs:
	@pid="$$( $(ADB) $(ADB_DEVICE) shell pidof "$(APP_ID)" 2>/dev/null | tr -d '\r' )"; \
	if [ -n "$$pid" ]; then \
		echo "正在显示 $(APP_ID) 的 logcat，pid=$$pid"; \
		$(ADB) $(ADB_DEVICE) logcat --pid="$$pid"; \
	else \
		echo "$(APP_ID) 当前没有运行。请先执行 make run，或使用 make logcat 查看原始日志。"; \
		exit 1; \
	fi

logcat:
	@$(ADB) $(ADB_DEVICE) logcat

clear-logs:
	@$(ADB) $(ADB_DEVICE) logcat -c
