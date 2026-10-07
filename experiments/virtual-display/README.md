# USB 虚拟屏实验原型

这是独立的 `app_process` 辅助进程和合成内容测试 App，用来验证 Android 的显示、画面、无障碍节点与输入路径。代码未接入 Eta Agent Runtime。

首轮设备是 OnePlus PLZ110 / ColorOS 17 / Android 17 API 37，USB ADB 默认 UID 0，KernelSU 3.3.0。实验结果见 [USB 实测报告](../../docs/VIRTUAL_DISPLAY_USB_RESULTS.md)。

## 构建与启动

本机需要 Android SDK 平台和 build-tools 37、JDK 17、Python 3。`build.sh` 支持通过 `ANDROID_SDK_ROOT` 和 `JAVA_HOME` 覆盖默认路径。调试签名和生成物在被 Git 忽略的 `build/` 中。

```bash
experiments/virtual-display/build.sh
adb -s SERIAL install --no-streaming experiments/virtual-display/build/probe.apk
adb -s SERIAL shell 'mkdir -p /sdcard/dev-dump/io.github.costben.etavdprobe/20261007-usb; touch /sdcard/dev-dump/.nomedia'
adb -s SERIAL push experiments/virtual-display/build/probe.jar /sdcard/dev-dump/io.github.costben.etavdprobe/20261007-usb/probe.jar
adb -s SERIAL forward tcp:18765 localabstract:eta_vd_probe
adb -s SERIAL shell 'CLASSPATH=/sdcard/dev-dump/io.github.costben.etavdprobe/20261007-usb/probe.jar app_process /system/bin io.github.costben.etavdprobe.ProbeMain'
```

最后一条命令保持运行。另一个终端通过 `rpc.py` 查询状态，从返回值读取 `displayId`，再启动 App：

```bash
python3 experiments/virtual-display/rpc.py '{"action":"status"}'
adb -s SERIAL shell am start --display DISPLAY_ID -n io.github.costben.etavdprobe/.ProbeActivity -f 0x18000000
python3 experiments/virtual-display/rpc.py '{"action":"nodes"}'
```

辅助进程维持 Surface 消费与任务期间的 CPU 唤醒锁。UiAutomation 使用 `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`，只返回测试 App 的节点。RPC 仅接受 root/shell 对端，支持状态、节点、目标屏触摸、节点文字赋值和停止。

CPU 唤醒锁设有 30 分钟期限。这个实验进程没有实现生产级监督或断连恢复，实验结束应主动停止。辅助进程初始版本使用普通运行环境的 `KeyguardManager` 时遇到 Android 17 的 `ApplicationSharedMemory not initialized`；当前改为直接读取窗口服务的锁屏状态，测试 App 保留正常应用环境的锁屏状态读回。

## 两种电源路径

`profile.baseline.json` 使用普通设备休眠；`profile.json` 的唯一变化是 `power_strategy` 改为 `display-only`，单独关闭默认物理屏幕。两种策略的语义不同，后者成功并不证明真正锁屏后持续执行。

```bash
python3 experiments/virtual-display/run_case.py --serial SERIAL --profile experiments/virtual-display/profile.baseline.json --seconds 12 --output experiments/virtual-display/artifacts/baseline.json
python3 experiments/virtual-display/run_case.py --serial SERIAL --profile experiments/virtual-display/profile.json --seconds 120 --output experiments/virtual-display/artifacts/modified.json
```

脚本每轮生成唯一中文文本，依次确认主屏 OFF、副屏 ON、新画面增加、计数器实际增加、文本与操作前不同且读回一致，并在结束时恢复默认屏电源与唤醒状态。输入注入返回成功但页面未发生变化时，判定为失败。互斥锁避免实验窗口重叠。

当前测试按钮中心 `(360,139)` 来自本机实际节点边界 `[32,91,688,187]`，适用于本原型固定的 `720×1280 / 320 dpi` 显示屏；其他布局应从节点边界重新计算。

## 在副本验证回滚

```bash
cp experiments/virtual-display/profile.json experiments/virtual-display/artifacts/profile.rollback.json
experiments/virtual-display/ROLLBACK.sh experiments/virtual-display/artifacts/profile.rollback.json
python3 experiments/virtual-display/run_case.py --serial SERIAL --profile experiments/virtual-display/artifacts/profile.rollback.json --seconds 12 --output experiments/virtual-display/artifacts/rollback.json
```

`ROLLBACK.sh` 只把指定副本恢复为普通休眠策略，并拒绝直接覆盖项目中的修改版配置。原始配置、修改配置、统一 diff 和验证记录都保存在本目录。

## 停止与清理

```bash
python3 experiments/virtual-display/rpc.py '{"action":"stop"}'
adb -s SERIAL forward --remove tcp:18765
adb -s SERIAL uninstall io.github.costben.etavdprobe
adb -s SERIAL shell 'rm -rf /sdcard/dev-dump/io.github.costben.etavdprobe/20261007-usb'
adb -s SERIAL shell cmd display power-reset 0
adb -s SERIAL shell input -d 0 keyevent 224
```

停止后核对：实验显示屏消失，辅助进程退出，测试 App 移除，临时无障碍连接结束，默认屏恢复 ON。原始系统 JAR、反编译文件、完整命令记录和视频只保存在本地忽略目录 `artifacts/`；提交中只保留合成状态和必要摘要。
