# LENS 卡片内相机

0.2.20 修复 LENS 选择模式后收起浮层、跳到独立相机应用的旧行为。现有模式列表、设置顺序和右上角设置保留；模式点击由原事件处理器入口接入卡片内的 TextureView，显示 ColorOS 系统相机的真实界面与原生拍摄控件。返回按钮释放相机显示并回到模式列表。

## 启动与退出

相机 Intent 保留原 ColorOS action、组件、模式编号和前后摄参数；卡片启动使用 NEW_TASK / MULTIPLE_TASK / EXCLUDE_FROM_RECENTS，去掉桌面快捷方式的 CLEAR_TASK / TASK_ON_HOME。ActivityOptions 必须明确指定正数副屏编号。创建或启动失败在卡片内提示，不降级为主屏相机。

Root 后端新增唯一固定名称 `SuperCard_Lens`，复用原安全、trusted、own-content、destroy-on-removal 显示策略（19917）。仍限定 SystemUI UID、nonce、存活 Binder、正数尺寸及安全 flags，每个固定名称各最多一个显示，不接受任意显示名称或系统调用。原 PAY 切卡时保留显示，相机使用独立槽位；关闭相机不会释放 PAY 显示。PAY 的显示名称、flags 和安全窗口请求保持原逻辑；相机使用独立的安全窗口请求，避免 PAY 观察器清除相机窗口保护。

触摸复制 MotionEvent 后按显示缓冲区与当前 TextureView 尺寸缩放，保留多指与历史数据，只注入本会话的正数显示编号。结束未完成的手势先发 CANCEL，再释放显示。返回、收起、切卡、后台通知、纹理销毁和卸载均关闭会话。destroy-on-removal 防止显示删除后任务落到主屏。

锁屏时继续使用原解锁入口。取消、设置操作和过期请求丢弃待执行模式；成功解锁后唤回 camera 卡片，等待原浮层挂载并展开，再打开选中模式，不在隐藏视图上启动相机。

## 验证

- 离线执行实际点击 Hook：展开状态、避免原收起/外跳、解锁续接、取消、过期、设置覆盖、切卡释放。
- 从实际内置 DEX 校验相机事件处理器及 BaseCardView 的反射签名。
- API 31 / 36 单元测试：全部 11 个 Intent、模式参数、显示编号、坐标缩放、CANCEL、重复关闭与启动失败清理。
- Android 36 安装后的统一 APK：在真实 TextureView 副屏启动测试 Activity，通过实际 LensDisplayBackend 的 ActivityOptions 与输入接口验证显示编号及按钮点击。

标准模拟器没有 ColorOS 相机及 Vector，不能证明目标 ROM 的相机模式、拍照/录像、系统手势和 root 创建策略已经真机验收。相机内部主动跳转或不支持副屏的机型仍需目标手机核对。
