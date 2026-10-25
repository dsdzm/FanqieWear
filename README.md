# 番茄阅读 · 手表版（FanqieWear）

一个跑在 **Wear OS / Android 8+ 手表**上的免费小说阅读器，纯 Jetpack Compose + Wear Compose 实现。
书荒广场支持 **番茄 / 七猫 双源**，含排行榜、搜索、书架、阅读历史、TTS 听书。
v1.4 在 1.3 的基础上补了 **番茄账号登录（Cookie）**、**应用内安装语音引擎**，并修了切章不回顶的问题。
1.3 带来 **离线下载**、**滚动/翻页两种阅读方式**、**字色与主题色**、**TTS 引擎安装引导**，
界面按 **Wear OS 原生交互** 实现：中心突出缩放列表、表冠吸附滚动、边缘右滑返回、震动反馈。
字号不再写死，会随屏宽（168dp 基准）自动放大，另有 4 档「界面缩放」和 5 档「安全区」可调。

> 数据来自番茄小说、七猫免费小说的公开接口，非官方 App，仅供学习研究。请支持正版、尊重作者版权。

## 一、先说接口：实测结论

开发前把 GitHub 上的方案（`mcp-server-fanqie`、`fanqie-api`、`fanqie-dl`、`PyFQWeb`）和真实接口都跑了一遍，
结论如下（2026-10 实测）：

| 能力 | 接口 | 是否需要签名 | 返回编码 |
|---|---|---|---|
| 搜索 | `novel.snssdk.com/api/novel/channel/homepage/search/search/v1/` | 否 | 明文 |
| 书籍详情 | `fanqienovel.com/page/{book_id}` HTML 内 `__INITIAL_STATE__.page` | 否 | 明文 |
| 目录 | `fanqienovel.com/api/reader/directory/detail?bookId=` | 否 | 明文 |
| 章节正文 | `fanqienovel.com/reader/{item_id}` HTML 内 `__INITIAL_STATE__.reader.chapterData` | 否 | **字体加密** |
| 分类榜单 | `fanqienovel.com/api/rank/category/list` | 否 | **字体加密** |
| 推荐流 | `fanqienovel.com/api/rank/recommend/list` | 否 | 明文 |
| 最近更新 | `fanqienovel.com/api/rank/recent/update/list` | 否 | 明文 |
| 分类列表 | `fanqienovel.com/api/author/book/category_list/v0/?gender=` | 否 | 明文 |
| 书评 | `reading.snssdk.com/novel/commentapi/comment/list/{id}/v1` | **是（X-Gorgon/X-Argus/X-Ladon）** | 明文 |
| 书荒广场 | 网页端不存在对应入口 | — | — |

两个关键发现：

1. **字体反爬可以离线还原。** 番茄把常用字替换成私有区字符（U+E000–U+F8FF），用自定义 woff2 渲染。
   本项目通过字形比对（Pillow 渲染 + Noto Sans CJK 匹配 + 匈牙利算法保证一一对应）还原了 362 个码点，
   生成 `app/src/main/assets/fontmap.json` 内置到 App 里，**运行时零请求、纯本地解码**。
   实测：榜单书名/作者/简介、章节正文解码后 0 个残留私有区字符，文本完全通顺。
2. **不同页面用不同字体。** 榜单页字体为 `dc027189e0ba4cd.woff2`（映射稳定，两次请求一致），
   但「书库」页用的是另一套 `e26e946d8b2ccb7.woff2`，映射不同。因此本项目**没有使用书库接口**，
   找书改用「分类榜单 + 推荐流 + 搜索」，避免引入第二套字体映射带来的维护成本。

> 书荒广场是 App 内的社区功能，网页端没有入口。本项目用「官方推荐流 + 最近更新 + 换一批」
> 复刻「书荒了给我来点书」的体验，见 `ExploreScreen.kt`。

## 一之二、七猫免费小说：实测结论

七猫网页端被阿里云 WAF 拦死（滑块验证），只能走 App 端接口。签名算法已跑通：

```kotlin
// 参数按 key 排序拼 "k=v&k=v"，末尾拼固定密钥做 MD5
sign = md5(params.sorted().joinToString("") { "${it.key}=${it.value}" } + "d3dGiJc651gSQ8w1")
// 请求头还要单独签一次（AUTHORIZATION/app-version/application-id/...）
```

| 能力 | 接口 | 状态 |
|---|---|---|
| 搜索 | `api-bc.wtzw.com/search/v1/words` | ✅ 可用 |
| 详情 | `api-bc.wtzw.com/api/v1/reader/detail?id=` | ✅ 可用 |
| 目录 | `api-ks.wtzw.com/api/v1/chapter/chapter-list?id=` | ✅ 可用 |
| 正文 | `api-ks.wtzw.com/api/v1/chapter/content` | ❌ 一直返回「参数错误」 |

正文接口需要 App 端才会生成的字段（试过 `id / book_id / chapter_id / chapter_ver / content_md5`
等十余种组合均失败），因此**七猫源目前只支持浏览详情和目录，正文请切回番茄源**。
App 内对这条路径有明确提示，不会静默失败。

## 二、界面与 Wear OS 交互

### 设计语言（自建，不套模板）

- 底色：墨蓝到深青的竖向渐变（`Palette`，纯黑 / 纸白两套备选）
- 卡片：竖向渐变 + 1dp 高光描边的玻璃质感，左侧一枚圆形图标位（16% 同色底），右侧箭头/数值
- 强调色：赤陶 `#E96A4F`，副色青瓷 `#5FB3A1`；首页是「继续阅读」大卡 + 分组胶囊列表，不再用方格磁贴
- 所有页面统一 `ScreenScaffold` + 右侧滚动指示器 + 顶部弧形时间

### 列表行为（和系统 Wear 应用一致）

| 行为 | 实现 |
| --- | --- |
| 中心突出 | `ScalingLazyColumn`，滚到屏幕中央的卡片放大、靠近上下边缘缩小 |
| 松手吸附 | `ScalingLazyColumnDefaults.snapFlingBehavior`，不会卡在两卡片之间 |
| 表冠滚动 | `RotaryScrollableDefaults.snapBehavior`，逐条滚动 + 系统旋转手感 |
| 震动反馈 | 中央条目变化、点击卡片时 `HapticFeedbackType`，可在设置关闭 |
| 边缘返回 | 左边缘 28dp 内右滑，超过屏宽 28% 触发返回（`Modifier.edgeSwipeBack`） |
| 滚动指示 | `ScrollIndicator(state)` 贴右侧显示当前位置 |

### 阅读页手势

- 表冠上下旋转：上一章 / 下一章（可关）
- 左右滑动：上一章 / 下一章
- 双击正文：打开目录
- 读到页尾自动接下一章（可关），可预加载下一章正文

## 二之零、番茄登录接口逆向结论（实测）

登录不是番茄自己的页面，而是字节 **Passport（muye SDK）**，弹窗由 `muye_*.js` 注入，相关接口都在
`https://fanqienovel.com` 同域下：

| 用途 | 路径 | 说明 |
| --- | --- | --- |
| 账号密码登录 | `POST /account_login/`、`/account_login/v2/` | 需 `aid`、密码加密串与签名 |
| 短信验证码 | `/send_activation_code/v2/` → `/quick_login/v2/` | 发码 + 登录，带滑块/图形验证码 |
| 扫码登录 | `GET /passport/web/get_qrcode/` → `GET /passport/web/check_qrconnect/?token=` | 轮询状态：未扫/已扫/已确认 |
| 登录态检查 | `GET /passport/account/info/v2/` | 返回用户信息，同时下发 Cookie |
| 退出 | `/logout/`、`/passport/web/logout/` | |

实测结果：

- 直接 `GET /passport/web/get_qrcode/?next=...` → `{"error_code":22,"description":"非法应用"}`
- 带 `aid=1967` → `{"error_code":16,"description":"该应用无权限"}`（aid 不对或缺少签名）
- `GET /api/reader/full?itemId=...`（页面自己用的正文接口）→ 200 但空 body；POST JSON → 403
- 首页加载了 `secsdk-lastest.umd.js`、`rc-client-security/captcha`、`byted_acrawler`，
  请求头 `X-Argus / X-Gorgon / X-Bogus` 由这些脚本在运行时生成，与 aid、URL、时间戳、ttwid 绑定

**结论**：在手表客户端里复刻这套签名既不可维护，也等于绕过平台风控，所以没有这么做。
App 走两条合规路径：

1. **网页登录（推荐）**：内置一个 WebView 打开官方登录页，用户自己登录，完成后从 `CookieManager`
   取出 `fanqienovel.com` 的 Cookie 存进设置（没装 WebView 会自动降级）
2. **粘贴 Cookie**：手机浏览器登录后复制 Cookie，粘到手表的输入框

## 二之一、为什么第 11 章起正文会断（登录 Cookie）

番茄网页端未登录只给每本书前 10 章左右的试读，第 11 章起 `chapterData.content` 只剩约 200 字，
`isChapterLock=1`。这不是解析 bug，已实测多个接口（网页、`/api/reader/full`、移动端
`reading/reader/full`）都拿不到完整正文，必须带登录态。

App 的做法：

- 设置 → 网络与书源 → **番茄账号**：粘贴浏览器里的整段 Cookie（`sessionid=...; sid_tt=...`），
  之后所有 `fanqienovel.com` 请求都会带上它，缓存键也会跟着变
- 阅读页遇到锁章会显示「未登录，本章只有试读 / 去登录」，登录后返回自动重新拉取本章
- Cookie 只保存在本机 SharedPreferences，不上传

获取方式：手机上用支持查看 Cookie 的浏览器打开 fanqienovel.com 登录 → 复制 Cookie → 同步到手表粘贴。

## 二之二、离线下载

书籍详情页 →「下载全本」或「前 50 章」，整本缓存到手表本地（`filesDir/downloads/<bookId>/`），
阅读时优先读本地文件，断网也能看；下载在应用级协程里跑，切页面不中断，可在「离线下载」页看进度、取消、删除。

## 三、安全区与小屏适配（重点）

320×320 在 2x 密度下只有 **160dp 宽**，圆屏两侧还会被弧线切掉，所以边距不写死：

- `rememberDeviceDims()` 按 `屏幕宽度比例 × 安全区档位` 算出行内边距，圆屏基准 7.8%、方屏 5.8%，窄屏（≤190dp）再降字号与卡片高度
- **安全区 5 档**：自动 / 紧凑 / 标准 / 宽松 / 最宽，设置页带形状预览（圆屏画圆、方屏画圆角矩形，内框就是内容边界），改完立即全局生效
- **界面缩放 3 档**：字号、卡片高度、图标位一起等比放大，避免只放大文字把布局挤爆
- 首页/设置用全宽胶囊卡（高 52dp 起），最小触控区 ≥48dp；筛选胶囊用 `FlowRow` 换行，不做横向滚动
- 主题三套：墨夜（默认）/ 纯黑（OLED 省电）/ 纸白（白天）

### 设置清单（8 组）

| 分组 | 可调项 |
| --- | --- |
| 阅读外观 | 字号（4 档）、行距（4 档）、段间距、6 种背景、6 种字色、两端对齐、屏幕常亮 |
| 阅读操作 | 阅读方式（滚动/翻页）、翻页动画、表冠翻章、表冠速度、自动下一章、预加载下一章、启动后续读、底部进度 |
| 界面与安全区 | 主题、主题色（5 套）、界面缩放（4 档）、安全区（含预览）、列表显示封面 |
| 语音听书 | 语速、音调、试听 |
| 下载与存储 | 缓存统计与清理 |
| 网络与书源 | 默认书源、接口状态 |
| 交互 | 震动反馈、边缘滑动返回 |
| 关于与支持 | 版本、免责声明、恢复默认 |

全部写入 `SharedPreferences`，通过 `StateFlow` 驱动 UI，改完即生效。

## 四、构建

```bash
export ANDROID_HOME=/path/to/android-sdk
gradle :app:assembleRelease         # 产物：app/build/outputs/apk/release/app-release.apk
```

- minSdk 26（Android 8.0）｜targetSdk 34｜compileSdk 35｜versionCode 6 / versionName 1.4.1
- Kotlin 2.0.21｜AGP 8.7.3｜Gradle 8.9｜JDK 17+
- 依赖：Wear Compose Material3 1.5.6、Wear Compose Foundation 1.5.6（`ScalingLazyColumn` / `rotary`）、Compose BOM 2024.12.01、OkHttp、kotlinx-serialization、Coil

安装到手表：

```bash
adb install -r app-debug.apk
```

## 四之二、TTS 引擎

手表 ROM 常常删掉语音引擎，App 不能内置完整的 TTS 引擎，所以设置页「语音听书」提供了安装渠道：

- 显示当前引擎名、是否就绪、缺中文语音包时的原因
- **应用内安装（主路径，不需要商店/WebView）**：一键下载 eSpeak NG 的 APK（约 10MB，开源 GPLv3），
  用 `PackageInstaller` 会话写入并交给系统确认；没有「安装未知应用」权限时跳本应用的授权页
- 「商店安装」→ 跳 Play 商店（Google 文字转语音），作为备用
- 「系统语音设置」→ 跳系统 TTS 设置下载离线语音包
- 列出常见引擎（Google / 讯飞 / 百度）并标注是否已安装，点一下直接去装
- 装完点「重新检测」，`TtsPlayer.refresh()` 会重新初始化引擎

阅读页在引擎不可用时也会显示一条「点此安装」的提示条。

## 五、已知限制

1. **正文只覆盖「网页可见」章节**：需要登录/付费的章节在网页端返回 `isChapterLock`，App 会提示需授权，不会绕过。
2. **字体映射会过期**：番茄若更换字体文件，书名会出现乱码。届时需要重新生成 `fontmap.json`（工具脚本见下）。
3. **听书音色取决于手表**：使用系统 TTS，手表没装中文语音包时会提示去系统设置安装。
4. **无登录/同步**：书架和进度存在本地，不涉及账号体系。
5. 接口非官方，随时可能变更；请控制请求频率。

## 六、字体映射的重新生成

`tools/gen_fontmap.py` 是离线脚本：下载当前榜单页字体 → 转 otf → 与 Noto Sans CJK 逐字比对 →
最优一一分配 → 输出 `fontmap.json`。字体换了重跑一次即可。

## 七、免责声明

本项目未使用番茄小说的任何私有 SDK 或 App 签名，仅解析网页端公开内容；不提供下载、不破解付费章节。
所有文字版权归原作者与平台所有。若相关方认为不妥，请删除本项目。
