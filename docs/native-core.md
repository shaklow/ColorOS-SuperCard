# 自研 Kotlin 卡包核心：首期

0.2.29 的自研入口位于首页“新卡包预览：校园卡与 LENS”。它使用独立 NativeCardHost 窗口和 NativeCardDeck 内容会话，不创建 vivo CardService、CardCoordinator、原卡片实例或 FunctionBean。原手势入口继续运行现有卡包；记忆卡、卡券和 PAY 尚未迁入新容器，不显示占位卡片，也不更改它们的界面。背景功能保持移除。

首期在新窗口中承载现有校园卡 Material 界面，并用 NativeLensCard 直接读取已保存模式，调用独立 LensCameraSession/LensDisplayBackend。校园卡通过 CardWindowOwner 回调关闭自己的窗口和维护保护，不再访问原窗口。相机沿用相同后端、模式 Intent、触摸缩放与 CANCEL/释放，保护回调由新窗口承接。原容器中的两张卡片继续保留原行为。

## 归属与系统边界

- NativeCardDeck 是不依赖 Android 的 Kotlin 内容状态机，负责单一活动卡片、失败回滚、关闭和旧异步结果令牌。
- NativeCardHost 只负责自己的 WindowManager 窗口、内容挂载、切换、关闭及导航；窗口始终 FLAG_SECURE，关闭/切卡清理上一张内容。导航区域的横滑不会抢相机内部触摸或模式列表滚动。
- NativeCardRuntime 处理入口、卡片配置和屏幕状态；仅启用已迁移且已开启的卡片，锁定、熄屏、关闭总开关或配置失效时移除窗口。
- ColorOsCardAccess 独立处理真实锁屏检查及 ActivityStarter 解锁。需要实际解锁后才打开内容；取消、超时和过期回调不会重新打开卡片。
- RootDisplayClient 在自研核心初始化时独立连接，原厂 DEX 准备失败不妨碍其建立连接。客户端与 daemon 的认证、固定显示槽位和协议不变。
- 私有 UI 档案仅通过 View/Bundle/Consumer 跨加载器通信，继续隔离 SystemUI 的 AndroidX/MDC 与资源。

## 验证与推广门槛

API 31/36 验证窗口导航布局、内容归属、失败清理、重开和旧令牌失效。Android 36 安装回归创建真实独立浮层，操作真实 Material 导航、验证 FLAG_SECURE、关闭/重开和锁定门禁清理，再将真实副屏 TextureView 接入该容器，沿生产 LensDisplayBackend 启动 Activity 并注入触摸。

CI 使用 `--secure-native` 明确要求可 root 的测试模拟器：以 root adbd 启动 instrumentation，通过 UiAutomation 的测试专用权限覆盖临时授予本测试 UID CAPTURE_SECURE_VIDEO_OUTPUT，创建真实 SECURE 虚拟显示并核对系统显示标志。覆盖在 finally 中撤销，生产权限、代码和 root daemon 认证没有放宽。测试不请求付款、不获取真实相机素材、不修改用户授权链接。普通设备不带该参数时不宣称完成安全显示验证。

标准模拟器缺少目标 ColorOS、Vector、原相机 HAL，以及真实小布/钱包账号。模拟器结果不能证明目标 ROM 的解锁入口、原系统相机全部模式或 root daemon 的实际策略已验收。因此本版提供单独试用入口，保持原手势路由；目标 ROM 链路确认后再推广为默认入口并迁移 PAY，随后按界面约束逐张迁移记忆和卡券。自研核心首期完成不等于全部原厂运行代码已删除。
