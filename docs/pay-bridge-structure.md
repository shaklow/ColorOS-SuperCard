# PAY 桥接职责拆分

0.2.21 将原 `PayRuntimeBridge` 中的启动身份和可选触摸策略拆成 Kotlin 组件，继续使用原 PayVirtualViewManager、窗口、虚拟屏和业务入口。卡券与记忆界面不接入这些组件。

| 组件 | 职责 |
| --- | --- |
| PayRuntimeBridge | 安装桥接、观察任务、校正同显示/用户的 taskId、通知原插件 |
| PaySessionState | 一次发布 manager / function / user / generation 的不可变快照，拒绝旧快照清理新会话 |
| PayTouchPolicy | 绑定原 PAY View、记录当前页面、刷新函数级触摸策略、清理隐藏 View 引用 |
| PayTouchAccess | 原白名单优先、目标应用及页面匹配、认证页面例外的决策 |
| PayDisplayReadiness / PayUserRouting | 原有后端就绪等待、SDK 和跨用户启动路由 |
| RootDisplayClient / RootDisplayDaemon | 原安全显示创建、认证与释放 |

过去任务线程逐个读取多个 volatile 字段，启动切换期间可能混合不同会话的用户、管理器和代数。现在轮询、排队回调与显示查询都使用同一份快照，主线程应用结果前再次核对身份；原公开桥接 API 保留。变更用户时先作废快照，再释放原管理器，旧 SDK 请求仍由原 generation 检查拒绝。

触摸策略沿用原规则：原厂已允许的页面优先；附加放行只对当前安全显示、已观察到的目标应用及页面和开启的函数生效，认证页面不会通过附加规则放行。相同页面/显示被新功能复用时按新代数刷新，原认证/全屏和虚拟遮罩继续工作。反射查找继续支持父类中的保护方法，所有签名在安装 Hook 前解析。

验证包括会话复用和旧回调失效、并发发布的一致性、触摸条件和认证规则矩阵，以及现有 PAY 用户/启动检查和 Android 36 安装回归。外层 UI 没有调整；发布前与 0.2.19 的 40 张 UI 渲染结果比较。
