# 自研 Kotlin 卡包核心：首期

0.2.29 的自研入口位于首页“新卡包预览：校园卡与 LENS”。它使用独立 NativeCardHost 窗口和 NativeCardDeck 内容会话，不创建 vivo CardService、CardCoordinator、原卡片实例或 FunctionBean。原手势入口继续运行现有卡包；记忆卡、卡券和 PAY 尚未迁入新容器，不显示占位卡片，也不更改它们的界面。背景功能保持移除。

首期在新窗口中承载现有校园卡 Material 界面，并用 NativeLensCard 直接读取已保存模式，调用独立 LensCameraSession/LensDisplayBackend。校园卡通过 CardWindowOwner 回调关闭自己的窗口和维护保护，不再访问原窗口。相机沿用相同后端、模式 Intent、触摸缩放与 CANCEL/释放，保护回调由新窗口承接。原容器中的两张卡片继续保留原行为。

## 0.2.30：原版手势与连续动画

自研容器恢复 280×450dp 原卡片范围，移除底部翻页按钮，按原交互从右侧手势区域拖入、跟手展开，再按距离或速度完成展开或回弹；在卡包内左右横滑切换，第一张向右滑收起。无障碍保留前后翻页和收起动作。

SystemGestureInput 直接管理 Android InputMonitor/InputEventReceiver；有效区域仍由 GesturePolicy 按已保存的唤出区域和锁屏设置决定。方向确认后才 pilferPointers，底层应用收到取消。展开阈值沿原控制器采用解锁 120dp、锁定 150dp、300dp/s；反向甩动优先取消。锁定时不会预先显示内容，只在完成手势并实际解锁后打开。熄屏继续交给现有 ROM 适配链路，不宣称标准输入监视器能替代厂商 AOD 唤醒。

仅开启校园卡、LENS 时自动由新核心接手边缘手势；仍开启未迁移卡片时，默认保留原完整卡包。首页的预览入口会选用新核心，收起后仍可从原区域滑入这两张卡；首页“唤出悬浮卡片”恢复原完整卡包入口。本次选择不写入持久偏好。NativeGestureCompatibility 只在新核心持有手势时阻止旧控制器重复抢占输入，不参与新核心识别和动效。

NativeGestureViewport 只拦截确认的单指横向手势，纵向滚动、TextureView 相机操作及子视图的禁止拦截请求仍交给内容；多指打断时取消切卡。拖动只更新实际视图的位移与缩放，不截图付款码或相机，不在每个 MOVE 重新布局/创建卡片。前后卡片叠放，下一张从后方揭开，上一张从左侧覆盖；几何回归逐帧验证两种方向、四档密度下均无空隙。旧内容保持挂载直到过渡完成，随后 NativeCardDeck 释放；取消时释放候选卡并回到原卡。松手后从当前位移续接动画，时间按剩余距离和速度在 140–320ms 内调整，连续操作排队续接；锁定、关闭和配置失效立即清理并使旧动画回调失效。

framework-stubs 仅提供 InputEventReceiver/InputChannel 的编译 ABI，使用 compileOnly，不进入 APK。封装检查读取 DEX class_defs，拒绝打包同名平台实现。Android 36 安装回归以 root 测试连接临时覆盖本测试 UID 的 MONITOR_INPUT，测试真实监视器、原始触摸、短滑取消、跟手展开、松手中间帧、卡片横滑、子视图 CANCEL、纵向滚动、连续切卡、首卡右滑收起与动画中锁定清理；测试恢复输入权限、悬浮窗 app-op 和进程动画倍率。API 31/36 同时检查相机 TextureView、多指取消和动画中排队。模拟器中的连续帧证明动画执行与位移续接，不代替目标手机的刷新率/卡顿和 ColorOS 返回手势共存验收。

## 归属与系统边界

- NativeCardDeck 是不依赖 Android 的 Kotlin 内容状态机，负责单一活动卡片、失败回滚、关闭和旧异步结果令牌。
- NativeCardHost 负责自己的 WindowManager 窗口、内容挂载、连续过渡和关闭；窗口始终 FLAG_SECURE，切卡动画完成后清理上一张内容，相机内部触摸及纵向模式列表滚动保留。
- NativeCardRuntime 处理入口、卡片配置和屏幕状态；仅启用已迁移且已开启的卡片，锁定、熄屏、关闭总开关或配置失效时移除窗口。
- ColorOsCardAccess 独立处理真实锁屏检查及 ActivityStarter 解锁。需要实际解锁后才打开内容；取消、超时和过期回调不会重新打开卡片。
- RootDisplayClient 在自研核心初始化时独立连接，原厂 DEX 准备失败不妨碍其建立连接。客户端与 daemon 的认证、固定显示槽位和协议不变。
- 私有 UI 档案仅通过 View/Bundle/Consumer 跨加载器通信，继续隔离 SystemUI 的 AndroidX/MDC 与资源。

## 验证与推广门槛

API 31/36 验证窗口导航布局、内容归属、失败清理、重开和旧令牌失效。Android 36 安装回归创建真实独立浮层，操作真实 Material 导航、验证 FLAG_SECURE、关闭/重开和锁定门禁清理，再将真实副屏 TextureView 接入该容器，沿生产 LensDisplayBackend 启动 Activity 并注入触摸。

CI 使用 `--secure-native` 明确要求可 root 的测试模拟器：以 root adbd 启动 instrumentation，通过 UiAutomation 的测试专用权限覆盖临时授予本测试 UID CAPTURE_SECURE_VIDEO_OUTPUT，创建真实 SECURE 虚拟显示并核对系统显示标志。仅对显示信任、输入注入及后台 Activity 启动采用 shell 权限身份，避免全量 shell 身份覆盖本测试 UID 的安全显示权限。覆盖生效和 finally 撤销后的权限状态均有断言，生产权限、代码和 root daemon 认证没有放宽。测试不请求付款、不获取真实相机素材、不修改用户授权链接。普通设备不带该参数时不宣称完成安全显示验证。

标准模拟器缺少目标 ColorOS、Vector、原相机 HAL，以及真实小布/钱包账号。模拟器结果不能证明目标 ROM 的解锁入口、原系统相机全部模式、系统返回手势共存或 root daemon 的实际策略已验收。0.2.30 的默认接管范围限于只启用已迁移卡片的配置；完整混合卡包仍保留原路由。后续迁移 PAY，再按界面约束逐张迁移记忆和卡券。自研核心首期完成不等于全部原厂运行代码已删除。
