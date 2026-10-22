# Kotlin / M3 Expressive 悬浮卡片

0.2.15 恢复旧版的随手使用方式：从原来的手势区域滑出卡片，在当前界面操作、切卡和收起。M3E 改造发生在浮层内，手势不会打开单独的卡片列表 Activity。桌面与 LSPosed 均进入 `HostActivity` 设置页，首页的“唤出悬浮卡片”通过已有 SystemUI 服务显示原浮层。

## 容器与界面边界

保留原 `CardService`、`CardCoordinator`、外层卡片 View、WindowManager、跟手动效、卡片顺序、锁屏回调和 PAY 安全虚拟屏。校园卡继续使用已有的 `DhuRuntimeBridge` / `DhuPayView`，M3E 校园卡本来就在这个浮层中。

LENS 仅拦截原 `CameraCardView.initComposeView()`，将内部 Compose 内容替换为 Kotlin `LensCardView` 和私有 UI 中的 `LensCardSurface`。原 `CameraCardView` 实例、viewModel、eventHandler、展开状态和卡片回调不变。新视图或依赖无法加载时，继续执行原方法，保留旧版 LENS。不会拦截手势结束和卡片触发方法，也不会启动卡片页面。

界面使用官方 MDC 的 `Theme.Material3Expressive.DynamicColors.DayNight.NoActionBar`、28dp 圆角和 tonal 模式按钮。LENS 在原 280 × 450dp 卡片范围内显示两列快捷模式，深浅色跟随系统，较大字体时改为单列并在同一卡片内部滚动。校园卡和 LENS 的设置图标位于右上角，触摸区为 48dp，没有新增刷新按钮。

校园卡、LENS 和 PAY 共用的外层/内层圆角、内容边距、设置按钮触摸区与图标尺寸集中在私有 UI 的 `card_dimensions.xml`。Kotlin 的 `CardDesign` 与 XML 样式/布局读取同一资源；PAY 的前景和原阴影填充也使用同一圆角来源。功能专属的扫码尺寸、原厂快捷入口几何和设置页面尺寸独立保留。此轮仅收拢定义，沿用 0.2.18 的外观。

## 配置与点击

LENS 读取原设置页使用的 `Settings.Secure.card_setting_data`，按已保存顺序显示已开启、受支持的模式，不再使用新版 Activity 的独立偏好。原 JSON 中的完整 FunctionBean 由原加载器的 Gson 解析后，交给原 `CameraCardEventHandler.handleFunctionClick`；右上角齿轮调用原 `handleSettingsClick`。0.2.20 起模式点击在原事件入口接入卡片内相机显示与触摸，避免原流程先收起卡片再外跳；设置继续使用原处理器。锁屏仍请求原解锁入口，成功后恢复相机卡片，详见 [卡片内相机](lens-embedded-camera.md)。

内容仅在挂载期间观察配置变化；退出时移除观察器和排队任务。宿主与私有 `dhu-ui.apk` 仅交换框架 Context / View / Bundle / String，MDC、AndroidX 和原厂 FunctionBean 不跨越私有 UI 的 ClassLoader。没有为浮层引入 Compose Lifecycle / SavedState 宿主依赖。

0.2.19 使用共享 Kotlin 挂载作用域管理校园卡与 LENS 的监听和队列，阻止上一轮挂载的迟到任务进入重新打开的卡片；配置重建先准备新内容，再替换旧内容。边界与回归范围见 [浮层挂载生命周期](floating-card-lifecycle.md)。

校园卡的授权、Provider、会话协议、二维码和条码生成，以及失活清码、离屏和锁屏检查保持原实现。PAY、记忆和卡券继续使用原有浮层功能界面；本版本不宣称全部卡片已重写。

## 升级兼容

删除 0.2.14 的 `NativeCardRouting`、新版手势开关、独立 LENS 设置页和全屏卡片容器。旧配置请求不能再次开启页面重定向。`NativeCardsActivity` 仅作为透明的旧快捷方式兼容入口：先关闭仍在运行的 0.2.14 SystemUI 旧开关，再请求原浮层，随后退出，不显示卡片页面。它不再是桌面入口。

更新后应重启手机，让 SystemUI 重新加载本版 Hook。配置请求仍验证发送 UID、固定操作和 5 秒有效期。Android 34 以下不发送缺少共享身份支持的配置请求，显示明确提示。

## 验证

- 离线边界回归覆盖 13 项：仅替换内部初始化、同一原卡片及窗口所有者、重复初始化保留单份内容、原子视图保留、缺失依赖回退、挂载失败清理和未知 ABI 拒绝。
- LENS 配置测试覆盖原排序和完整启动数据、禁用或不支持模式、去重、空选择和损坏 JSON。
- 私有 UI 在 API 31 / 36、深浅色和 1.0 / 1.3 / 1.6 字体倍率下测量原 280 × 450dp 卡片，检查图标实际绘制、触摸区、标题和内部滚动；校园卡继续验证大字体下的扫码区域。
- Android 36 安装最终统一 APK，验证桌面设置、MODULE_SETTINGS、运行设置、校园卡设置四个入口，并将真实私有 M3E 视图挂载到非 Activity 的 WindowManager 浮层，检查布局和操作回调。测试临时授予悬浮窗 app-op，并在结束时恢复原值。
- 保留 DHU 协议、PAY 用户路由及启动回归。

标准 Android 模拟器不能验证目标 ColorOS + Vector 的系统手势、真实取码和相机模式。真机仍需验收滑出/取消、左右切卡、收起、锁屏解锁续接，以及实际扫码和相机启动。自动化的悬浮窗挂载测试只证明视图可在非 Activity 窗口使用，不等同于完成 ROM 手势验收。
