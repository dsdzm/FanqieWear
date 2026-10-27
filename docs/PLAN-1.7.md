# 1.7 规划：登录器 / 聚合 / 性能 / 本地书 / 在线 TTS

> 目标：把"只能看笔趣阁"扩展成"多源聚合 + 本地书 + 可换语音"，并解决手表端登录和流畅度。
> 本文只定方案与顺序，实施时按阶段推进，每阶段独立可发版。

## 一、总体架构变化

```
                 ┌─────────────────────────────┐
                 │        SourceRegistry        │
                 │  按 key 取 BookSource        │
                 └───────┬─────────┬───────────┘
                         │         │
        ┌────────────────┘         └───────────────┐
        │                                          │
┌───────▼────────┐   ┌──────────────────┐   ┌──────▼────────┐
│ DeclarativeSrc │   │ AggregateSource  │   │  LocalSource  │
│ （JSON 规则）  │   │ （多源并行合并） │   │ （导入的 txt）│
└───────┬────────┘   └────────┬─────────┘   └──────┬────────┘
        │                     │                    │
   站点 A/B/C…           调用上面两类源          filesDir/books
```

现有 `BookSource` 接口保持不变（search/browse/detail/catalog/chapter/ping），
新增的三种实现都落在同一接口下，阅读器、书架、下载器零改动。

---

## 二、手机登录器（把番茄 Cookie 从手机送到手表）

### 难点先说清
番茄的 `sessionid` 是 **HttpOnly**，网页 JS 读不到。所以"手机上打开网页自动抓 Cookie"这条路走不通，
只有两种方式能拿到完整 Cookie：
1. 手机端**我们自己的 App** 里用 WebView 登录 —— `CookieManager.getCookie(url)` 能拿到 HttpOnly 项；
2. 手动从浏览器开发者工具/网络面板复制整条请求头。

### 方案 A：局域网粘贴页（零额外安装，先做）
- 手表端 `PhoneLoginServer`：`java.net.ServerSocket` 起一个极简 HTTP 服务（端口 8765，仅 WiFi 内网可达），
  不做任何外网暴露；页面由手表直接吐 HTML。
- 手表在「设置 → 番茄账号」显示一个二维码（ZXing core，约 500KB），内容 `http://<手表IP>:8765`。
- 手机扫码打开页面：一个输入框 + 「保存到手表」按钮，粘贴 Cookie 后 POST 回来；
  页面同时给一条说明链接，教用户怎么在手机浏览器里复制整条 Cookie。
- 手表收到后立刻走现有的 `FanqieApi.lookLoggedIn` + `eleventhChapterId` 探测，通过才算成功。

### 方案 B：手机伴侣 APK（体验最好，后做）
- 同仓库新增 `:phone-login` module，一个几百行的 Activity：WebView 打开 `fanqienovel.com`，
  用户正常登录，点「发送到手表」→ 用 `CookieManager.getCookie()` 取全量 → UDP 广播 + 局域网 POST 到手表。
- 手表端同一套 `PhoneLoginServer` 接收，页面 A 和 App B 共用接口。
- 手表上显示 4 位配对码，防止同网段误投。

### 交付节奏
- A 先上（1.7.2），B 作为可选增强（1.7.3）。

---

## 三、多站点聚合

### 3.1 声明式书源（关键设计）
新增 `assets/sources/*.json`，一个站点一个文件，不需要改代码：

```json
{
  "key": "biquge365",
  "name": "笔趣阁",
  "baseUrl": "https://www.biquge365.net",
  "charset": "utf-8",
  "search":  { "method": "POST", "path": "/s.php", "body": "type=articlename&s={q}", "list": "div.result-item", "title": "h3 a", "link": "h3 a@href", "author": "p.author" },
  "detail":  { "path": "/book/{id}/", "title": "h1", "author": ".xinxi p:eq(0)", "catalog": "a.full-catalog@href" },
  "catalog": { "path": "/newbook/{id}/", "list": "ul.info li a", "title": "", "link": "@href" },
  "chapter": { "path": "/chapter/{bid}/{cid}.html", "content": "div#txt", "remove": ["script", ".ads"] }
}
```

- 解析用 **jsoup**（约 400KB），比正则稳；`@attr` 表示取属性，`:eq(n)` 取第 n 个。
- `remove` 是正文清洗列表；`replace` 支持正则替换（去广告行、去"本章未完"）。
- 新增 `DeclarativeSource`：读 JSON → 拼 URL → jsoup 选择 → 统一映射到现有 `BookSource` 模型。
- 站点改版只改 JSON，可以走"云端书源"（放资料库，App 拉取更新），不用发版。

### 3.2 聚合搜索
`AggregateSource` 实现 `search()`：并发（协程 `async`）查询所有可用源，超时 6s，
合并去重（书名归一化 + 作者），结果标注来源；点进去用命中源做 detail/catalog/chapter。
UI 上加「换源」：同一本书在其他源里的结果列表，一键切，保留阅读进度（按章节标题对齐）。

### 3.3 首批内置源
按"能不能稳定抓"排序，逐个实测后再进 JSON：
- 笔趣阁系（biquge365 / biquge365.net 镜像 / 新笔趣阁）
- 顶点小说、得奇小说、69书吧、书迷楼
- 七猫（目录可用，正文需签名，标为"仅目录"）
- 番茄（登录后）

> 每个源进库前必须跑一次 `ping` 实测（搜索/目录/正文三条链路），不过的不进。

---

## 四、本地导入小说（自动分章节）

- **选择文件**：SAF `ActivityResultContracts.OpenDocument`，mime `text/plain`，也允许 `*/*` 里按扩展名过滤。
- **编码识别**：BOM → UTF-8 严格解码（`CharsetDecoder` REPORT）失败则按 GB18030 重试（覆盖 GBK/GB2312）。
- **分章**：
  - 主规则：`^\s*(第[0-9零一二三四五六七八九十百千万两]{1,10}[章节回卷][^\n]{0,30})$`（多行模式）
  - 兼容：`Chapter\s+\d+`、`楔子|序章|前言|后记|尾声`、纯数字标题行
  - 兜底：识别不到就按 3000 字切片，标题"第 N 节"
- **存储**：`filesDir/books/<bookId>/source.txt` + `chapters.json`（每章的字节偏移与长度），
  阅读时按偏移随机读，不整本进内存；`bookId` 用书名+文件大小+CRC32 前 8 位，重复导入直接复用。
- **书源**：`LocalSource` 实现 `BookSource`，书架里与网络书完全一致，可离线读、可听书。
- **UI**：书架页顶部「导入本地书」，导入时显示解析进度（章节数、字数），失败给具体原因。

---

## 五、在线 TTS（免费）

### 选型
用 **Edge Read Aloud**（微软 Edge 浏览器朗读）的公开接口：
- 免费、不要 key、中文音色多（`zh-CN-XiaoxiaoNeural`、`YunxiNeural`、`XiaoyiNeural` 等），质量接近真人；
- WebSocket 流式返回 MP3，首包延迟低；
- 风险：非官方接口，可能变更 → 做成可切换的"在线引擎"，失败自动回退系统语音/eSpeak。

### 实现
- `EdgeTtsEngine`：OkHttp WebSocket 连 `wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1?TrustedClientToken=...`，
  发 `speech.config` + SSML，收 `audio.metadata`/二进制帧。
- **播放**：MP3 用 `MediaCodec` 解成 PCM 喂现有 AudioTrack 通路（复用 1.6.x 的续播/暂停逻辑）；
  解码复杂就退一步用 `MediaPlayer` 单块播放。
- **分块**：沿用 220 字一块，合成一块播一块；**边下边播**，下一块在播放时预取。
- **缓存**：`cacheDir/tts/<hash>.mp3`，同一章重听不再请求。
- **设置**：朗读引擎增加「在线（Edge）」，下面选音色；首次使用提示"需要联网，流量约 40KB/百字"。

---

## 六、性能优化

先量后优，避免拍脑袋：

1. **内置性能面板**（设置 → 关于 → 长按版本号）：FPS、帧耗时 P90、内存、当前页重组次数、网络耗时。
2. **Compose**
   - 数据类加 `@Immutable`，`State` 拆细（阅读页不要整页订阅 tts 全量状态）；
   - 列表 `items(key = { it.id })`，避免整表重建；
   - 阅读页 `derivedStateOf` 包裹分页计算，字号不变不重算。
3. **图片**：Coil 配内存/磁盘缓存上限，列表滑动时降采样。
4. **网络**：OkHttp 连接池复用、`Cache` 磁盘缓存、目录/正文 ETag 条件请求。
5. **存储**：`ShelfStore` 批量写、加索引；下载器改成分块写 + 断点续传。
6. **启动**：`Application` 里 TTS 预热、字体解码改懒加载；首屏只初始化必需项。

---

## 七、实施顺序与版本

| 版本 | 内容 | 理由 |
|---|---|---|
| **1.7.0** | 本地导入（四）+ 在线 TTS（五） | 互相独立、见效最快，先解决"看什么/怎么听" |
| **1.7.1** | 声明式书源 + 聚合搜索 + 换源（二） | 架构活，做完后加源只写 JSON |
| **1.7.2** | 手机登录器 A 方案（一） | 依赖聚合里的番茄源，排在后面更合理 |
| **1.7.3** | 手机伴侣 APK（一·B）+ 云书源下发 | 体验增强 |
| **1.8.0** | 性能专项（三） | 需要真机数据，贯穿全程收集指标 |

每个版本仍然出两个签名包（官方 / testkey），流程不变。

## 八、风险与对策

| 风险 | 对策 |
|---|---|
| 第三方站点改版 | 书源 JSON 化 + 云端下发，不发版也能修 |
| Edge TTS 接口变更 | 多音色多端点探测，失败自动回退本地引擎 |
| 手表 CPU 弱，在线 TTS 解码卡 | 分块 + 预取，先播后缓冲；实在不行回退系统语音 |
| 局域网登录器被同网段滥用 | 仅监听内网、4 位配对码、30 秒无操作自动关闭 |
| 本地 txt 编码混乱 | BOM → UTF-8 严格 → GB18030 三级回退，失败提示另存为 UTF-8 |
