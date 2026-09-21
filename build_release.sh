#!/usr/bin/env bash
# SY --> 构建「官方 Mihon 0.20.4 + WebDAV 云同步上传」的 release 变体。
#
# 上游情况：官方 app/build.gradle.kts 的 release 已经
#   signingConfig = debug.signingConfig
# 且仓库里没有 keystore.properties（那是官方 CI 才有的），所以本地产物
# 用 AGP 的默认 debug keystore 签名。安装约束见 README 说明。
export PATH="/c/Users/saber/.workbuddy/binaries/PortableGit/versions/1.2.0/mingw64/bin:/c/Users/saber/.workbuddy/binaries/PortableGit/versions/1.2.0/usr/bin:$PATH"
cd /g/code/mihon-webdav || exit 1

export JAVA_HOME="D:/build_tools/jdk21/jdk-21.0.12.1+1"
export ANDROID_HOME="C:/Users/saber/AppData/Local/Android/Sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export GRADLE_USER_HOME="G:/code/gradle-home"
export PATH="$JAVA_HOME/bin:$PATH"

LOG=/g/code/mihon-webdav-build.log
# 说明：android.injected.build.abi 只打 arm64（Mihon 官方支持 4 个 ABI，
# 只留 arm64 可显著缩小产物）；一旦用 android.injected.* 前缀，AGP 会按
# 「IDE 注入构建」处理并把 android:testOnly 打开，所以必须显式关掉，
# 否则真机会以 INSTALL_FAILED_TEST_ONLY 拒绝安装。
./gradlew :app:assembleRelease --no-daemon \
  -Pandroid.injected.build.abi=arm64-v8a \
  -Pandroid.injected.testOnly=false \
  -Dorg.gradle.jvmargs="-Xmx6g -XX:MaxMetaspaceSize=1g" > "$LOG" 2>&1
RC=$?
echo "########## EXIT=$RC ##########"
if [ $RC -ne 0 ]; then
  sed -n '/What went wrong/,/^\* Try/p' "$LOG" | head -60
  exit $RC
fi
echo "=== outputs/apk/release ==="
ls -la app/build/outputs/apk/release/ 2>/dev/null
echo "=== intermediates/apk/release ==="
ls -la app/build/intermediates/apk/release/ 2>/dev/null
tail -5 "$LOG"
