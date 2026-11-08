# Tickmark

个人 Android 手机使用轨迹与每日复盘工具。名称暂用 Tickmark。

体验安装：[下载0.3.0 APK](https://github.com/goooseby/Tickmark-android/releases/download/v0.3.0/tickmark-v0.3.0.apk) · [版本说明](https://github.com/goooseby/Tickmark-android/releases/tag/v0.3.0)。首次使用请按应用提示开启使用记录访问权限。

核心问题：今天什么时候用了哪些应用、每次用了多久、哪些时段值得回看？

## 当前阶段

2026-11-08：进入原生 Android 实现与模拟器验证阶段，已有可构建的 app + engine、Room 本地存储、系统使用事件采集、后台补采、日/周图表、时段复盘、标注及备份导出。页面按最新反馈改为图表优先，原始会话仅在详情主动展开。具体实现范围、验证结果与尚未完成的部分见 [开发与验收](docs/05-delivery.md)，不代表全部 P0 已验收。

## 阅读顺序

| 文档 | 内容 |
| --- | --- |
| [产品需求](docs/01-product.md) | 用户场景、产品取舍、第一版范围 |
| [交互设计](docs/02-experience.md) | 时间线、选区复盘、标注、状态文案 |
| [统计与数据契约](docs/03-data-contract.md) | 时间口径、会话、缺失、分类和保存策略 |
| [技术架构](docs/04-architecture.md) | 分层、数据模型、同步与工程目录 |
| [开发与验收](docs/05-delivery.md) | 分阶段交付、样例、真机验收 |
| [平台依据](docs/06-platform-notes.md) | Android 接口事实、参考链接、待验证项 |
| [功能打磨与建议](docs/07-improvements.md) | 目标、复盘与后续产品优先级 |
| [更新与发布](docs/08-updates.md) | GitHub 更新、签名与发布流程 |
| [状态空档诊断](docs/09-capture-diagnosis.md) | 启动、屏幕与锁屏状态的修复依据 |
| [组件与未使用时段](docs/10-widgets-and-away.md) | 共享统计投影、桌面设计与更新机制 |

原始输入完整保存在 [初稿归档](docs/archive/initial-prd.md)。正式决策以以上分工文档为准；实现中改变口径时同步更新文档。

## 目录约定

| 路径 | 用途 |
| --- | --- |
| `app/` | Android 模块，系统接入、数据库、页面 |
| `engine/` | 纯 Kotlin 模块，会话还原与统计计算 |
| `docs/` | 产品、交互、数据、架构、验收文档 |
| `docs/archive/` | 初始资料归档 |
| `scripts/` | 签名构建与发布脚本 |

各模块的 `build/` 是可重新生成的构建产物；临时调试文件统一放在根目录的 `build/diagnostics/`，按需生成，任务结束清理。

applicationId 为 `io.github.goooseby.tickmark`，最低 Android 10 / API 29，当前编译/目标 API 36。第一版按单设备、个人使用设计。

包名在首次发布前统一为 GitHub 用户名对应的 `io.github.goooseby.tickmark`；后续发布保持稳定。仓库名不决定 applicationId。早期开发包属于不同应用，不作覆盖升级，必要时用逻辑备份迁移记录。

构建：`./gradlew.bat :app:assembleDebug :engine:test :app:lintDebug`。APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。本机 SDK 使用忽略提交的 `local.properties`。
