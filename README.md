# 书阁 · 手表版（FanqieWear）

一个跑在 **Wear OS / Android 8+ 手表**上的免费小说阅读器，纯 Jetpack Compose + Wear Compose 实现。
含**分类书库、搜索、书架、阅读历史、离线下载、TTS 听书**，支持**多书源自由切换**。

> 当前默认书源是**笔趣阁**（免登录、正文完整）。番茄源的登录链路走字节 Passport，
> 手表端拿不到完整正文，暂时降级为备用源——代码保留着，登录修好后在设置里切回去即可。

v1.5 主要变化：

- 抽出统一书源层 `BookSource` + `SourceRegistry`，页面不再直接依赖某个站点
- 新增免登录书源**笔趣阁**，搜索 / 分类 / 详情 / 目录 / 正文全通
- 设置 → 网络与书源：**选中即切换**，每个源标注能力与限制，支持**一键连通性检测**
- 书架、阅读历史、继续阅读都会记住**这本书来自哪个源**，跨源不会串
- 番茄源降级为可选备用源，七猫源保留（只到目录）

## 一、书源一览

| 书源 | 需要登录 | 搜索 | 分类浏览 | 目录 | 正文 | 状态 |
|---|---|---|---|---|---|---|
| **笔趣阁** `biquge365.net` | 否 | ✅ | ✅ | ✅ | ✅ | **默认** |
| 七猫免费小说 | 否 | ✅ | ✅ | ✅ | ❌ 需 App 端签名 | 备用 |
| 番茄小说 | 是（Cookie） | ✅ | ✅ | ✅ | ⚠️ 未登录第 11 章起只有试读 | 备用 |

切换路径：**设置 → 网络与书源 → 切换书源**。选中项即时生效，首页副标题会显示当前源；
搜索页、书库页顶部也能**临时换源**（只影响当前页面，不改默认值）。

## 二、笔趣阁：实测结论

选它当默认源的原因很简单：**全链路免登录**，且页面结构稳定到可以用正则解析。

| 能力 | 路径 | 说明 |
|---|---|---|
| 搜索 | `POST /s.php`（`type=articlename&s=关键词`） | 服务端渲染 HTML |
| 详情 | `GET /book/{id}/` | `<h1>` 书名，`.xinxi` 里有作者/类型/状态/最新章节 |
| 目录 | `GET /newbook/{id}/` | `<ul class="info">` 是全量正序章节 |
| 正文 | `GET /chapter/{bid}/{cid}.html` | `<div id="txt">`，`<br>` 分段 |
| 分类 | `GET /sort/{分类}_{页码}/` | 页码从 1 开始，8 个分类 |

实现细节（`data/BiqugeApi.kt`）：

- 正文清洗：`<br>` 转段落，逐行丢掉站点插入的广告与推书行（"一秒记住"、"笔趣阁"、"最新网址"…）
- 上一章 / 下一章直接从页面导航区解析，不依赖目录下标，断章也不会错位
- 详情与目录缓存 2 分钟，请求间隔 200ms，避免给站点压力
- 章节 `itemId` 只取数字串（如 `13964879`），可以直接当离线文件的名字

## 三、番茄源（备用）：接口实测

开发时把 GitHub 上的方案（`mcp-server-fanqie`、`fanqie-api`、`fanqie-dl`、`PyFQWeb`）和真实接口都跑了一遍，
结论如下（2026-10 实测）：

| 能力 | 接口 | 是否需要签名 | 返回编码 |
|---|---|---|---|
| 搜索 | `novel.snssdk.com/api/novel/channel/homepage/search/search/v1/` | 否 | 明文 |
| 书籍详情 | `fanqienovel.com/page/{book_id}` HTML 内 `__INITIAL_STATE__.page` | 否 | 明文 |
| 目录 | `fanqienovel.com/api/reader/directory/detail?bookId=` | 否 | 明文 |
| 章节正文 | `fanqienovel.com/reader/{item_id}` HTML 内 `__INITIAL_STATE__.reader.chapterData` | 否 | **字体加密** |
| 推荐流 | `fanqienovel.com/api/rank/recommend/list` | 否 | 明文 |
| 分类榜单 | `fanqienovel.com/api/rank/category/list` | 否 | **字体加密** |

两个关键发现：

1. **字体反爬可以离线还原。** 番茄把常用字替换成私有区字符（U+E000–U+F8FF），用自定义 woff2 渲染。
   本项目通过字形比对（Pillow 渲染 + Noto Sans CJK 匹配 + 匈牙利算法保证一一对应）还原了 362 个码点，
   生成 `app/src/main/assets/fontmap.json` 内置到 App 里，**运行时零请求、纯本地解码**。
2. **不同页面用不同字体。** 榜单页字体映射稳定，书库页是另一套，所以番茄源也没用书库接口。

### 为什么番茄暂时不能用

番茄的登录不是自己的页面，而是字节 **Passport（muye SDK）**。实测：

- 直接 `GET /passport/web/get_qrcode/` → `{"error_code":22,"description":"非法应用"}`
- 带 `aid=1967` → `{"error_code":16,"description":"该应用无权限"}`
- 首页加载了 `secsdk-lastest.umd.js`、`byted_acrawler`，`X-Argus / X-Gorgon / X-Bogus`
  由脚本运行时生成，和 aid、URL、时间戳、ttwid 绑定

在手表客户端复刻这套签名既不可维护，也等于绕过平台风控，所以没有做。
**未登录只给每本书前 10 章左右试读**，第 11 章起 `chapterData.content` 只剩约 200 字、
`isChapterLock=1`，这不是解析 bug。

App 里保留了两条合规路径（在设置里把书源切到「番茄小说」后才会出现入口）：

1. **网页登录**：内置 WebView 打开官方登录页，用户自己登录，完成后从 `CookieManager` 取 Cookie
2. **粘贴 Cookie**：手机浏览器登录后复制整段 Cookie，粘到手表输入框

Cookie 只存本机 SharedPreferences，不上传。

## 四、七猫：只到目录

七猫网页端被阿里云 WAF 拦死（滑块验证），只能走 App 端接口。签名算法已跑通：

```kotlin
// 参数按 key 排序拼 "k=v&k=v"，末尾拼固定密钥做 MD5
sign = md5(params.sorted().joinToString("") { "${it.key}=${it.value}" } + "d3dGiJc651gSQ8w1")
```

搜索、详情、目录可用；正文接口 `chapter/content` 一直返回「参数错误」，
还需要 App 端才会生成的字段，所以这个源只支持浏览。UI 里会直接提示，不会静默失败。

## 五、代码结构（书源相关）

```
data/
  BookSource.kt      统一书源接口：search / browse / detail / catalog / chapter / ping
  Sources.kt         SourceRegistry 注册表 + 三个源的适配实现
  BiqugeApi.kt       笔趣阁：HTML 抓取与正则解析
  FanqieApi.kt       番茄：JSON + 字体解码（备用）
  QimaoApi.kt        七猫：签名请求（备用）
  SettingsStore.kt   sourceKey 等设置
  ShelfStore.kt      书架与进度，记录每本书的 source
```

加一个新源只需要：写一个 `BookSource` 实现 → 在 `SourceRegistry.all` 里注册一行。

## 六、界面与 Wear OS 交互

### 设计语言（自建，不套模板）

- 底色：墨蓝到深青的竖向渐变（`Palette`，纯黑 / 纸白两套备选）
- 卡片：竖向渐变 + 1dp 高光描边的玻璃质感，左侧一枚圆形图标位（16% 同色底）
- 强调色：赤陶 `#E96A4F`，副色青瓷 `#5FB3A1`
- 所有页面统一 `ScreenScaffold` + 右侧滚动指示器 + 顶部弧形时间

### 列表行为（和系统 Wear 应用一致）

| 行为 | 实现 |
| --- | --- |
| 中心突出 | `ScalingLazyColumn`，滚到屏幕中央的卡片放大、靠近上下边缘缩小 |
| 松手吸附 | `ScalingLazyColumnDefaults.snapFlingBehavior` |
| 表冠滚动 | `RotaryScrollableDefaults.snapBehavior`，逐条滚动 + 系统旋转手感 |
| 震动反馈 | 中央条目变化、点击卡片时 `HapticFeedbackType`，可在设置关闭 |
| 边缘返回 | 左边缘 28dp 内右滑，超过屏宽 28% 触发返回 |
| 滚动指示 | `ScrollIndicator(state)` 贴右侧显示当前位置 |

### 阅读页手势

- 表冠上下旋转：上一章 / 下一章（可关）
- 左右滑动：上一章 / 下一章
- 双击正文：打开目录
- 读到页尾自动接下一章（可关），可预加载下一章正文

## 七、安全区与小屏适配

320×320 在 2x 密度下只有 **160dp 宽**，圆屏两侧还会被弧线切掉，所以边距不写死：

- `rememberDeviceDims()` 按 `屏幕宽度比例 × 安全区档位` 算出行内边距
- **安全区 5 档**：自动 / 紧凑 / 标准 / 宽松 / 最宽，设置页带形状预览
- **界面缩放 4 档**：字号、卡片高度、图标位一起等比放大
- 首页/设置用全宽胶囊卡（高 52dp 起），最小触控区 ≥48dp

### 设置清单（8 组）

| 分组 | 可调项 |
| --- | --- |
| 阅读外观 | 字号（4 档）、行距（4 档）、段间距、6 种背景、6 种字色、两端对齐、屏幕常亮 |
| 阅读操作 | 阅读方式（滚动/翻页）、翻页动画、表冠翻章、表冠速度、自动下一章、预加载、启动续读 |
| 界面与安全区 | 主题、主题色（5 套）、界面缩放（4 档）、安全区（含预览）、列表封面 |
| 语音听书 | 语速、音调、试听 |
| 下载与存储 | 缓存统计与清理 |
| **网络与书源** | **书源切换、能力说明、连通性检测、番茄账号** |
| 交互 | 震动反馈、边缘滑动返回 |
| 关于与支持 | 版本、免责声明、恢复默认 |

全部写入 `SharedPreferences`，通过 `StateFlow` 驱动 UI，改完即生效。

## 八、构建

```bash
export ANDROID_HOME=/path/to/android-sdk
gradle :app:assembleRelease         # 产物：app/build/outputs/apk/release/app-release.apk
```

- minSdk 26（Android 8.0）｜targetSdk 34｜compileSdk 35｜versionCode 7 / versionName 1.5.0
- Kotlin 2.0.21｜AGP 8.7.3｜Gradle 8.9｜JDK 17+
- 依赖：Wear Compose Material3 1.5.6、Wear Compose Foundation 1.5.6、Compose BOM 2024.12.01、OkHttp、kotlinx-serialization、Coil

装到手表：

```bash
adb install -r app-debug.apk
```

## 九、离线下载

书籍详情页 →「下载全本」或「前 50 章」，整本缓存到手表本地（`filesDir/downloads/<bookId>/`），
阅读时优先读本地文件，断网也能看。下载元数据里记了**书源**，离线打开时按原源走。
下载在应用级协程里跑，切页面不中断，可在「下载与缓存」页看进度、取消、删除。

## 十、TTS 引擎

手表 ROM 常常删掉语音引擎，App 不能内置完整的 TTS 引擎，所以设置页「语音听书」提供了安装渠道：

- **应用内安装（主路径）**：一键下载 eSpeak NG 的 APK（约 10MB，开源 GPLv3），
  用 `PackageInstaller` 会话写入并交给系统确认
- 「商店安装」→ 跳 Play 商店（Google 文字转语音），作为备用
- 「系统语音设置」→ 跳系统 TTS 设置下载离线语音包

## 十一、已知限制

1. **笔趣阁是第三方站点**：接口随时可能改版，解析失败时请在设置里做一次「检测书源可用性」，
   或者换回其它源。
2. **番茄只覆盖网页可见章节**：未登录只有前 10 章左右，App 会提示，不绕过。
3. **七猫没有正文**：只能浏览详情与目录。
4. **字体映射会过期**（番茄源）：番茄更换字体文件后书名会乱码，需重跑 `tools/gen_fontmap.py`。
5. **书架按书号去重**：不同源的书号偶有重复，同时收藏两本同书号的会互相覆盖（极少见）。
6. **听书音色取决于手表**：用系统 TTS，没装中文语音包时会提示去系统设置安装。
7. 书架和进度只存在本地，不涉及账号体系。

## 十二、免责声明

本项目未使用番茄小说的任何私有 SDK 或 App 签名，仅解析公开网页内容；
不提供下载、不破解付费章节。所有文字版权归原作者与平台所有。
若相关方认为不妥，请删除本项目。
