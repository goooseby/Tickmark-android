# Release构建优化

2026-11-08，按用户要求完善构建，不自动调整版本或替换已发布资产。

## 0.4.0体积清单

从公开Release下载APK实测，单位MB为1,000,000字节。APK共49,282,014字节（49.28MB）。

| 部分 | APK内实际占用 | 说明 |
| --- | ---: | --- |
| DEX代码 | 48.054MB | 3个DEX，未开启R8，约占整包97.5% |
| Android资源 | 0.983MB | 包含resources.arsc、界面与图标资源 |
| 原生库 | 0.037MB | 四种CPU架构合计，并非大型原生引擎 |
| assets | 0.009MB | 基线配置和应用分类等 |
| 清单、元数据等 | 0.043MB | Manifest、依赖元数据与许可证等 |
| ZIP结构、对齐及签名开销 | 0.155MB | 与文件条目内容区分 |

APK Analyzer统计扩展图标包包含11,400个类，定义代码约11.39MB，应用源码引用37种图标。自身应用与engine的定义代码约0.65MB。该“定义代码”口径不含共享DEX索引、字符串等，不能直接与上表DEX体积相加。设计候选PNG、文档和调试日志不进入APK。

同级Keye项目已开启minify/shrink；代码行数无法反映依赖和未使用代码的打包体积。Tickmark最低API29默认以未压缩DEX直接加载，APK下载大小也不能等同于其他项目的压缩或安装占用口径。

## 配置

- Release启用R8代码裁剪、优化及混淆，使用proguard-android-optimize.txt。
- 启用资源裁剪；AGP8.13额外启用android.r8.optimizedResourceShrinking，把代码和资源的可达性联合分析。R8使用AGP默认完整模式。
- 保留所有现有语言、CPU架构、屏幕密度及功能。DEX继续使用系统推荐的加载方式，不为压低下载数字引入额外解压开销。
- 两个Glance组件保留稳定类名与无参构造，兼容旧版注册信息。Room、WorkManager和ActionCallback使用依赖自带的反射规则，不保留整个应用或整套AndroidX库，也不忽略全部警告。
- CI验证同时构建Debug与优化Release并运行lint。正式发布保存映射压缩包，与该版本APK校验值一起长期保留在Release；工作流另保留90天映射artifact。映射与用户版本说明分开。
- 本地签名脚本执行Release lint、算法测试，并将映射按APK SHA-256归档到忽略提交的build/release-symbols/，避免同版本再次构建覆盖旧映射。

原功能代码不变的签名构建实验从49.28MB降至4.39MB，约减少91%。加入后续功能后的最终产物、运行范围见[验证记录](validation/README.md)。没有以修改业务功能换体积。

依据：[Android R8配置](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization)、[图标库体积说明](https://developer.android.com/develop/ui/compose/resources)、[DEX默认打包方式](https://developer.android.com/reference/tools/gradle-api/8.9/com/android/build/api/dsl/DexPackaging)、[映射与反射排查](https://developer.android.com/topic/performance/app-optimization/troubleshoot-the-optimization)。
