# BookNest · 纯本地 Android 电子书阅读器

一个**零广告、零账号、零网络权限**的 Android 阅读器。所有书库、进度、标注、统计都只存在本机私有目录，卸载即清空。全部能力用 Kotlin + Jetpack Compose 从零实现，没有引入 Readium 之类的第三方阅读内核。

## 功能

**格式**：EPUB 2/3（OPF + spine + NCX/nav 目录，按需解压单章）、TXT（编码自动识别、正则分章、流式行索引）、FB2、MOBI（基础兼容）、PDF（PdfRenderer）、CBZ 漫画。

**阅读**
- 4 种翻页：仿真卷曲（锥形斜卷、纸背镜像透印）、覆盖（跟手滑出）、平移、连续滚动
- 三角区点击 / 音量键 / 手势滑动翻页，翻页灵敏度可调
- 跨章连续阅读：章末桥接卡 + 相邻章预取，切章在主线程之外完成
- 长按选词、6 色高亮、笔记、书签、书内搜索、TTS 朗读
- 首行缩进、段落间距、字体 / 字号 / 行距 / 页边距，按书独立保存
- 6 套纸感主题 + 跟随系统深色 + Android 12 动态取色，阅读页与书库共用同一套配色

**书库**：SAF 多选导入、网格 / 列表视图、分组与收藏、排序、阅读进度、统计热力图与柱状图。

## 技术栈

Kotlin 2.x · Jetpack Compose（Material 3）· Room + KSP · DataStore · Coroutines/Flow · PdfRenderer

- 分页：把整章构建成 `StaticLayout`，按真实行高切成页切片，`Canvas` 上 `clipRect + translate` 绘制同一份 layout —— 所以翻页不错行、重排不丢位置。
- 标注：以字符偏移存储，重排后依然定位准确。
- 零网络：`AndroidManifest.xml` 中没有 `INTERNET` 权限，合并后的 Manifest 也确认为 0 个网络权限。

## 构建

```bash
./gradlew :app:assembleDebug     # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest # 解析引擎单测
```

需要 JDK 17+、Android SDK（compileSdk 37）、minSdk 26。

## 目录结构

```
app/src/main/java/com/booknest/reader/
├── core/        模型、排版规格、主题调色板
├── data/        Room 实体与 DAO、DataStore 设置、文件导入
├── format/      EPUB / TXT / FB2 / MOBI / CBZ 解析与文本源
├── reader/      分页器、TTS
├── state/       应用与阅读状态机
└── ui/          书库、阅读页、设置、统计、笔记、动效
```

## 已知限制

- 没有完整的 CSS 布局引擎，EPUB 只做语义级还原（`text-indent`、`float`、复杂 `@font-face`、竖排不支持）
- 插图按整块占位，不与文字环绕
- MOBI 仅基础版本，KF8/HUFF/DRM 不支持
- 仿真翻页是几何近似，不是纸张物理模拟

## 隐私

不申请任何网络权限，不采集数据，不含广告 SDK，不需要账号。书籍、进度、高亮、笔记、统计全部保存在应用私有目录，卸载即删除。
