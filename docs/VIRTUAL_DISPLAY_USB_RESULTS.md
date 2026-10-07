# USB 首轮虚拟屏实测：物理息屏通过，普通休眠停止副屏

日期：2026-10-07。分支：`explore/usb-virtual-display`。源码基线：探索文档提交 `aa327f8`，Eta 应用源码仍基于 `9de3a3f70aba497bd8fd677cb5b876f45115b428`。

**这台设备已经跑通 Root 创建虚拟屏、按屏读取节点、发送触摸与中文节点赋值。单独关闭物理屏幕后，副屏连续两分钟保持运行；普通设备休眠会同时关闭副屏。真正凭证锁屏后的持续执行仍待验证。**

当前原型是独立辅助进程和合成内容测试 App，见 [原型说明](../experiments/virtual-display/README.md)。[最初探索文档](VIRTUAL_DISPLAY_EXPLORATION.md)已补充本轮结论。

## 1. 设备与实验条件

| 项目 | 实际读回 |
| --- | --- |
| USB 设备 | OnePlus `PLZ110`，序列号 `3B163B004PM00000` |
| Android / API | Android 17 / API 37 |
| ROM | `PLZ110_17.0.0.106(CN01)`，Oplus ROM `V17.0.0` |
| 安全补丁 | 2026-09-01 |
| Root | KernelSU `3.3.0`；ADB 默认 UID 0，SELinux 域 `ksu` |
| shell 身份 | `su 2000 -c id` 返回实际 UID 2000；完整显示实验使用 UID 0 |
| LSPosed | 管理器已安装，`lspd` 与伴生进程存在；本轮使用独立辅助进程验证系统能力 |
| Eta | 已安装 `3.2.0`，versionCode `2026100501` |
| 初始无障碍设置 | `enabled_accessibility_services=null`，`accessibility_enabled=0` |
| 初始电源设置 | 屏幕超时 `120000 ms`，插电常亮 `0`，USB 供电 |
| 锁屏条件 | `isDeviceSecure=false`，`locksettings get-disabled=true`，系统 Keyguard `showing=false` |

这台设备当前既没有凭证锁，也禁用了普通锁屏界面。本轮确认了设备休眠与物理息屏的差异，PIN/密码锁定条件留待补测。现有锁屏、常亮和超时设置在实验后均读回为初始值。

## 2. 已执行实验

测试 App 只显示合成计数器、每秒变化的时钟、测试输入框与本实例的显示屏/锁屏状态。测试窗口是 `720×1280 / 320 dpi`，完整对照使用逻辑屏 `9`。

辅助进程持续消费 ImageReader 画面，持有实验期间的部分 CPU 唤醒锁，通过 UiAutomation 按屏取窗口，目标屏注入 MotionEvent。中文验证使用可编辑节点的 `ACTION_SET_TEXT`，验证的是节点赋值，输入法和键盘提交另需测试。

### 2.1 scrcpy 与 Android 17 兼容问题

本机 scrcpy `3.3.1` 先成功创建显示屏 `5`，随后出现新增系统回调缺失：

```text
java.lang.AbstractMethodError: abstract method
"void android.view.IDisplayWindowListener.onDisplayAnimationsDisabledChanged(int, boolean)"
```

进程最终以状态 `2` 结束。后续转为项目内辅助进程，避免把这个客户端兼容问题与系统虚拟屏能力混在一起。

辅助进程初始版本调用普通 `KeyguardManager` 时遇到 `ApplicationSharedMemory not initialized`。修正为直接查询窗口服务后，创建、状态查询和无障碍操作均跑通；测试 App 仍从正常 App 环境读回锁屏状态。

### 2.2 原始策略、修改策略与回滚复现

三轮正式对照顺序执行，使用相同辅助进程、显示属性、App 与验证脚本，只改变配置中的 `power_strategy`。

| 对照 | 电源动作 | 观察窗口 | 结果 | 主屏 / 副屏 | 帧计数 | 点击与中文读回 |
| --- | --- | --- | --- | --- | --- | --- |
| BASELINE | `input -d 0 keyevent 223`，普通设备休眠 | 12 秒 | FAIL，退出 1 | OFF / OFF | `31 → 31` | 页面消失，操作未确认 |
| MODIFIED | `cmd display power-off 0`，物理屏单独关闭 | 120 秒 | PASS，退出 0 | OFF / ON | `48 → 202` | 计数 `0 → 1`，中文实际变化且读回一致 |
| ROLLBACK | 配置副本恢复普通休眠，再执行相同基线动作 | 12 秒 | FAIL，退出 1 | OFF / OFF | `205 → 205` | 复现基线停止行为 |

表中的观察窗口指固定采样区间，额外的启动、动作等待、读回和清理时间单独发生。一次测试窗口重叠的尝试已作废，正式结果取加互斥锁后顺序执行的记录。

修改版末次画面的年龄为 `529 ms`；目标 App 的时钟 `17 → 140`，Activity 持续 resumed，节点可见。每轮使用唯一中文文本，并检查它与操作前的值不同。结果证明主屏关闭期间仍有新画面与实际动作反馈，而不是只收到输入发送成功。

回滚测试在另一份配置副本上进行，回滚后 SHA-256 与原始配置一致；项目内修改版仍保留 `display-only`。

完整命令、输入、字面输出和退出状态见 [VERIFICATION.txt](../experiments/virtual-display/VERIFICATION.txt)。合成状态摘要见 [usb-results.json](../experiments/virtual-display/results/usb-results.json)。包含完整系统输出的本地记录位于原型的忽略目录 `artifacts/`。

## 3. 显示组分配问题的证据

最初的普通创建标志组合为 `97737`；补上 PRESENTATION 与 DEVICE_DISPLAY_GROUP 后为 `130507`。两种组合均带 OWN_DISPLAY_GROUP / ALWAYS_UNLOCKED，实际虚拟屏却都分到 `displayGroupId=0`。

增加上述标志没有改变分组；DEVICE_DISPLAY_GROUP 也没有关联的 VirtualDevice，后续需要逐项收敛有效属性。部分 CPU 唤醒锁一直持有，普通休眠后仍出现副屏 OFF，这直接区分了 CPU 保活与显示屏保活。

本机系统 `separate_timeouts` 功能标志已启用。读取 `/system/framework/services.jar` 后，显示组分配代码显示以下路径：

1. `LogicalDisplayMapper` 调用 `DisplayGroupAllocator` 决定所需组类型。
2. 普通虚拟屏的内容模式落到默认组类型空字符串。
3. 已存在组 0 的组名也是空字符串，计算得到组 0，并覆盖普通独立显示组的预期分配。

`LogicalDisplayMapper` 的完整方法存在反编译不完整情况；上述结论结合了明确的调用片段、可读取的 allocator 方法、功能标志和实际分组读回。下一轮仍需通过运行时方法观测确认拟议补丁的参数与调用顺序。

本机 `/system/framework/oplus-services.jar` 还包含 `InsensibleVirtualDisplayManager`：厂商无感虚拟屏被 allocator 赋予单独的内容模式和组类型。其保留屏名 `InsensibleScreen` 有包名检查，并在创建时管理已有厂商会话，因此它不是可直接复用为 Eta 会话的普通屏名。

本轮原始系统文件保持原样；分析使用本地副本。对应 SHA-256：

```text
services.jar        ab0b072c056d17bbf65643b8b7e9b7f650f341d2691bc42a0796687bd0ca48eb
oplus-services.jar  3146424914814a7bcfd73b39db56c6f4bb474a814eaadae934cf7b068d268cbe
```

两个 JAR 和反编译文件保留在本地忽略目录，报告仅记录与本次结论有关的片段和哈希。

## 4. 对 Root / LSPosed 方案的修正

**Root 侧的创建、画面和基本操作路径已在这台 Android 17 设备上成立。** 先前 Android 13 包名/UID 校验的担忧应按系统版本处理：这台设备 UID 0 创建已实测成功；其他 Android 版本仍要验证实际创建身份。

**LSPosed 下一轮最有价值的接入点是显示组分配。** 建议仅识别由 Eta 会话创建并登记的虚拟屏，为这些显示屏提供独立组名，保留普通屏幕与厂商屏幕的分组行为，再核对对应 PowerGroup 是否跟随默认组休眠。

拟议运行时观测/适配位置是 `DisplayGroupAllocator.decideRequiredGroupTypeLocked` 及调用它的 `LogicalDisplayMapper` 路径。这是待验证设计，尚未作为补丁装载到 system_server。后续实现应同时验证组 ID、组标志、默认组邻接关系与退出清理，而不是仅凭返回一个新组名判定成功。

Eta 的工具改造方向保持原探索文档中的显示屏会话合同：启动、截图、节点、坐标、文字、返回与观察代次需要统一路由。当前通过的 UiAutomation 原型只是系统能力验证，Eta 自己的无障碍服务还需单独验证。

## 5. 下一轮门槛

- 在 Eta 自建屏上验证独立显示组和 PowerGroup，复测普通电源键/休眠动作。
- 在设备已有 PIN/密码锁定条件下，确认默认屏 Keyguard 保持锁定，副屏仍执行；本轮设备条件不足以给出该结论。
- 验证 Eta 无障碍服务、主副屏并用、输入法中文输入、提交和返回键。
- 系统闭环通过后接入 Agent Runtime，再做 60 分钟及 8 小时测试。
- 拔线、电池、Doze、模型网络请求和另一种 ROM 均作为独立验收项。

物理息屏策略可保留为诊断对照，但正式功能的描述应以真实锁屏和独立分组的实测结果为准。

## 6. 实验结束状态

停止 RPC 返回 `{"ok":true,"action":"stop"}`，辅助进程返回 `ETA_VD_RELEASED`，退出状态 `0`。

已读回：只剩显示屏 `0`，主屏 ON，辅助进程退出，实验 CPU 唤醒锁释放，测试 App 与设备临时文件移除，USB 转发移除。无障碍、屏幕超时、插电常亮和锁屏禁用状态均与初始值一致，见 [cleanup.json](../experiments/virtual-display/results/cleanup.json)。
