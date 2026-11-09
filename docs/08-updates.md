# GitHub 更新与发布

## 网络边界

使用记录、目标和复盘仍在本机。应用新增 INTERNET 权限，仅更新客户端访问 GitHub API 和 Release 安装包；没有账号、遥测或轨迹上传。自动检查默认关闭，开启后在前台每日最多检查一次。下载必须由用户点击，可使用移动网络，按钮展示安装包大小。

## 版本约定

- applicationId 固定 `io.github.goooseby.tickmark`。
- 首次公开发布前按用户要求统一包名；源码、测试、组件和安装文件提供器使用相同身份。此前开发包属于独立应用，不承诺覆盖安装或自动继承本地数据，必要时用JSON备份迁移。
- 版本名称与 versionCode 在用户明确要求发布时调整。0.5.1 对应 versionCode 6。
- 正式版本 tag 为 `v主.次.修订`，如 `v0.2.0`；versionCode 严格递增。
- 最新版本通过 `GET /repos/goooseby/Tickmark-android/releases/latest` 查询，草稿和预发行不进入默认渠道。
- 安装包命名 `tickmark-v0.2.0.apk`，使用 GitHub 资产 SHA-256 digest；没有校验信息时只提供发布页，不直接安装。
- 检查无发布、已最新、网络失败、限流分别显示。离线不删除已取得的版本信息。

## 下载与安装

使用 Android DownloadManager，下载任务与版本信息跨进程保存。在更新页读取进度，取消后可以重试；不持续后台轮询。安装前重新检查文件大小、SHA-256、包名、递增的 versionCode、版本名称和签名一致性，再经 FileProvider 交给系统安装。不静默安装。

没有未知来源安装权限时打开本应用的系统授权页；返回后由用户再次点击安装。开发调试包与正式发布包证书不同，禁止直接互相覆盖；实际用户应始终从同一正式签名渠道安装。更新不清空数据库。

## 发布工作流

GitHub Actions 检查构建与算法测试；手动发布工作流接收版本与 versionCode，读取仓库签名 Secrets 构建正式 APK，生成 SHA-256 与发布说明，并创建草稿 Release。开发者检查草稿后发布，更新客户端随后可发现该版本。草稿的目的是让安装包和说明先可核对；并不要求普通用户了解发布流程。

签名 Secrets：ANDROID_KEYSTORE_BASE64、ANDROID_STORE_PASSWORD、ANDROID_KEY_ALIAS、ANDROID_KEY_PASSWORD。签名密钥不进入仓库。没有签名配置时发布任务明确失败，不以调试证书代替。

## 验证范围

版本大小比较、发布源与安装包选择、摘要失败、旧版本拒绝、页面状态与错误状态由测试覆盖。GitHub 尚无正式 Release 时应显示“尚无正式发布版本”。完整正式渠道覆盖安装需要同一证书签名的两个递增版本，不能用调试包的安装结果冒充正式渠道验证。

依据：[GitHub Releases API](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)、[DownloadManager](https://developer.android.com/reference/android/app/DownloadManager)、[FileProvider](https://developer.android.com/reference/androidx/core/content/FileProvider)。

本机发布密钥保存在 `D:/AndroidDev/Signing/tickmark/`；本机签名构建可运行 `python scripts/build-release.py --signing-config D:/AndroidDev/Signing/tickmark/signing.local.json`。签名配置文件含凭据，已在 Git 忽略规则中排除。GitHub 仓库四项签名 Secrets 已配置，工作流不输出密钥内容。


0.4.0已验证API36模拟器中正式0.3.0覆盖升级，旧复盘在新版记录列表保留；签名、包名及版本校验通过。完整应用内下载与系统安装授权交互仍需单独验证，不能将ADB覆盖安装等同于该流程。详见 [发布验证](validation/README.md)。


开发代码已启用R8与资源联合优化；后续发布会随APK保留匹配校验值的r8-mapping.zip，工作流另保存90天映射artifact。本地按APK SHA-256归档映射。0.5.0首次发布优化构建，旧版0.4.0的资产与校验值保持原样。见[构建优化](12-release-optimization.md)。
