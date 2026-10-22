# PAY 外层 M3 Expressive 适配

0.2.17 在原 PAY 卡片及原快捷入口控件上应用 Kotlin / MDC M3 Expressive 外观：动态语义色、28dp 卡片圆角、20dp 入口圆角、选中状态和按压反馈，以及 48dp 的右上角设置触摸区。字体尺寸、入口顺序、现有标签与图标来源继续使用原配置。这是一轮外层视觉适配，未替换整个 PAY 运行时。

卡券和记忆的布局与交互不作改动。0.2.16 的钱包连接修复保留在本版。

0.2.18 修正 PAY 四角白边：原 `bg_shadow` 是 20dp 圆角的半透明白色填充，前景改为 28dp 后会露出四角。现在前景与该背景层统一使用 28dp 轮廓和 `colorSurfaceContainerLow`，保留背景层的原 elevation、alpha、可见性及淡出流程。

中部增加主题色钱包图标，作为原 `cardFrame` 背景中的 Drawable 绘制，不增加 View、点击目标或无障碍节点。绘制范围位于标题下方及原 `linearShadow` 底部功能区域上方；空间不足时不绘制。图标随原空闲背景淡出、隐藏或还原，不进入 `payShow` / `payClick`，不遮盖支付内容。

## 接入边界

`PayChromeBridge` 只在原 `PayCardView.initViews`、`setSkinDataToCard`、`updateCurrentSkin`、`onAttachedToWindow` 及原 `PayCardAdapter.onBindViewHolder` 完成后装饰已有框架 View。安装前检查所有字段、方法与返回类型，依赖或 ABI 不匹配时保留原界面。私有 UI 中的 `PayCardAppearance` 不读取原厂模型，不持有钱包、支付账号或功能数据。

跨加载器仅传递 Context、String、View[]，MDC 与 AndroidX 资源和实现继续封装在 `dhu-ui.apk`。不重新设置点击或触摸监听，不替换 RecyclerView Holder，不移动、添加或删除付款内容；不接触 `payShow`、`payClick`、虚拟显示管理器、HoleDrawable、锁屏鉴权或 WindowManager。设置仍调用原事件处理器。

外观绑定新增 `shadowBgView` 与 `linearShadow` 的字段签名检查，离线 ABI 校验覆盖实际内置 DEX 的 15 个入口。公共私有 UI 加载桥接已抽取为 Kotlin `MaterialUiArchive`，见 [公共界面加载边界](ui-archive.md)。

展开后由微信、支付宝绘制的付款页面与现有付款容器保持原样。此轮没有新增刷新按钮、手势路由或独立卡包页面。

## 验证

UI 测试覆盖 API 31 / 36、深浅色与 1.0 / 1.3 / 1.6 字体倍率，验证设置尺寸、文字对比度、原 View 所有者、点击回调、入口尺寸、原图标、标签与选中标志，以及无效绑定在修改界面前被拒绝。渲染图使用框架视图构造固定尺寸夹具，证明的是外观适配与控件约束，不代表支付页面或真机功能验收。

0.2.18 渲染夹具加入原 20dp 白色背景层，先验证能复现漏白，再检查修复后四角透明、图标实际绘制、原背景隐藏及 alpha=0 时图标消失、底部功能占满空间时不绘制，以及反复绑定不新增子 View。已安装 APK 的浮层测试也检查真实私有 Drawable 的多层边角和显隐。

设备测试在已安装统一 APK 的非 Activity 浮层中调用真实私有 UI 外观接口，检查挂载、设置回调和付款内容所有者占位夹具保持不变。PAY 用户路由与启动回归继续执行。ColorOS + Vector 的实际支付启动、触摸、锁屏续接和多用户仍需真机验证。
