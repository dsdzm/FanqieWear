# 番茄小说接口实测记录

> 测试时间：2026-10-25　测试环境：Ubuntu 22.04 / 上海出口 IP / Node 22 / Python 3.11
> 目的：确认社区方案哪些还能用、哪些已失效，为手表阅读器选数据源。

## 一、被测项目

| 项目 | 结论 |
|---|---|
| [fysh1010/mcp-server-fanqie](https://github.com/fysh1010/mcp-server-fanqie) | **可用**。搜索/详情/目录/正文/书评全部返回真实数据，自带 App 端签名实现 |
| [h13637404183-hub/fanqie-api](https://github.com/h13637404183-hub/fanqie-api) | 部分可用。榜单接口仍在，但搜索接口未实现签名，调不通 |
| [linzj/fanqie-dl](https://github.com/linzj/fanqie-dl) | 签名算法文档有价值，但项目 WIP，缺 sign key，无法直接跑通 |
| [MeoProject/PyFQWeb](https://github.com/MeoProject/PyFQWeb) | 已停更，仓库仅剩框架 |

## 二、接口逐项验证

### 1. 搜索（可用，明文）

```
GET https://novel.snssdk.com/api/novel/channel/homepage/search/search/v1/?aid=1967&q=十日终焉&offset=0
```

返回 `data.ret_data[]`，字段：`book_id / title / author / abstract / thumb_url / category / creation_status / score`。
**不需要签名**，中文正常。

### 2. 书籍详情（可用，明文）

```
GET https://fanqienovel.com/page/{book_id}
```

HTML 里 `window.__INITIAL_STATE__.page` 是完整 JSON：

```json
{"bookId":"7143038691944959011","bookName":"十日终焉","authorName":"杀虫队队员",
 "wordNumber":"3201288","chapterTotal":1496,"creationStatus":"1",
 "lastChapterTitle":"陈俊南（终）","thumbUrl":"https://..."}
```

注意 `bookName` 这里是**明文**，与榜单接口不同。

### 3. 目录（可用，明文）

```
GET https://fanqienovel.com/api/reader/directory/detail?bookId=7143038691944959011
```

`data.chapterListWithVolume` 按卷分组，`data.volumeNameList` 是卷名。
实测《十日终焉》返回 1496 章、10 卷，全部明文。

### 4. 章节正文（可用，**字体加密**）

```
GET https://fanqienovel.com/reader/{item_id}
```

`window.__INITIAL_STATE__.reader.chapterData` 里有 `content`（HTML）、`isChapterLock`、`needPay`、
`preItemId`、`nextItemId`。`content` 里的常用字被替换成私有区字符，需要字体映射还原。

实测第 1 章解码后 **0 个残留私有区字符**，文本通顺：

> 一个老旧的钨丝灯被黑色的电线悬在屋子中央，闪烁着昏暗的光芒。静谧的气氛犹如墨汁滴入清水，正在房间内晕染蔓延……

### 5. 分类榜单（可用，**字体加密**）

```
GET https://fanqienovel.com/api/rank/category/list?app_id=2503&rank_list_type=3
    &offset=0&limit=15&category_id=258&rank_version=&gender=1&rankMold=1
```

- `gender=1` → 男频，`gender=0` → 女频（**注意与直觉相反**）
- `rankMold=1` 阅读榜，`2` 新书榜
- 返回 `data.book_list[]`，`total_num` 上限 100
- `bookName / author / abstract / wordNumber / read_count` 均为私有区字符

解码样例（《大圣哥哥，别躲着奴家哟》那批）：

```
大圣哥哥，别躲着奴家哟 | 睡觉大哥嘿 | 297502
港影：反派的强制爱甚是美味 | 割大腿自渡 | 269301
在甄嬛传里发疯的鬼 | 涓涓爱划水 | 185769
蜗居：海藻重生遇到离婚的宋思明 | 钱百万豪 | 228525
```

### 6. 推荐流 / 最近更新（可用，明文）

```
GET https://fanqienovel.com/api/rank/recommend/list?type=2&limit=12&offset=0   # 2=男频 3=女频
GET https://fanqienovel.com/api/rank/recent/update/list?limit=12&offset=0
```

这两个接口不加密，可以直接当推书信息流用。

### 7. 分类列表（可用，明文）

```
GET https://fanqienovel.com/api/author/book/category_list/v0/?gender=1
```

返回 154 个男频分类 / 182 个女频分类，含 `category_id`。

### 8. 书评（可用，需签名）

```
GET https://reading.snssdk.com/novel/commentapi/comment/list/{book_id}/v1
```

需要 `X-Gorgon / X-Argus / X-Ladon` 等请求头，`mcp-server-fanqie` 里已实现，实测能取到正文、
评分、点赞数，明文中。

### 9. 书荒广场（**网页端不存在**）

`/community`、`/booklist` 均 404；首页导航只有「首页 / 书库 / 书架 / 原创榜 / 作家专区 / 版权专区」。
书荒广场是 App 内的社区模块，没有对应的 Web 入口，需要 App 端签名接口才能拿，成本与稳定性都不划算。

**方案**：用「推荐流 + 最近更新 + 换一批」复刻找书体验。

## 三、字体反爬的处理

番茄的加密方式：

1. 服务端把命中的常用字换成 `U+E000–U+F8FF` 私有区码点
2. 页面注入 `@font-face`，把每个码点映射到一个真实汉字的字形
3. 浏览器渲染出来是正常文字，直接取 JSON 就是乱码

本项目做法：

1. 下载字体（榜单页是 `awesome-font/c/dc027189e0ba4cd.woff2`，47KB，362 个字形）
2. woff2 → otf，用 Pillow 把每个私有区码点渲染成 64×64 位图
3. 用 Noto Sans CJK 渲染 GB2312 全部汉字 + ASCII + 标点，同尺寸归一化
4. 余弦相似度矩阵 + 匈牙利算法做**全局一一分配**（避免"特/将"这类字形相近字互相抢位）
5. 相似度 < 0.9 的 27 个条目人工校对
6. 输出 362 条映射，内置进 App

验证：平均相似度 0.968，解码后文本通顺，正文 0 残留。

**注意**：不同页面用不同字体子集。`/library` 页用的是 `e26e946d8b2ccb7.woff2`，映射与榜单不同，
所以书库接口没有采用。榜单字体两次请求返回一致（映射稳定），但平台换字体后需要重新生成。

## 四、对 App 的影响

- 采用：搜索、详情、目录、正文、分类榜单、分类列表、推荐流、最近更新
- 放弃：书库（第二套字体）、书评（需要签名，手表端不值得内置 JSSE 级签名逻辑）、书荒广场（无 Web 接口）
- 听书走系统 TTS，不碰番茄的音频接口（那些接口签名字段更多，且音源有防盗链）
