# Root 显示桥接

0.2.22 在浮层四阶段改造完成后，继续拆分安全显示客户端。PAY 与 LENS 的显示名称、尺寸、density、flags（19917）及原 SDK 的 VirtualDisplay 返回值保持不变，卡券和记忆界面不调整。

| 组件 | 职责 |
| --- | --- |
| RootDisplayClient | 原公开入口、APK starter 绑定与退避、PAY 安全窗口请求、创建失败回滚 |
| RootDisplayConnection | 一次发布端点与 nonce，握手与死亡监听，拒绝旧连接的排队通知 |
| RootDisplayTransactions | 同步 v1 Binder 编码、显式检查 transaction 支持、异常读取与 Parcel 回收 |
| RootDisplayWrapper | 将 root 返回的正数副屏 ID 和 callback token 包装成系统 VirtualDisplay |
| RootDisplayProtocol | 客户端与 daemon 共用的既有 action、descriptor、操作编号及显示 flags |
| RootDisplayDaemon / CardDisplaySlots | 原 SystemUI 身份授权、两个固定名称槽位和 root 显示资源归属 |

## 修复的时序

旧客户端收到 Binder 死亡通知后立即清空连接，再向主线程排队清除 PAY 安全状态；如果新后端的广播先完成重连，旧通知仍会清除新连接的状态。

现在广播只在主线程握手和发布连接，死亡监听可以在 Binder 线程作废对应会话；主线程应用断线效果前再次比较连接代数。新连接建立后，旧排队回调和旧 recipient 均失效。连接期间死亡不会被发布，握手或死亡注册失败也不会覆盖仍可用的连接，替换时解除旧 recipient。

每个显示创建操作捕获固定的端点及 nonce，包装失败后的 RELEASE 发回该端点。即使创建期间接收到新的广播，回滚也不会发给新后端。没有正数副屏 ID 或缺少 callback token 时拒绝包装；主屏不会进入回滚释放。

## 保留的边界

v1 操作编号和字段顺序保持兼容。daemon 仍要求 SystemUI UID、正确 nonce、存活 lifetime Binder、同步双向调用、有效 Surface、正数几何和原安全 flags，只允许固定 PAY/LENS 槽位。原 callback getter/字段回退保留。APK starter 的绑定、三秒 PAY demand debounce、背景退避与周期重连规则保持原样。

## 验证

- 离线执行实际连接、通信和客户端源码，覆盖旧死亡通知、重复广播、握手失败、连接期间死亡、回滚端点归属、缺失操作及 Parcel 回收。
- 原 PAY 启动退避与用户路由、真实原厂 DEX ABI 回归。
- API 31/36 使用 Android Parcel 验证 v1 握手/创建/释放字段、远端异常及缺失操作，后台握手不能绕过主线程序列。
- Android 36 安装后使用真实显示 callback 调用生产包装器，再运行副屏 Activity 与触摸回归。
- 发布时比较 40 张既有界面渲染图，核对资源和固定签名。

标准模拟器没有 root daemon 的 SystemUI 运行环境、Vector 与 ColorOS 相机 HAL，不能代替目标 ROM 的特权和硬件验收。
