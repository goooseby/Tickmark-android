# 技术架构

## 1. 工程决策

采用 Kotlin、Compose / Material 3、ViewModel、Coroutines / Flow、Room、DataStore、WorkManager。首版最低版本计划 Android 10 / API 29，优先验证用户现用手机。compileSdk、targetSdk 与依赖版本在构建初始化时根据本机 SDK 和当时稳定版本锁定，统一放入版本目录。

物理结构采用两个模块：Android `app` 与纯 Kotlin/JVM `engine`。这个边界隔离项目最重要的计算逻辑，支持快速离线回放与测试；页面按包组织，后续根据规模再拆模块。

`app` 依赖 `engine`；`engine` 只依赖 Kotlin/JVM 与标准时间模型，独立于 Android Context、Room 和 Compose。采用构造函数注入与应用级组合入口，首版手动装配。

## 2. 目录职责

| 目录 | 责任 |
| --- | --- |
| `app/src/main/kotlin/com/goooseby/tickmark/app` | 应用入口、依赖装配、导航 |
| `app/src/main/kotlin/com/goooseby/tickmark/platform/usage` | 系统事件适配、可用能力、权限检查 |
| `app/src/main/kotlin/com/goooseby/tickmark/platform/packages` | 应用名称、图标、可见性处理 |
| `app/src/main/kotlin/com/goooseby/tickmark/data/local` | Room 实体、DAO、迁移、事务 |
| `app/src/main/kotlin/com/goooseby/tickmark/data/repository` | 同步、缓存、查询与数据流 |
| `app/src/main/kotlin/com/goooseby/tickmark/data/transfer` | 导出、备份、恢复 |
| `app/src/main/kotlin/com/goooseby/tickmark/feature/today` | 日期时间线、选区与标注入口 |
| `app/src/main/kotlin/com/goooseby/tickmark/feature/review` | 摘要、排行、趋势 |
| `app/src/main/kotlin/com/goooseby/tickmark/feature/appdetail` | 单应用详情 |
| `app/src/main/kotlin/com/goooseby/tickmark/feature/settings` | 配置与权限状态 |
| `app/src/main/kotlin/com/goooseby/tickmark/core/ui` | 主题、时间轴、图表、状态组件 |
| `app/src/main/kotlin/com/goooseby/tickmark/worker` | 补采与保留策略执行 |
| `engine/src/main/kotlin/com/goooseby/tickmark/engine/model` | 标准化事件、区间、质量与来源 |
| `engine/src/main/kotlin/com/goooseby/tickmark/engine/session` | 状态机、检查点、会话重建 |
| `engine/src/main/kotlin/com/goooseby/tickmark/engine/analytics` | 并集、切分、投影与用途统计 |
| `engine/src/test` | 合成序列回放和性质测试 |
| `app/src/androidTest` | Room 迁移、系统接入与关键操作验证 |

## 3. 数据流

系统适配器读取 UsageEvents，转换为标准事件；同步协调器去重、匿名化并入库；engine 重建会话与设备区间；Room 保存派生数据；Repository 发布 Flow；ViewModel 组合日期、选区和设置；Compose 渲染不可变 UI 状态。

页面从数据库读，系统查询仅由同步协调器执行。读取缓存后异步更新；大区间按日投影和分页读取，图标以包名与更新版本缓存。初始回填展示进度，切换日期可取消过期 UI 查询。

## 4. 数据模型

| 模型 | 关键字段与约束 |
| --- | --- |
| RawUsageEvent | source、timestamp、type、packageName?、className?、instanceId?、sourceOrder、multiplicity、bootEpoch |
| UsageSession | id、packageName、start、end?、quality、endReason、algorithmVersion；完整会话跨日保留 |
| DeviceInterval | start、end?、screenState、keyguardState、quality、bootEpoch；状态组合允许 unknown |
| CoverageInterval | start、end、queryStatus、evidenceStatus、reason、source |
| DailyDeviceSummary | localDate、zoneId、metricVersion、observedDuration、inferredDuration、unlockCount、coverage、sourceRevision |
| DailyAppSummary | localDate、zoneId、packageName、duration、startCount、quality、sourceRevision |
| AppMetadata | packageName、lastKnownName、appCategory、defaultPurpose、displayHidden、metadataStatus |
| CaptureRule | packageName、mode、effectiveFrom；隐私排除名单本身为本地配置 |
| PurposeAnnotation | id、start、end、purposeId、createdAt、updatedAt；用途区间互斥 |
| ReviewNote | id、start、end、text、createdAt、updatedAt；备注允许重叠 |
| Purpose | id、name、color、sortOrder |
| SyncCheckpoint | source、lastCommittedQueryEnd、replayAnchor、stateSnapshot、algorithmVersion |

高频区间查询为开始/结束时间及包名建立适当索引。日摘要使用复合键，避免不同时区或版本互相覆盖。个人备注与手动用途作为原始用户数据，聚合缓存可重建。

分类修改、规则修改、时区修改、算法升级分别产生数据修订版本；相关缓存失效后重算。超出原始事件保留期时，从既存会话能完成的投影继续计算，其余保留历史版本并标明范围。

## 5. 系统接入

- 通过 AppOps 与实际查询结果共同判断状态，区分授权关闭、设备启动后尚未首次解锁、查询失败与有效空结果。
- 授权撤销后历史照常可读，采集暂停；恢复时补采可用区间。
- WorkManager 唯一周期任务负责可延迟补采。厂商后台限制用采集健康信息提示；电池优化豁免留为针对实际问题的可选操作。
- 首版无常驻前台服务、AccessibilityService、VPN、root 接入或高频轮询。未来实时提醒另作架构决策。
- 应用信息读取遵守包可见性，优先声明必要的定向 queries；NameNotFound 等结果归为信息不可用，只有可靠安装信息才展示已卸载。
- 通知仅在用户开启提醒时申请；备份通过 Storage Access Framework 操作用户选择的文件。
- 无 INTERNET 权限；关闭自动备份相关通道，导出与分享显式触发。

## 6. 异常与性能

同步 I/O 在后台调度，算法运行在计算调度器；同设备同步串行，事务按有限区间提交。失败保留旧缓存与水位，显示最后成功同步时间。

时间修改导致乱序、时间回退时，将受影响记录隔离为待确认区间；原始事件仍保留，避免负时长。启动周期与采集时的墙钟/单调时钟锚点用于本应用可观察范围内的诊断。

性能目标待首轮真机建立基线：本地单日首屏可快速交互、长时间线分页流畅、应用关闭时无持续高频任务。后续记录真实冷启动、90 天数据库大小与补采耗时，再给出预算。
