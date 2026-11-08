# 状态空档诊断与修复

2026-11-08，算法版本 2。

## 发现的问题

用户指出首页“另有一小时多记录待确认”“没有安全锁屏”等说明占据关键位置。检查模拟器的本地数据库发现，屏幕事件有实际记录，但多次为 SCREEN_INTERACTIVE 先于 DEVICE_STARTUP 数十至数百毫秒；旧还原逻辑看到 STARTUP 时直接清空独立屏幕状态，导致随后大量区间变为 UNKNOWN。模拟器未设置安全锁屏，也没有 KEYGUARD 事件，不能把它一律当作授权失败。

另外，查询返回的历史启动记录与当前实际启动周期不完全对应；旧前台记录在没有结束边界时可能跨未观察到的重启空档继续计时。仅把提示隐藏不能解决这个问题。

## 修复

1. 已有 SHUTDOWN 边界后、STARTUP 前观察到的独立屏幕/锁屏状态保留，不再被服务初始化标记抹掉；前一个启动周期的活动在 SHUTDOWN 处清理。
2. STARTUP 没有对应 SHUTDOWN 时，上一条事件之后至新启动的空档保持 UNKNOWN，不续计旧应用。
3. 使用 BOOT_COUNT 和当前墙钟减 elapsedRealtime 建立本应用的当前启动观察点，保存为 OBSERVE_BOOT；它与系统事件来源分开，幂等保存，不当作解锁。
4. 每次同步采样 PowerManager.isInteractive 和 KeyguardManager.isKeyguardLocked，保存独立 OBSERVE 状态。采样只从当时向后生效，不反填无依据的历史；重叠系统查询保留这些观察点。
5. 无安全锁屏且没有历史锁屏事件时采用屏幕可交互口径；同步采样仍可识别实际锁屏状态。OBSERVE_UNLOCKED 不计作用户解锁；真正的 UNLOCK 需先有锁定状态依据才计数。

## 实际回放结果

API 36 模拟器当前日重放后，应用推算时长从此前约71分钟降为0。午夜至本次启动后首次亮屏前仍有约71分钟设备空档，保留缺失语义，没有算成手机使用或息屏。此结论来自本机样本，不代表所有设备的所有历史均可恢复。

主页删除长段口径文字。手机使用摘要和应用构成图提供信息按钮，排查原因与刷新操作放在“设置 → 采集状态”。没有推算数据时不展示推算图例。授权撤销等需要用户操作的真实故障仍可提示。

## 依据与限制

Android 开源实现会在 UsageStats 服务初始化时加入当前时间的 STARTUP，SHUTDOWN 采用最近持久化/事件时间，而非精确的物理开关机时间：[UserUsageStatsService.init](https://android.googlesource.com/platform/frameworks/base/+/6a7ce3a5178d/services/usage/java/com/android/server/usage/UserUsageStatsService.java)。结合本机事件顺序，修复独立状态被错误清空的逻辑。

原始事件已被系统清理、设备时钟大幅跳变或厂商长期不提供事件时，不能无依据补成精确轨迹。时钟变化隔离、设备检查点与真机验证仍需继续完善。原始诊断数据库和设备轨迹仅作为本地临时文件存放于忽略提交的 `build/diagnostics/`，验证结束后清理，不上传仓库。
