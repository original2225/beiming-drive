# 北冥云盘 · 项目记忆（PROJECT_MEMORY）

> **交接用法**：开新会话第一句说「先读 README.md 和 PROJECT_MEMORY.md，只看最新一节，然后做 <具体下一步>，别动其他模块」。README 讲**怎么用/怎么部署**，本文件讲**做到哪、为什么这么做、接下来做什么**。
> 每完成一块完整功能就往下追加一节（格式见文末）。上下文到 10 万 token 左右就换会话。

## 一句话现状（更新于 2026-11-27）

自研网盘（不是 Cloudreve 换皮）主功能已闭环：登录、文件树、上传下载、sha256 分卷去重、在线预览、分享、回收站、专属多线程下载器、自研站内搜索、右侧竖栏 + 新建菜单。**最近在做前端体验打磨（字号/留白/过渡/竖栏常驻），后端功能稳定。** 最近 6 个提交都还没 push 到 origin。

## 收工状态（2026-11-27 21:20，花蝉说「网盘 drive 告一段落了」）

这一阶段到此为止，下一次开工从这里接：

- 分支 `main`，**ahead origin/main 6 个提交，全部未推**
- 工作区有两张截图改过没提交：`docs/screenshots/rail/70-rail-open-downloader.png`、`71-rail-open-shares.png`
- `PROJECT_MEMORY.md`（本文件）新建，尚未 commit
- 本机 8140 有 java 进程在跑（PID 8168 与 17876 各占一个监听）——**改代码重新打包前必须先停它**，否则 mvn 报 Unable to rename
- 没有任何半成品功能挂着；前端体验打磨这块可以视为已完成，下次要做的是下面「待办」里的事

**下次开工的第一句话**（照抄即可）：
> 先读 README.md 和 PROJECT_MEMORY.md，看完「收工状态」节再动手；这次只做 <具体一件事>，别动其他模块。


## 技术栈与结构（细节见 README）

- 后端 Java 21 + Spring Boot 3.3.5 + Spring JDBC + Flyway + jjwt；PostgreSQL 16（容器 `drive-postgres`，不对外暴露）
- 前端 Vue 3 + Vite，构建产物由后端**同端口 8140** 托管（刻意单端口，少一个 nginx 少一处出错）
- 后端包：`games.beiming.drive.{auth, common, fs, remote, search, share, storage, web}`
- 前端 `frontend/src/pages/`：Home / Login / Profile / Storage / Shares / ShareView / Downloader
- 文件实体按 sha256 前两位分层存卷 `blobs`，天然去重

## 已完成（按 git 提交倒序，能查证的都在这里）

- `feat(web)` 右侧竖栏改为全站常驻
- `style(web)` 统一各页内边距与内容宽度上限；全站字号与留白放大
- `feat(web)` 存储空间页 + 个人信息页
- `feat(web)` 页面切换过渡 + 公共外壳 + 骨架屏；上传按钮切页平滑收拢
- `feat(search)` 自研嵌入式搜索引擎（Lucene + 中英混合分词 + pinyin4j 三形态），替代 SQL LIKE
- `feat(web)` 右侧竖栏 + 顶栏新建菜单；修根目录上传 500
- `feat(web)` 右键菜单 + 自绘对话框（改名/删除都自己画，不用浏览器原生弹窗）
- `feat(drive)` 项目起底：Java 21 + Spring Boot + PostgreSQL + Vue 3

## 工作区当前状态（下次开工先跑 `git status` 核对）

- 分支 `main`，**ahead origin/main 6 个提交，未 push**
- 只有两张截图有改动：`docs/screenshots/rail/70-rail-open-downloader.png`、`71-rail-open-shares.png`
- 后端测试只有 1 个测试类，覆盖很薄——真要改核心逻辑得自己补

## 待办 / 下一步（README「还没做」同步过来的）

1. 回收站自动清理（30 天）
2. 多用户协作、共享目录
3. 离线部署：`docker compose` 目前只跑了 postgres，后端还是本机 java 进程
4. 并入 `beiming-official-website` 的模块化单体（等这个跑稳再决定）
5. 把 6 个未推提交 push 上去

## 已知的坑（踩过的，别再踩）

- **新增前端页面必须同步改 `SpaWebController` 白名单**，否则直接访问该路径 404 并弹回首页（知识库有整卡：`排障SOP_beiming-drive_新增前端页面漏改_SpaWebController_白名单`）
- **`.page` 的 `padding` + `max-width` 必须每页标配**，漏一页就是文字贴边满屏（知识库卡：`beiming-drive_内页布局_.page_的_padding_max-width_必须每页标配`）
- **重新打包前必须停掉占用 8140 的 java 进程**，否则 `mvn package` 报 `Unable to rename ...jar to ...jar.original`
- **git-bash 里 curl 发中文会变 GBK**；用 Python 发请求或 `--data-binary @file`
- **`.bat` 不能写 UTF-8 中文注释**（cmd 按 GBK 解析会把后半行当命令跑）
- **Vite 代理只挂 `/api`**：写 `/s` 之类前缀会把整个前端转发给后端，页面白屏且控制台无报错
- **窄窗口下 `flex: 3` 名称列会被挤成 0 宽**，要 `flex: 1 1 0` + `min-width`

## 可复用的东西

- 站内搜索整套（`backend/.../drive/search/`）可**整包复制**到北冥官网，零耦合、不起独立服务。搬法见 `docs/search-engine-reuse.md` 和知识库卡 `方案_beiming-drive_站内搜索_嵌入式_Lucene...`
- 文档库同步：`python scripts/sync_docs.py`（增量，sha256 相同跳过），`--dry-run` 先看

## 与旧项目的关系

`C:\Users\15780\beiming-pan`（Cloudreve 4.17 换前端 + Python 旁挂）是**上一版，方向是错的**——Cloudreve 后端闭源改不了内核。只留作数据导入来源和参照，不再演进。

## 追加格式（以后每块功能完成后照这个写）

```
## 2026-MM-DD｜<做了什么>
### 主题
### 关键决定（他定的，带日期）
### 踩的坑 / 发现
### 已完成（可查证的：文件、测试数字、提交）
### 待办 / 下一步
```
