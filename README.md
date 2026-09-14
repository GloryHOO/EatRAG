# 🍽️ EatRAG · 尝尝咸淡

基于 **[all-in-rag](https://github.com/)** 开源项目改造的 **Java RAG 食谱问答练习项目**。

这是一个以「吃什么」为主题的检索增强生成（Retrieval-Augmented Generation, RAG）服务：输入一句关于菜品制作、食材、烹饪技巧的自然语言问题，系统从 323 篇中文食谱中检索相关内容，由大模型生成准确、实用的回答。

> **练习定位**：本项目是一个 **单步（Single-Turn）查询** 的 RAG 服务，每次请求独立完成「路由 → 重写 → 检索 → 生成」全流程，**不涉及会话历史管理**（无多轮对话、无对话记忆、无上下文缓存）。

---

## ✨ 特性

- 📚 **单步问答**：一次请求完成一次完整问答，无状态、无会话历史
- 🔍 **混合检索**：向量语义检索 + BM25 词法检索，RRF 融合粗排
- 🎯 **两阶段排序**：RRF 粗排 + 重排序模型精排（带自动降级）
- 🧠 **查询优化**：规则 + LLM 混合路由、条件性查询重写、元数据过滤
- ⚡ **流式输出**：REST 同步问答 + SSE 流式问答
- 💾 **索引持久化**：向量索引与 BM25 索引本地落盘，启动增量加载，避免重复向量化
- 🧱 **手写核心算法**：BM25、RRF、Markdown 分块、父子文档管理均为自主实现，零搜索框架依赖
- 🖥️ **配套 Web 前端**：搜索页（流式回答 + 来源卡片 + 查询中间信息）与食谱库页（统计概览 + 筛选分页 + 详情查看）

---

## 🏗️ 架构

```
用户查询
   │
   ▼
┌──────────────────────────┐
│  查询路由（规则 + LLM）    │  list / detail / general
└───────────┬──────────────┘
            ▼
┌──────────────────────────┐
│  查询重写（条件性调用 LLM） │  模糊查询 → 检索友好形式
└───────────┬──────────────┘
            ▼
┌──────────────────────────┐
│  过滤条件提取（规则匹配）   │  分类 / 难度 元数据过滤
└───────────┬──────────────┘
            ▼
┌──────────────────────────┐
│      混合检索             │
│  ┌──────────┬─────────┐  │
│  │向量检索    │  BM25  │  │  并行执行
│  └──────────┴─────────┘  │
│         ▼ RRF 融合        │
│         ▼ 元数据过滤       │
│         ▼ Rerank 精排     │  qwen3.7-text-rerank
└───────────┬──────────────┘
            ▼
┌──────────────────────────┐
│   父文档回溯（去重排序）    │  chunk → 完整食谱
└───────────┬──────────────┘
            ▼
┌──────────────────────────┐
│   回答生成（按路由策略）    │  列表 / 分步指导 / 基础回答
└───────────┬──────────────┘
            ▼
      回答 / SSE 流
```

**离线索引构建流程：**

```
Markdown 食谱文件 → 元数据提取（分类/难度/菜名） → Markdown 结构分块
    → 向量化（增量，已处理跳过） → SimpleVectorStore
    → BM25 索引（增量合并 → 全量重建）
    → 异步落盘（vector_json.json / bm25.json）
```

---

## 🛠️ 技术栈

| 组件 | 选型 |
|------|------|
| 语言 | Java 25 |
| 框架 | Spring Boot 4.1.1 |
| AI 框架 | Spring AI 2.0.1 |
| 模型提供方 | 阿里百炼（DashScope） |
| LLM | glm-5.2 |
| Embedding | qwen3.7-text-embedding-flash |
| Rerank | qwen3.7-text-rerank |
| 向量存储 | SimpleVectorStore（嵌入式） |
| 前端 | Vue 3 + Vite + Element Plus + Vue Router |
| 构建工具 | Maven / npm |

### 框架使用边界

- **Spring AI 负责**：ChatModel / EmbeddingModel 调用抽象、SimpleVectorStore 向量存储、`.st` Prompt 模板渲染
- **自主实现**：BM25 检索、RRF 融合、Markdown 分块、父子文档管理、查询路由/重写、检索 Pipeline 编排、Rerank 封装（HTTP 直调 DashScope）

---

## 🚀 快速开始

### 1. 环境要求

- JDK 25+
- Maven 3.9+
- Node.js 20+（仅构建前端时需要）
- 阿里百炼 API Key（[获取地址](https://bailian.console.aliyun.com/)）

### 2. 配置

设置环境变量：

```bash
export DASHSCOPE_API_KEY=sk-xxxxxxxx
```

可选：修改 `src/main/resources/application.yml` 中的数据路径（默认指向 all-in-rag 的 C8 食谱目录）：

```yaml
eatagent:
  data:
    path: /path/to/your/recipes/dishes   # 外部食谱目录，回退到 classpath:data/dishes/
```

### 3. 构建前端

前端源码在 `frontend/`，构建产物输出到 `src/main/resources/static/`，由 Spring Boot 单 jar 托管：

```bash
cd frontend
npm install
npm run build      # 产物进入 ../src/main/resources/static/
```

前端开发模式（热更新，`/api` 自动代理到 8080）：

```bash
cd frontend
npm run dev        # 访问 http://localhost:5173
```

### 4. 启动

```bash
mvn spring-boot:run
```

启动时自动构建/加载索引。首次启动会全量向量化 323 篇食谱；之后启动若存在本地索引文件则增量加载，只处理新增文档。

访问 `http://localhost:8080/` 即可使用 Web 界面。

### 5. 快速验证

```bash
# 查看知识库状态
curl http://localhost:8080/api/knowledge/stats

# 文档列表（筛选 + 分页）
curl "http://localhost:8080/api/knowledge/documents?category=汤品&page=1&size=5"

# 文档详情（parentId 来自列表接口）
curl "http://localhost:8080/api/knowledge/documents/{parentId}"

# 同步问答
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"question":"宫保鸡丁怎么做"}'

# 流式问答（SSE）
curl -N "http://localhost:8080/api/chat/stream?question=推荐几个简单的素菜"
```

---

## 📡 API 一览

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/chat` | 同步问答 |
| GET | `/api/chat/stream?question=...` | SSE 流式问答 |
| GET | `/api/knowledge/documents` | 文档列表（category / difficulty / keyword 筛选，page / size 分页） |
| GET | `/api/knowledge/documents/{parentId}` | 文档详情（完整 Markdown 原文） |
| POST | `/api/knowledge/rebuild` | 全量重建索引 |
| GET | `/api/knowledge/stats` | 知识库统计信息 |

统一响应格式：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "answer": "...",
    "routeType": "detail",
    "sources": [
      { "parentId": "ea2cc354...", "dishName": "宫保鸡丁", "category": "荤菜", "difficulty": "困难" }
    ]
  }
}
```

SSE 流式响应包含两类事件：
- `message`：文本片段 `{"type":"message","content":"片段"}`
- `done`：结束时一次性下发 `{"type":"done","routeType":"detail","sources":[...],"originalQuery":"...","rewrittenQuery":"..."}`

> **注**：文档详情以 `parentId` 而非菜名作为唯一键——食谱数据中存在同名菜品（如「陈皮排骨汤」出现 2 处），菜名不唯一。

---

## 📂 项目结构

```
src/main/java/com/yybbglory/ai/eatagent/
├── config/          # 配置（RagProperties、RagConfig）
├── controller/      # REST 控制器（Chat / Knowledge）
├── service/         # 业务编排（ChatService、KnowledgeService）
├── rag/
│   ├── chunking/    # Markdown 结构分块
│   ├── document/    # 食谱加载、父子文档管理
│   ├── query/       # 查询路由、查询重写、过滤提取
│   └── retrieval/   # BM25、RRF、Rerank、混合检索
├── model/           # 领域模型、DTO、枚举
├── exception/       # 业务异常与全局异常处理
└── util/            # 文本、文档序列化、Prompt 模板工具
src/main/resources/
├── application.yml
├── prompts/         # .st Prompt 模板
├── static/          # 前端构建产物（由 frontend/ 构建生成）
└── data/dishes/     # classpath 回退数据（可选）

frontend/            # Vue 3 前端工程
├── vite.config.js   # /api 代理到 8080，构建产物输出到 static/
└── src/
    ├── api/         # 接口封装（SSE 流式问答、知识库 REST）
    ├── views/       # SearchView（搜索页）、DocumentsView（食谱库页）
    ├── components/  # SourceCard、QueryTrace、StatsOverview、DocumentDetailDrawer
    └── router/      # 路由表

docs/                # 规格说明（spec / spec-frontend）、ADR
tasks/               # 任务拆分与进度跟踪
```

---

## 🖥️ 前端页面

### 搜索页（`/search`）

- 输入自然语言问题，**SSE 流式打字机**展示回答
- 展示**查询中间信息**：路由类型（列表/详细/一般）与查询重写前后对比
- 回答下方展示**来源卡片**（菜名 + 分类 + 难度），点击跳转到该食谱详情
- 内置示例问题、索引未就绪提示、中断保护（保留已接收内容）

### 食谱库页（`/documents`）

- 顶部**统计概览**：文档总数、文本块总数、分类与难度分布
- **筛选**（分类 / 难度 / 菜名关键词）+ **分页表格**
- 点击行或访问 `/documents/:parentId` 打开**详情抽屉**，渲染完整 Markdown 食谱
- 提供**重建索引**入口（确认弹窗 → 重建 → 自动刷新统计与列表）

---

## 🔌 模型配置

本服务通过 **DashScope OpenAI 兼容模式**接入阿里百炼（`base-url: https://dashscope.aliyuncs.com/compatible-mode/v1`），因此：
- Chat / Embedding 由 Spring AI 的 `OpenAI` starter 驱动（可切换任意 OpenAI 兼容服务）
- Rerank 因 DashScope 不提供 OpenAI 兼容接口，采用手写 HTTP 调用原生 DashScope API

所有模型名称、温度、top-k 等参数均在 `application.yml` 中可调。

---

## 📝 练习要点

本项目的核心价值在于 **手写 RAG 关键组件**，理解其内部原理：

| 组件 | 实现要点 |
|------|---------|
| **BM25** | 手写实现，k1=1.5、b=0.75，中文按标点 + 单字切分 |
| **RRF 融合** | `score = Σ 1/(k + rank)`，k=60，MD5 文档去重 |
| **Markdown 分块** | 按 `#/##/###` 标题层级切分，保留标题上下文 |
| **父子文档** | 检索子块、生成用完整父文档，按命中次数去重排序 |
| **查询路由** | 正则规则秒判列表查询，LLM 兜底语义分类 |
| **索引持久化** | content_hash 增量判定，异步落盘，重启免全量重建 |

### 前端练习要点

| 组件 | 实现要点 |
|------|---------|
| **SSE 流式渲染** | `EventSource` 监听 `message`/`done` 事件，累积文本实现打字机效果，完成后渲染 Markdown |
| **连接管理** | `done` 事件后主动 `close()`，避免 EventSource 自动重连导致重复请求；重新提问前关闭旧连接 |
| **前后端契约** | 统一 `{code,message,data}` 响应；`parentId` 作为详情唯一键解决菜名重复 |
| **工程形态** | 独立 `frontend/` 目录 + Vite proxy 开发 + 构建产物进 `static/` 单 jar 部署 |

---

## 📚 参考资料

- [all-in-rag](https://github.com/) — 本项目参考的原始 RAG 项目（C8 食谱问答）
- [Spring AI](https://spring.io/projects/spring-ai) — Java AI 应用框架
- [Spring AI Alibaba](https://java2ai.com/) — 阿里百炼生态集成
- [阿里百炼（DashScope）](https://help.aliyun.com/zh/model-studio/) — 模型服务文档
- [Vue 3](https://cn.vuejs.org/) / [Element Plus](https://element-plus.org/zh-CN/) — 前端框架与组件库
- `docs/spec.md` — 后端规格说明
- `docs/spec-frontend.md` — 前端规格说明
- `docs/adr/0001-rag-架构总体设计.md` — 后端架构决策
- `docs/adr/0002-web前端架构设计.md` — 前端架构决策

---

## 📄 License

本项目仅用于 **学习与练习** 目的。
