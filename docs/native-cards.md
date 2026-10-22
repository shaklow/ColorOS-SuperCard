# Kotlin / M3 Expressive 自有卡片：第一阶段

桌面启动 `NativeCardsActivity`，校园卡和 LENS 使用自有 Activity 窗口与 Kotlin 视图；不创建 vivo CardService、不反射原版容器。LSPosed 的 MODULE_SETTINGS 保留 `HostActivity`。统一合包脚本也将唯一桌面入口指向新 Activity。

## 界面

私有 `dhu-ui.apk` 内的 `CardDesign` 定义动态配色、28dp 圆角、48dp 图标触摸区与 tonal 按钮。`NativeCardsSurface` 提供居中最大 600dp 的滚动容器；校园卡、LENS 和“更多卡片”依次排列。LENS 在空间充足时使用两列，大字和窄屏使用单列。深浅色跟随系统；进入动效尊重系统动画开关。全局设置、校园卡设置和 LENS 设置均使用右上角图标，没有新增刷新按钮。

校园卡沿用现有 MDC M3E 组件和 `DhuPayView` 控制器，协议、Provider、授权和编码未重写。LENS 复用 `ColorOsLensModes` 的官方相机 Intent，支持 11 个模式和独立的 Kotlin 设置页；可全部关闭，选择立即保存，返回卡片时重新显示。新选择不覆盖原版卡包配置。

宿主与 UI 仍只交换 Context、View、Bundle 和字符串，MDC / AndroidX / 私有 Kotlin 类型不跨 ClassLoader。没有引入 Compose 的 Lifecycle / SavedState 宿主依赖。

## 唤出与兼容

设置页的“解锁后手势打开新版卡片”默认关闭，读到 SystemUI 的有效配置后才可修改，保存以服务读回确认。开启后复用原手势边界与原有成功/取消判定，在成功唤出后切换到自有 Activity。手势识别和跟手阶段仍经过原引擎，第一阶段未重写输入监视器。锁屏和熄屏继续使用原路径，指定支付功能的触发也保留原路径。

“更多卡片”通过现有校验发送 UID 的配置协议打开原卡包，保留 PAY、记忆和卡券。请求只接受固定操作，附 5 秒有效期，不转发外部组件和 Intent。新路由加载失败不阻断原路径。

新窗口在内容挂载前设置 FLAG_SECURE。校园卡失去焦点、页面暂停或销毁时立即停用并清码；离屏与锁屏仍由原控制器检查。滚动时至少 60% 的卡片高度可见才取码。扩大码图保持独立安全窗口。日志不记录授权和付款码。

## 验证与后续

CI 运行原有协议、PAY 路由、启动和资源校验，以及 API 31 / 36 单元测试、新卡片深浅色/窄屏/大字布局与设置持久化检查。Android 36 设备测试安装最终统一 APK，覆盖桌面卡片、LSPosed 设置、运行设置、校园卡设置、LENS 设置五个入口，并检查右上角导航、LENS 选择返回更新和安全窗口标志。

目标 ColorOS + Vector 真机仍需验收手势成功/取消、系统允许的后台 Activity 唤出、实际取码与 11 个相机模式；标准 Android 模拟器不能验证这些 ROM 集成。PAY、记忆和卡券界面仍在原容器，尚未宣称完成去原引擎化。下一阶段拆分记忆与卡券的数据接口，再迁移其显示；PAY、锁屏和安全虚拟屏最后处理。
