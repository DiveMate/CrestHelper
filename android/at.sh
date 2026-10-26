#!/usr/bin/env bash
# =============================================================================
#  at.sh —— 一条命令跑完全部自动化测试，替代"人工装 APK 点一点"
#
#  用法：
#    bash at.sh              # 全量：L1 + L2 + 构建 APK + L3（有手机才跑 L3）
#    bash at.sh unit         # 只跑 L1+L2 单元/界面测试（秒级，不需要手机）
#    bash at.sh device       # 只跑 L3 真机测试（自动装 APK、抓崩溃）
#    bash at.sh crash        # 只抓当前手机的崩溃堆栈
#    bash at.sh install      # 只装 APK 到手机
#    bash at.sh logs         # 实时跟踪 App 日志
#
#  三层测试：
#    L1  JVM 单元测试    —— 减压内核/合并过滤/JSON解析（无需手机，秒级）
#    L2  Robolectric    —— Compose 界面渲染与点击（无需手机，抓白屏/崩溃）
#    L3  仪器测试        —— 真机跑 JNI + libdivecomputer 解析（抓"装上打不开"）
# =============================================================================
set -uo pipefail

# --- 输出函数先定义（后续任何位置都会用到）---------------------------------
RED=$'\033[31m'; GRN=$'\033[32m'; YEL=$'\033[33m'; CYA=$'\033[36m'; RST=$'\033[0m'
say()  { printf "%s== %s ==%s\n" "$CYA" "$*" "$RST"; }
ok()   { printf "%s[OK]%s %s\n"   "$GRN" "$RST" "$*"; }
bad()  { printf "%s[FAIL]%s %s\n" "$RED" "$RST" "$*"; }
warn() { printf "%s[WARN]%s %s\n" "$YEL" "$RST" "$*"; }

# --- JDK 17 强制就位 -------------------------------------------------------
# AGP 8.x 必须 JDK 17。若系统 JAVA_HOME 指向别的版本（如 Java 11），必须覆盖，
# 否则报 "Android Gradle plugin requires Java 17 to run"。
JDK17_HOME="$JAVA_HOME"
if [ ! -f "$JDK17_HOME/bin/java.exe" ]; then
    bad "找不到 JDK 17: $JDK17_HOME"
    bad "请修改本脚本里的 JDK17_HOME 指向你的 JDK17 目录"
    exit 3
fi
export JAVA_HOME="$JDK17_HOME"

export ANDROID_HOME="${ANDROID_HOME:-$ANDROID_HOME}"
# 关键：Git Bash 不认 Windows 风格的冒号分隔 PATH（ADK 是 Windows 程序，
# 用 D:/... 会被当成单个非法目录名）。adb.exe 是 Windows 程序，用 MSYS 斜杠路径才能跑。
ADB_BIN="/d/code/android-sdk/platform-tools"
[ -d "$ADB_BIN" ] || ADB_BIN="$(cygpath -m "$ANDROID_HOME/platform-tools" 2>/dev/null | sed 's|^\([A-Za-z]\):|/\L\1|')"
export PATH="$JAVA_HOME/bin:$ADB_BIN:$PATH"
ADB="$ADB_BIN/adb"
if [ ! -x "$ADB" ]; then
    bad "找不到 adb：$ADB"
    bad "请确认 Android SDK 已安装（platform-tools），或修改本脚本里的 ADB_BIN"
    exit 3
fi

PROJ="/c/Users/GX/WorkBuddy/code/dive/android"
GRADLE="/d/code/tools/gradle-8.7/bin/gradle"
PKG="com.divelog.app"
REPORT="$PROJ/app/build/reports/tests"
LOGDIR="/d/code/_at_logs"
mkdir -p "$LOGDIR"
cd "$PROJ" || exit 1

# --- 设备检测 ---------------------------------------------------------------
# 注意：Windows 上 adb 输出行尾带 \r（CRLF），且设备名与状态之间是 TAB。
# 所以不能用 grep "$device$" 这种行尾锚定，必须先去 \r 再匹配。
device_list() {
    "$ADB" devices 2>/dev/null | tr -d '\r' \
        | awk '$2=="device" {print $1}'
}
device_count() { device_list | wc -l | tr -d ' '; }
first_device() { device_list | head -1; }

# --- 汇总测试结果 -----------------------------------------------------------
# Gradle 的 androidTest 输出路径各版本不同，动态查找最稳妥。
find_androidtest_xml() {
    find "$PROJ/app/build/outputs/androidTest-results" -name "*.xml" 2>/dev/null | head -1 >/dev/null
    local dir
    dir=$(find "$PROJ/app/build/outputs/androidTest-results" -name "*.xml" -printf '%h\n' 2>/dev/null | head -1)
    [ -n "$dir" ] && echo "$dir"
}

summarize_jvm() {
    local dir="$1" label="$2"
    if [ ! -d "$dir" ]; then
        warn "$label 未找到报告目录（若测试未真正执行会走到这里）"
        return 1
    fi
    local n total=0 failed=0
    # 解析 XML：tests/failures/errors
    for f in "$dir"/*.xml; do
        [ -f "$f" ] || continue
        n=$(grep -o 'tests="[0-9]*"' "$f" | head -1 | grep -o '[0-9]*')
        local fl
        fl=$(grep -o 'failures="[0-9]*"' "$f" | head -1 | grep -o '[0-9]*')
        local er
        er=$(grep -o 'errors="[0-9]*"' "$f" | head -1 | grep -o '[0-9]*')
        [ -z "$n" ] && continue
        total=$((total + n))
        failed=$((failed + ${fl:-0} + ${er:-0}))
    done
    echo "$total $failed"
}

print_failures() {
    local dir="$1"
    for f in "$dir"/*.xml; do
        [ -f "$f" ] || continue
        if grep -qE '<(failure|error)' "$f"; then
            printf "%s--- %s ---%s\n" "$YEL" "$(basename "$f")" "$RST"
            # 抽出失败用例名与消息
            grep -oE '<testcase name="[^"]*" classname="[^"]*"' "$f" | head -40 | sed 's/^/    /'
            grep -A2 -E '<(failure|error) message=' "$f" | sed 's/^/    /' | head -30
        fi
    done
}

# =============================================================================
case "${1:-all}" in

# -----------------------------------------------------------------------------
unit)
    say "L1/L2 单元 + 界面测试（不需要手机）"
    if "$GRADLE" testDebugUnitTest --console=plain > "$LOGDIR/unit.log" 2>&1; then
        ok "单元测试通过"
    else
        bad "单元测试失败"
        grep -E "^e: |error:|FAILED" "$LOGDIR/unit.log" | head -20
    fi
    read -r total failed <<< "$(summarize_jvm "$PROJ/app/build/test-results/testDebugUnitTest" L1)"
    say "结果: 共 $total 个测试，失败 $failed"
    [ "$failed" -gt 0 ] && print_failures "$PROJ/app/build/test-results/testDebugUnitTest"
    ok "HTML 报告: $PROJ/app/build/reports/tests/testDebugUnitTest/index.html"
    [ "$failed" -gt 0 ] && exit 1
    ;;

# -----------------------------------------------------------------------------
build)
    say "构建 APK（含 NDK 编译 libdc.so）"
    if "$GRADLE" assembleDebug --console=plain > "$LOGDIR/build.log" 2>&1; then
        apk_out="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
        ok "APK: $apk_out ($(du -h "$apk_out" | cut -f1))"
        # --- 16 KB 页对齐检查（Android 15+ 硬要求）---
        # NDK r27 默认按 4KB(0x1000) 链接；漏加 max-page-size=16384 时，
        # 16KB 页设备会装不上/起不来，4KB 设备则弹「应用兼容性」警告。
        so="$PROJ/app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib/arm64-v8a/libdcbridge.so"
        readelf=$(ls "$ANDROID_HOME"/ndk/*/toolchains/llvm/prebuilt/*/bin/llvm-readelf.exe 2>/dev/null | head -1)
        if [ -f "$so" ] && [ -n "$readelf" ]; then
            align=$("$readelf" -l "$so" 2>/dev/null | awk '/LOAD/{print $NF; exit}')
            if [ "$align" = "0x4000" ]; then
                ok "libdcbridge.so 16KB 对齐 ($align)"
            else
                bad "libdcbridge.so 未按 16KB 对齐（当前 $align），16KB 页设备会失败"
            fi
        fi
    else
        bad "构建失败"
        grep -E "^e: |error:|FAILED|CMake Error" "$LOGDIR/build.log" | head -20
        exit 1
    fi
    ;;

# -----------------------------------------------------------------------------
install)
    say "安装 APK 到手机"
    n=$(device_count)
    if [ "$n" -eq 0 ]; then
        bad "没有检测到设备。请：① 用 USB 连手机 ② 开启『USB 调试』③ 弹窗点『允许』"
        echo "    检查: adb devices -l"
        exit 2
    fi
    apk="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
    [ -f "$apk" ] || { bad "APK 不存在，请先跑: bash at.sh build"; exit 1; }
    "$ADB" install -r -t "$apk" 2>&1 | tail -3
    ok "已安装到 $(first_device)"
    ;;

# -----------------------------------------------------------------------------
device)
    say "L3 真机仪器测试（自动装 APK + 抓崩溃）"
    n=$(device_count)
    if [ "$n" -eq 0 ]; then
        bad "没有检测到设备 —— L3 需要真机/模拟器"
        echo "    ① 手机开『USB 调试』并连上  ② 或: emulator -avd <name> -no-window"
        exit 2
    fi
# 关键：仪器测试要求屏幕点亮且无锁屏。
# 息屏（Doze）时 Activity 只到 STARTED，Compose 测试会报
#   "No compose hierarchies found" / "Activity 应处于 RESUMED"。
# 这里先唤醒 + 解锁 + 插电保持常亮。
"$ADB" shell svc power stayon usb >/dev/null 2>&1
"$ADB" shell input keyevent 224 >/dev/null 2>&1   # KEYCODE_WAKEUP
"$ADB" shell input keyevent 82  >/dev/null 2>&1   # KEYCODE_MENU：唤出解锁
"$ADB" shell wm dismiss-keyguard >/dev/null 2>&1
sleep 1

# 预先授予 BLE 权限：否则用例里点「请求权限」会弹出系统对话框，
# 覆盖住被测 Activity，导致后续 Compose 断言永久等待（挂死整轮测试）。
for p in BLUETOOTH_SCAN BLUETOOTH_CONNECT ACCESS_FINE_LOCATION; do
    "$ADB" shell pm grant "$PKG" "android.permission.$p" >/dev/null 2>&1
done

# 先清 logcat，保证只抓本次的崩溃
"$ADB" logcat -c 2>/dev/null

# --- 只用 Gradle 做「构建 + 打包」，测试本身交给 am instrument 直跑 -----------
# 为什么不用 connectedDebugAndroidTest：
#   它在设备侧要做一轮自己的编排（装包、起 instrumentation、轮询报告），
#   实测容易出现「跑到第 N 个用例后整轮挂死、既不出结果也不超时」——
#   同一批用例用 am instrument 直跑 20 秒就全绿，问题在编排层而不在测试代码。
#   am instrument 还能拿到逐条用例的实时状态（test= / STATUS_CODE），
#   挂住时能立刻看出卡在哪个用例上。
say "构建 App + 测试 APK"
if ! "$GRADLE" assembleDebug assembleDebugAndroidTest --console=plain > "$LOGDIR/device_build.log" 2>&1; then
    bad "构建失败（见 $LOGDIR/device_build.log）"
    grep -E "^e: |error:|FAILED|CMake Error" "$LOGDIR/device_build.log" | head -20
    exit 1
fi
app_apk="$PROJ/app/build/outputs/apk/debug/app-debug.apk"
test_apk="$PROJ/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
"$ADB" install -r -t "$app_apk" 2>&1 | tail -1
"$ADB" install -r -t "$test_apk" 2>&1 | tail -1

RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
PER_TIMEOUT=${DEVICE_TIMEOUT:-300}
CLASSES="com.divelog.app.EnvScreenTest com.divelog.app.NativeParseTest com.divelog.app.SmokeLaunchTest"

# 为什么按类拆开跑、并且挂死自动重跑一次：
#   在这台三星（One UI / Android 16）上，把 20 个 Compose 用例塞进同一次会话，
#   偶发「跑到第 N 个用例就永久等待」——既不返回也不超时（Activity 被系统顶到
#   后台后 Compose 的 waitForIdle 不响应中断，JUnit 的 @Test(timeout) 也杀不掉）。
#   实测同一批用例重跑必过，所以：按类拆成 3 个小批次（一个类挂住不牵连其它类）
#   + 单类超时 + 自动重跑一次，把偶发挂死变成可恢复的抖动。
say "运行仪器测试（3 个类，单类上限 ${PER_TIMEOUT}s，挂死自动重跑一次）"
: > "$LOGDIR/device.log.raw"
total=0
passed=0
failed=0
hung=0
hard_hung=""

prepare_device() {
    "$ADB" shell am force-stop "$PKG.test" >/dev/null 2>&1
    "$ADB" shell am force-stop "$PKG" >/dev/null 2>&1
    "$ADB" shell svc power stayon usb >/dev/null 2>&1
    "$ADB" shell input keyevent 224 >/dev/null 2>&1
    "$ADB" shell wm dismiss-keyguard >/dev/null 2>&1
    sleep 1
}

for cls in $CLASSES; do
    short=${cls##*.}
    attempt=1
    done_ok=0
    while [ "$attempt" -le 2 ] && [ "$done_ok" -eq 0 ]; do
        prepare_device
        say "  $short（第 $attempt 次）"
        timeout "$PER_TIMEOUT" "$ADB" shell am instrument -w -r -e class "$cls" "$RUNNER" \
            > "$LOGDIR/device_$short.raw" 2>&1
        rc=$?
        # adb 输出是 CRLF，先去掉 \r，否则 grep 的 $ 锚定全部失效
        tr -d '\r' < "$LOGDIR/device_$short.raw" > "$LOGDIR/device_$short.log"
        cat "$LOGDIR/device_$short.log" >> "$LOGDIR/device.log.raw"

        if [ "$rc" -eq 124 ]; then
            hung=$((hung + 1))
            warn "$short 挂死（超时 ${PER_TIMEOUT}s），清理进程后重跑"
            prepare_device
            attempt=$((attempt + 1))
            continue
        fi

        n=$(grep -o "numtests=[0-9]*" "$LOGDIR/device_$short.log" | tail -1 | cut -d= -f2)
        total=$((total + ${n:-0}))
        passed=$((passed + $(grep -c "INSTRUMENTATION_STATUS_CODE: 0$" "$LOGDIR/device_$short.log")))
        failed=$((failed + $(grep -c "INSTRUMENTATION_STATUS_CODE: -2$" "$LOGDIR/device_$short.log")))
        done_ok=1
    done
    if [ "$done_ok" -eq 0 ]; then
        hard_hung="$hard_hung $short"
    fi
done

tr -d '\r' < "$LOGDIR/device.log.raw" > "$LOGDIR/device.log"
rc=0
[ -n "$hard_hung" ] && rc=1
[ "$failed" -gt 0 ] && rc=1


if [ "$failed" -gt 0 ]; then
    bad "真机测试失败：$failed 个用例"
    say "结果: 共 $total 个测试，通过 $((total - failed))，失败 $failed"
    # 失败用例名 + 堆栈（每条失败前都有 test= 与 stack=）
    awk '/^INSTRUMENTATION_STATUS: test=/{t=substr($0,index($0,"=")+1)}
         /^INSTRUMENTATION_STATUS: stack=/{s=substr($0,index($0,"=")+1)}
         /^INSTRUMENTATION_STATUS_CODE: -2$/{printf "  FAIL %s\n    %s\n", t, s}' "$LOGDIR/device.log"
    echo ""
    bad "崩溃堆栈（logcat）："
    "$ADB" logcat -d -v brief 2>/dev/null | grep -B3 -A25 "FATAL EXCEPTION" | head -60
elif [ -n "$hard_hung" ]; then
    bad "以下测试类连续两次挂死：$hard_hung"
    for short in $hard_hung; do
        last=$(grep "^INSTRUMENTATION_STATUS: test=" "$LOGDIR/device_$short.log" 2>/dev/null | tail -1 | cut -d= -f2)
        [ -n "$last" ] && bad "  $short 最后开始未返回的用例: $last"
    done
    say "单独重跑: bash at.sh device  或  adb shell am instrument -w -r -e class <类名> $RUNNER"
else
    ok "真机测试通过：$total 项全部通过"
    say "结果: 共 $total 个测试，失败 0（通过 $passed）"
    [ "$hung" -gt 0 ] && warn "本轮有 $hung 次偶发挂死，已自动重跑通过"
fi
echo "完整日志: $LOGDIR/device.log"
if [ "$rc" -ne 0 ]; then exit 1; fi
;;


# -----------------------------------------------------------------------------
crash)
    say "抓取设备上的崩溃堆栈"
    n=$(device_count)
    if [ "$n" -eq 0 ]; then bad "没有设备"; exit 2; fi
    out=$("$ADB" logcat -d -v time 2>/dev/null | grep -B5 -A40 "FATAL EXCEPTION")
    if [ -z "$out" ]; then
        ok "logcat 里没有崩溃记录"
    else
        bad "发现崩溃："
        echo "$out" | head -80
    fi
    ;;

# -----------------------------------------------------------------------------
logs)
    say "实时日志（Ctrl+C 退出）"
    pid=$("$ADB" shell pidof "$PKG" 2>/dev/null | tr -d '\r')
    if [ -n "$pid" ]; then
        echo "App pid=$pid"
        "$ADB" logcat --pid="$pid" -v time
    else
        warn "App 没在运行，改看全量 ERROR 以上"
        "$ADB" logcat *:E
    fi
    ;;

# -----------------------------------------------------------------------------
all)
    say "=== 全量自动化测试 ==="
    echo ""
    bash "$0" unit
    echo ""
    bash "$0" build
    echo ""
    if [ "$(device_count)" -gt 0 ]; then
        bash "$0" device
    else
        warn "未检测到设备 —— 跳过 L3（真机测试）"
        warn "这意味着『装上打不开』这类问题现在查不出来。"
        warn "连上手机并开 USB 调试后跑:  bash at.sh device"
    fi
    echo ""
    say "完成"
    ;;

*)
    echo "用法: bash at.sh [all|unit|build|install|device|crash|logs]"
    exit 1
    ;;
esac