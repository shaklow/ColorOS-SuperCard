# 公共界面加载边界

0.2.18 将设置、校园卡、LENS、PAY 共用的私有界面加载器从 Java 适配类中提取为 Kotlin `MaterialUiArchive`。`DhuMaterialUi` 保持现有 Java 调用接口及校园卡降级视图，功能端无需迁移。

共享状态只保存已验证的 APK 资产、隔离 ClassLoader 和框架类型组成的方法契约。初始化时一次性核对所有静态方法的参数与返回类型，全部完成后才发布缓存。后续快捷入口重绑不再重复解析 APK manifest 或查询每个方法。

每次创建界面均根据本次宿主 Context 生成独立 Resources、Theme 与包装 Context，不缓存 Activity、窗口、View 或某一窗口的字体、深浅色、密度配置。派生 ConfigurationContext 与 DisplayContext 继续使用同一私有资源资产，同时沿用新宿主配置。LayoutInflater 仍来自私有归档，不使用宿主的 AppCompat Factory2。

跨加载器仅使用 Context、View、Bundle、String、原始类型及框架回调；MDC、AndroidX、Kotlin 实现和资源 ID 不从界面归档传入原厂模型。校园卡安全清码、LENS 功能调用、PAY 支付虚拟屏和触摸路由不属于此加载器。卡券与记忆仍使用各自现有界面。

Android 36 已安装统一包的测试覆盖四个设置入口、非 Activity 窗口中的真实隔离界面、校园卡字体配置、PAY 多层背景及图标显隐，同时验证派生字体/夜间配置不会修改原 Context 的配置或复用其 Theme。
