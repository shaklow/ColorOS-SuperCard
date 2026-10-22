# M3 Expressive 设置界面

第一阶段统一设置首页、运行状态与 PAY 设置、校园卡授权设置。设置首页使用 `HostActivity`（桌面、LSPosed 的 `MODULE_SETTINGS` 入口及卡片右上角设置），首页可唤出原悬浮卡片，见 [悬浮卡片改造](native-cards.md)；“管理卡片”自 0.2.23 起使用 [Material 管理页](card-management.md)，保留原卡片配置与功能编辑入口，“手势与唤出”保留现有手势设置。其他卡片的重绘不在本阶段内。

## 设计规范

| 项目 | 规范 |
| --- | --- |
| 主题 | `Theme.Material3Expressive.DynamicColors.DayNight.NoActionBar`，随系统深浅色 |
| 页面背景 | `colorSurface` |
| 分组表面 | `colorSurfaceContainerLow`，无阴影与描边 |
| 重点表面 | `colorPrimaryContainer`，用于首页入口和连接摘要 |
| 正文与辅助文字 | `colorOnSurface`、`colorOnSurfaceVariant` |
| 状态 | 文字说明为主，主色表示已确认，错误色表示检测或连接异常；不只依赖颜色 |
| 页面宽度 | 最大 600dp，内容居中，两侧 20dp 留白 |
| 卡片圆角 | 28dp |
| 字体 | 页面标题 32sp、重点结果 24sp、入口标题 17–18sp、正文 16sp、说明 12–14sp |
| 操作尺寸 | 主按钮至少 52dp，返回触摸区至少 48dp，触摸开关行至少 56dp |
| 适配 | 滚动内容，文字允许换行，接入系统栏、刘海与输入法 Insets |

公共尺寸集中在 `dhu-ui/src/main/res/values/settings_dimensions.xml`；组件与主题角色集中在 Kotlin `SettingsSurface`。主要动作使用 MaterialButton，设置开关使用 MaterialSwitch，授权输入使用 TextInputLayout / TextInputEditText，选择与清除确认使用 MaterialAlertDialog。

## 页面与交互

- 设置首页按“卡片与功能”“连接与授权”“使用偏好”组织入口，只读取校园卡的配置状态，不展示链接或付款码。
- 运行状态分别显示 Root、框架、SystemUI 和安全虚拟屏。权限与后台详情默认收起，按需展开；未连接不等于未安装框架。
- PAY 触摸设置按微信、支付宝、微信分身分组，保留九项原有开关。取得有效诊断前禁用；保存后以服务读回值确认成功，失败时恢复上次确认状态。
- 用户选择保留已选但已删除的分身信息，不静默切换主用户；选择弹窗提交明确的用户编号。
- 校园卡授权支持保存并验证、验证已保存链接和清除。无效链接使用输入框错误提示；清除成功后才清空文本。网络验证返回时检查页面生命周期与请求代次，丢弃过期结果。
- 校园卡设置保留 FLAG_SECURE，禁止输入框自动填充及 View 状态保存，不从外部 Intent 接受授权链接。链接、Cookie、付款码仍由原 Provider 管理，不写日志。

## Kotlin 与加载边界

三个 Activity 保留原有组件名称并迁移为 Kotlin。系统桥接、Hook、Root 后端和取码协议维持现有实现。

M3E 控件仍在私有 `assets/dhu-ui.apk` 内。通过原有隔离 ClassLoader 和资源 Context 加载，宿主仅交换框架 View、Bundle、String、Runnable 和 IntConsumer。宿主不直接引用 MDC 类型，不改变原厂 0x7f 资源表。

设置页异常时显示明确的不可用提示，不回退成可编辑但无法确认保存的假界面。Material 对话框在页面分离时关闭，避免 Activity 窗口泄漏。

## 验证范围

UI 测试覆盖 API 31 / 36、真实 Material 组件、全部设置入口、九项开关初始禁用、权限详情展开、用户选择回调、授权输入保护，以及窄窗口、大字体和深浅色布局。

宿主状态测试覆盖诊断类型校验、虚拟屏未就绪但服务已连接、分身消失、损坏用户列表，以及保存读回不一致。CI 同时运行宿主与私有 UI 模块的单元测试。实际 Root 授权、SystemUI 连接和手机键盘/系统栏仍需目标 ColorOS 真机验收。

宿主生命周期测试覆盖三个 Activity 在 API 31 / 36 上的首次创建、恢复和销毁，包括私有 UI 不可用时的提示页。修复前六个用例都在 `PhoneWindow.getInsetsController()` 报空 DecorView；系统栏外观现于 `onPostCreate` 中设置，并通过 `decorView` 获取控制器。此项回归与 Android 35 完整 APK 上捕获的启动异常一致。

安装后的启动测试与 Robolectric 布局测试分开执行。使用 Android 31+ 测试设备，先安装统一 APK，再安装同一次构建、相同签名的测试 APK：

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
python tools/unified-prototype/build.py --version-code 20 --version-name 0.2.13-settings-test --sign
adb install -r tools/unified-prototype/out/supercard-unified-prototype.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
python tools/test_settings_startup.py
```

`SettingsStartupTest` 检查桌面和 LSPosed 的入口解析，真正启动三个 Activity，等待首次绘制与异步状态回调，确认控件已布局且来自私有 `DelegateLastClassLoader`。私有界面加载失败、只显示兜底提示时测试会失败。测试不提交授权链接或更改 PAY 开关。CI 在硬件加速的 Android 36 模拟器上运行这项测试，安装的就是对应构建产物中的统一 APK。
