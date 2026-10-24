# 自研 PAY 支付卡（0.2.37）

PAY 与校园卡、LENS 共用 NativeCardHost 的原版 288×466dp 几何、跟手唤出、连续切卡和叠放收起。NativePayCard 与私有 UI 的 PayCardSurface 替换原 PayCardView；NativePaySession 与 NativePayBackend 接管副屏、输入、任务和释放，不创建原 PayVirtualViewManager、FunctionBean、PayCardViewModel 或 CardEventHandler。

## 界面与配置

读取同一份 `Settings.Secure.card_setting_data`，保留已启用且受支持的微信、支付宝功能顺序和原图标资源。快捷入口、卡内底部切换、设置、返回和全屏入口均位于同一卡片。卡片处于后方时不启动支付；切走时释放显示并记住当前功能，回到前台后重新打开，关闭整个卡包后重回快捷入口。退出付款页、锁定、配置或账号变化时取消排队工作并释放显示。大字体下快捷入口增加行高，底部入口可在卡片内横向滚动，卡内支付区仍保留原版多功能区的 21dp 左右、51dp 顶部和 85dp 底部边距；仅一个功能时底部为 21dp。

配置存在未迁移的已启用卡片、PAY 功能或协议未就绪时，默认手势继续使用原完整卡包。只启用 PAY、校园卡和 LENS 且功能均受支持时，自研核心按原保存顺序接手。首页预览加入 PAY，原完整卡包入口继续保留。

## 业务与系统边界

- NativePayLauncher 只复用原 PayCardSDKLauncher 与 PayCardIntentBuilder 的启动协议，微信扫码、付款码及乘车小程序的参数保持原样。支付宝继续使用原深链，保留原启动 flags 及 SchemeLauncherActivity 卡住 1.5 秒后的一次重试。
- PayUserSettings、PayUserRouting 和 PaySessionState 继续提供独立的微信/支付宝账号选择、显式用户与显示、分身跳过选择器、微信 SDK 用户 Context，以及旧请求失效检查。新会话发布之前清理旧会话，关闭时仅能清理自己的快照。
- NativePayBackend 使用既有认证 RootDisplayClient 和固定 `SuperCard_Virtual` 显示槽位，保持原安全标志与密钥流程。CONTAINER 模式按物理屏宽和视口比例渲染，密度使用实际屏幕值；微信付款码保留放大显示条件。没有新增 root 操作或传输支付内容。
- 任务观察继续核对显示与账号，再交给当前 NativePayDisplayOwner。输入沿原免拦截白名单及功能级触摸开关；已知认证页禁止卡内输入，转全屏时核对当前任务仍属于该显示与用户，再移动同一任务，保留页面状态。扫码到达结果页后保留原 300ms / 小程序 1000ms 的转全屏延时。
- 微信通用 UIPageFragmentActivity 也可能是付款页面，不能仅凭名称自动当成认证页。它保留卡内显示和点击转全屏保护，附加触摸开关不对该容器放行；已知指纹、手势及权限页走认证转全屏。
- 每次启动使用新的纹理和 Surface，旧纹理回调按所有者拒绝；加载遮罩等待页面与新帧后再显示。显示断开时停止输入并退回快捷入口，避免停留在旧付款画面。手势取消、切卡、释放时先向原显示发送 CANCEL，再释放会话和 Surface；迟到的 SDK、等待后端和重试任务不能恢复已关闭的卡片。

## 验证范围

单元回归覆盖配置排序与能力筛选、原版显示几何、账号捕获、过期快照、页面触摸策略、认证与扫码结果转场、有限重试、坐标映射、取消和一次性释放。私有 UI 在 API 31/36、深浅色及 1.0/1.3/1.6 字体倍率下测量并渲染。

Android 36 安装回归检查三张真实卡片的切换，并以测试 Activity 在真实安全副屏中验证生产 NativePaySession 和 NativePayBackend 的缩放触摸、默认保护、CANCEL、当前任务转全屏及实际显示释放。测试不调用真实微信、支付宝或付款操作；测试使用的安全显示权限覆盖和任务权限在结束时恢复。真实 ColorOS 的 SDK 落屏、各支付应用页面和分身行为仍需 ADB 恢复后验收，模拟器通过不等于实际支付链路已验收。
