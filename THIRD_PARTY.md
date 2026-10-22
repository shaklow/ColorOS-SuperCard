# 第三方组件与原版输入

本仓库包含研究和构建超级卡包兼容层所需的原版输入。vivo、OPPO、OnePlus、微信、支付宝等名称及原厂资源的权利归各自权利人。本仓库没有为原厂 APK 或资源授予额外许可，也没有为整个混合产物声明统一开源许可证。

## 原版输入

| 文件 | 用途 |
| --- | --- |
| `app/src/main/assets/supercard-resources.apk` | 原超级卡包 APK，提供原资源表、布局、图标与原始 DEX 再生输入 |
| `app/src/main/assets/memory-plugin.apk` | 原记忆卡插件资源与补丁再生输入 |
| `app/src/main/assets/coupon-plugin.apk` | 原券码卡插件 |
| `app/src/main/assets/supercard-code.zip` | 由仓库补丁工具生成的超级卡包 DEX |
| `app/src/main/assets/memory-code.zip` | 由仓库补丁工具生成的记忆插件 DEX |
| `vendor/supercard/AndroidManifest.xml` | 原 Manifest 的可读副本，供统一包合并受保留组件 |

输入 SHA-256 见 `vendor/inputs.sha256.json`。仓库没有打包钱包、小布记忆、微信、支付宝、ColorOS 相机或 Vector 的完整 APK；这些应用需在设备上已有。

## 开源依赖

- [Material Components for Android 1.14.0](https://github.com/material-components/material-components-android/tree/1.14.0)：Apache-2.0，提供校园卡与设置页的 M3 Expressive 主题及组件；连同其 AndroidX 依赖封装在独立界面模块中。
- [Robolectric 4.16.1](https://github.com/robolectric/robolectric/tree/robolectric-4.16.1)、JUnit 4.13.2：仅用于 JVM 界面测试，不进入安装包。
- [ZXing core 3.5.3](https://github.com/zxing/zxing/tree/zxing-3.5.3)：Apache-2.0，用于校园卡二维码和 CODE128 编码；依赖哈希固定于 Gradle 校验清单。
- [JSON-java 20240303](https://github.com/stleary/JSON-java)：仅用于离线校园卡测试，应用运行时使用 Android 自带的 `org.json`；测试下载固定 SHA-256。
- [DexKit 2.0.7](https://github.com/LuckyPray/DexKit/tree/2.0.7)：DEX 特征查询，原项目许可证与源码见该版本。
- [libxposed API 102](https://github.com/libxposed/api)：由 Vector 运行时提供，项目仅 `compileOnly` 编译引用。
- [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass)：隐藏 API 访问辅助。
- [jadx 1.5.5](https://github.com/skylot/jadx/tree/v1.5.5)：使用发行 JAR 中的 DEX 工具库合包，构建脚本固定下载哈希。
- [smali / dexlib2](https://github.com/JesusFreke/smali)、Guava、smali-util、JSR305：补丁资产精确重建依赖；原许可证、分发版本与哈希见 `tools/asset-patcher-libs/`。
- Kotlin、FlatBuffers、Rikka CXX 等传递依赖由固定 Maven 坐标解析，保留其各自许可证。
- [Vector](https://github.com/JingMatrix/Vector)：设备运行前提，不包含在项目构建产物中。

如需修改或再分发，请分别核对各原版输入和第三方组件的授权条件。

ColorOS 后台和关联启动 provider 适配参考 OpenAlive（Maga-King and OpenAlive contributors，MIT）；相关许可见 `vendor/openalive/LICENSE`。未修改参考项目。
