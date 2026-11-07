# 平台依据与待验证项

核对日期：2026-11-08。接口事实与本项目策略分开维护；以下链接为 Android 官方文档。

| 平台事实 | 设计影响 | 参考 |
| --- | --- | --- |
| queryEvents 的系统事件只保留数天，查询结束为排他边界 | 定期本地补采、记录覆盖范围、使用半开区间 | [UsageStatsManager](https://developer.android.com/reference/android/app/usage/UsageStatsManager) |
| Android R 起，用户启动后尚未解锁时查询可能返回 null | 与授权关闭、有效空结果分别处理 | [UsageStatsManager](https://developer.android.com/reference/android/app/usage/UsageStatsManager) |
| RESUMED/PAUSED 是 Activity 生命周期事件，包内多个 Activity 各自上报 | 以 Activity 为底层状态，再生成应用会话 | [UsageEvents.Event](https://developer.android.com/reference/android/app/usage/UsageEvents.Event) |
| 屏幕、锁屏与设备启停有独立事件；关机记录时间为关机前最后持久化时间 | 独立设备状态模型，关机终点保留来源语义 | [UsageEvents.Event](https://developer.android.com/reference/android/app/usage/UsageEvents.Event) |
| Android 10 起多窗口支持多个 Activity 同时 resumed | 并发区间保留，设备时间取并集 | [多窗口支持](https://developer.android.com/develop/ui/views/layout/support-multi-window-mode) |
| Top-resumed 回调通知 Activity 自身 | 他应用焦点归属采用已验证能力与明确策略 | [Activity](https://developer.android.com/reference/android/app/Activity#onTopResumedActivityChanged(boolean)) |
| WorkManager 周期最短 15 分钟，执行可受节电机制延迟 | 用于补采，提醒另作设计 | [PeriodicWorkRequest](https://developer.android.com/reference/androidx/work/PeriodicWorkRequest) |
| Android 11 起应用安装信息查询受包可见性过滤 | 图标与名称失败时优先历史快照，区分状态 | [包可见性](https://developer.android.com/training/package-visibility) |

## 真机验证清单

- 用户现用手机具体系统版本、厂商设置、使用事件实际保留范围。
- 长会话跨日、息屏与锁屏事件顺序，屏幕常亮场景。
- 应用内 Activity 切换、相同时间戳事件、Activity 实例标识可用性。
- 分屏、悬浮窗、画中画与通知面板的事件表现。
- 默认启动器、应用分身、工作资料的包名及可查询范围。
- 应用被系统结束、重启、启动后首次解锁及后台补采延迟。
- 包可见性下应用名称、图标及卸载判断所需查询配置。
- 手动时间调整和时区切换后的事件时间行为。

以上能力通过 M0 样本验证后记录实际结果。首版覆盖当前用户主空间、主设备；其他资料空间如出现使用需求，再按接口能力接入并标明来源。
