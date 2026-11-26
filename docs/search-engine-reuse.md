# 可复用搜索引擎（北冥云盘 → 北冥官网）

> 这是给**未来的自己**看的交接单。做北冥官网时如果需要一个站内搜索，
> 直接把这套东西搬过去，不要重新选型、不要重新踩坑。

## 一句话

嵌入式 Lucene + 自写中英混合分词（单字+双字）+ pinyin4j 三形态拼音，
**不起独立服务、不占端口**，整个 `search` 包与业务零耦合，可整包复制。

## 代码在哪

```
C:\Users\15780\dev\beiming-drive\backend\src\main\java\games\beiming\drive\search\
├── MixedCjkAnalyzer.java   # 分词器（单字+双字，中英混合）
├── Pinyin.java             # 汉字→拼音三形态（全拼/首字母/逐字）
├── SearchDoc.java          # 通用文档模型（id/title/scope/extra/payload）
├── SearchIndex.java        # 索引读写 + 查询加权 + 拼音/容错
├── SearchConfig.java       # ← 唯一的 Spring 装配，搬走时按项目改
└── Json.java               # payload 序列化，保持包自洽
```

业务侧对照（搬去官网时**不要**照抄这层，要按官网的数据结构重写）：

```
games.beiming.drive.fs.FsSearchSync      # Node → SearchDoc 的翻译
games.beiming.drive.fs.SearchBootstrap   # 启动时全量对齐
```

测试：`backend/src/test/java/games/beiming/drive/search/SearchIndexTest.java`（12 条，是能力清单）

## 能力（都被测试锁住了）

| 输入 | 结果 | 实现方式 |
|---|---|---|
| `整合` | 命中「北冥整合包-v2.4.zip」 | 分词单字+双字，`TITLE` 字段 |
| `beiming` | 命中「北冥整合包」「关于北冥」 | `py_full` 前缀 + 包含两路 |
| `bmzhb` | 命中「北冥整合包」 | `py_initials` |
| `gybm` | 命中「关于北冥」 | `py_initials` |
| `v2.4` / `pdf` | 命中对应文件 | 字母数字整段成词 |
| `文挡`（错一字） | 仍命中「文档库」 | 对**分词后 token** 做 FuzzyQuery |
| 不同用户 | 互相搜不到 | `scope` 字段 FILTER |
| `文档` 精确 | 排「文档库备份」前面 | BoostQuery 分级加权 |

## 三个必须知道的坑（都踩过了）

1. **不要用 `CJKBigramFilter`**：它只在成对汉字上出双字，单字不出，导致搜单个字（「包」）搜不到。已自写 `GramTokenizer`，单字和双字都出。

2. **`CharTermAttribute.append(cs, start, end)` 第三参是「结束位置」不是长度**。传长度会直接 `IndexOutOfBoundsException: Range [2, 1)`。

3. **偏移量必须单调不减**。不能「先把一段的单字全吐完再吐双字」——双字起点会回到段首，Lucene 抛
   `offsets must not go backwards`。正确做法：同一位置先出单字、再出双字，位置递增着交替出。

4. **Fuzzy 不能对长串整串做**：Lucene 编辑距离上限 2，长文件名永远不命中。必须按 token 做。

## 搬去官网时的步骤

1. 复制整个 `search/` 包，改包名
2. 写一个 `XxxSearchSync`，把官网的数据（文章/页面/文档）映射成 `SearchDoc`
3. 官网是单租户 → `scope` 传 `null`，`search(q, limit)` 重载直接用
4. `SearchConfig` 里的索引目录指到合适位置
5. 起一个 `ApplicationRunner` 做首次全量灌入（照抄 `SearchBootstrap`）
6. 把 `SearchIndexTest` 一起搬过去，改掉测试数据即可验证能力没退化

## 索引持久化

- 路径：`${DRIVE_SEARCH_INDEX:/data/search-index}`
- compose 里挂具名卷 `searchidx`（**必须挂**，否则重建容器丢索引）
- 启动时 `SearchBootstrap` 会比对库里的节点数，不一致就全量重建 —— 所以索引丢了不会导致搜索永久失效