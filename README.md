# AI 智能客服工单系统

基于 **DDD + 六边形架构** 的 AI 工单处理系统。用户用自然语言描述诉求，系统通过 **Plan-and-Execute** 编排图完成意图识别、多步规划、工具调用、人机确认（HITL）与结果校验，并以 **SSE 流式** 把每一步实时推给前端。

> 深入设计细节见根目录 [`技术设计文档.md`](技术设计文档.md)。

---

## 核心特性

- **Plan-and-Execute 编排**：基于 LangGraph4j 的有向图（改写 → 规划 → 检索 → 执行 → 确认 → 校验 → 回复 → 记忆），带受控路由、有界重规划与中断续跑。
- **Function Calling 工具层**：9 个业务工具自动注册，模型按意图选择调用；敏感操作（取消/售后/支付/退款）走 **人机确认**。
- **真实副作用 + 后置校验**：写库工具先读真实订单、做状态守卫与幂等判断，UPDATE 后回查确认状态真的变更，而非只看影响行数。
- **RAG 知识库**：通用政策咨询（如物流规则）走向量检索而非追问订单号；Milvus 未启用时自动降级为内存知识库。
- **长短期记忆**：短期对话记忆（进程内）+ 长期用户画像/语义记忆（Chroma 向量库，重启不丢）。
- **代码沙箱**：`run_code` 工具在受限表达式引擎内做算术等多步推理。
- **可观测性**：接入 Langfuse，记录每次 AI 调用的 trace。
- **流式体验**：Vue 3 前端通过 SSE 实时展示 AI 分析进度与结果。

---

## 技术栈

| 分类 | 选型 |
| --- | --- |
| 语言 / 框架 | Java 17、Spring Boot 4.1.1 |
| AI 编排 | LangChain4j 0.36.2、LangGraph4j-core 1.8.19 |
| 大模型 | 通义千问 `qwen-max`（DashScope OpenAI 兼容模式）、`text-embedding-v3` |
| 持久层 | MyBatis 4.0.0、MySQL |
| 向量库 | Chroma（长期记忆）、Milvus（知识库，默认关闭并降级） |
| 可观测性 | Langfuse |
| 前端 | Vue 3.5、Vite 8、TypeScript |
| API 文档 | SpringDoc OpenAPI 2.8.0（Swagger UI） |
| 其他 | Spring Retry、Docker Compose |

---

## 架构分层

```
interfaces   ──  REST 控制器 / DTO / 全局异常（入站适配器）
application  ──  应用服务编排、Command / Result
domain       ──  聚合、端口（BusinessTool / Repository / Gateway），纯业务、无框架依赖
infrastructure ── 端口实现：AI、工具、持久化、记忆、RAG、编排图（出站适配器）
```

依赖方向始终指向 domain，符合六边形架构「端口与适配器」原则。新增工具只需实现 `BusinessTool` 端口并标注 `@Component`，即被自动收集注册。

---

## 业务工具

| 工具 | 说明 | 需确认 |
| --- | --- | :---: |
| `query_order` | 按订单号查询订单 | |
| `list_orders` | 查询当前客户的全部订单 | |
| `query_logistics` | 查询物流轨迹 | |
| `query_after_sale` | 查询订单的售后/退款进度 | |
| `run_code` | 沙箱内执行算术表达式 | |
| `cancel_order` | 取消订单（状态守卫 + 幂等） | ✅ |
| `create_after_sale` | 申请售后退款，订单转「退款中」并写售后记录 | ✅ |
| `pay_order` | 支付订单，`CREATED → PAID` | ✅ |
| `process_refund` | 退款到账，订单与售后记录转 `REFUNDED` | ✅ |

订单状态：`CREATED`（待付款）、`PAID`（已支付）、`SHIPPED`（已发货）、`DELIVERED`（已签收）、`CANCELLED`（已取消）、`REFUNDING`（退款中）、`REFUNDED`（已退款）。

---

## 快速开始

### 前置条件

- JDK 17、Docker & Docker Compose
- Node.js 18+（仅本地跑前端时需要）
- 一个 DashScope（阿里云百炼）API Key

### 1) 配置密钥

仓库不含任何真实密钥。复制模板并填入你自己的值：

```bash
cp .env.example .env
# 编辑 .env，至少填写 AI_QWEN_API_KEY
```

`.env` 已被 `.gitignore` 排除，同时供 **docker compose**（变量替换）与 **Spring Boot**（`spring.config.import`）读取。

> 注意：`MYSQL_PASSWORD` 需与 `SPRING_DATASOURCE_PASSWORD` 一致，后端才能连上数据库。

### 2) 一键启动（推荐）

```bash
docker compose up -d --build
```

将启动 MySQL、Chroma、Langfuse 与后端服务。后端地址 `http://localhost:8080`，Swagger UI `http://localhost:8080/swagger-ui.html`，Langfuse `http://localhost:3000`。

### 3) 数据库初始化

- `schema.sql` 已挂载到 MySQL 的 `/docker-entrypoint-initdb.d/`，**仅在数据卷全新时自动执行一次**。
- 演示种子数据 `data.sql` **不会自动导入**，需要时手动执行：

```bash
docker compose exec -T mysql sh -c 'exec mysql -umyuser -p"$MYSQL_PASSWORD" mydatabase' < src/main/resources/data.sql
```

> 若 `mysql_data` 卷已存在（非全新），schema 不会重跑；改表/改种子后需手动迁移。

### 4) 前端

```bash
cd frontend
npm install
npm run dev      # http://localhost:5173，已配置 /api 代理到 :8080
```

### 本地直接跑后端（不用容器）

需自行准备 MySQL 与 Chroma，然后：

```bash
./mvnw spring-boot:run
```

---

## API 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/tickets/stream` | 创建工单并以 SSE 流式返回处理过程 |
| GET | `/api/tickets/health` | 健康检查 |
| POST | `/api/conversations/{conversationId}/actions/{actionId}` | 人机确认：批准/拒绝挂起的敏感操作 |

创建工单请求体：

```json
{ "customerId": "C001", "content": "帮我取消订单10086", "conversationId": "CV_DEMO" }
```

确认操作请求体：

```json
{ "approved": true }
```

> 安全说明：`customerId` 由服务端在 `@Valid` 之后强制置为 `C001`，工具执行时也以服务端上下文为准，**不信任模型输出中的 customerId**。

---

## 测试

```bash
./mvnw test
```

覆盖工具状态守卫/幂等、Plan-and-Execute 图的中断续跑、意图解析、沙箱、记忆隔离等，全量 **124 个用例通过**。

---

## 项目结构

```
src/main/java/com/zzj/interview/
├── interfaces/        REST 控制器、DTO、全局异常
├── application/       应用服务、Command / Result
├── domain/            聚合、端口、领域模型（ticket / conversation / tool / memory / rag / prompt）
└── infrastructure/    端口实现：ai / tool / workflow / persistence / memory / rag / prompt / sandbox / sse
src/main/resources/    application.yml、schema.sql、data.sql、mapper/*.xml
frontend/              Vue 3 前端
compose.yaml           MySQL / Chroma / Langfuse / 后端 编排
技术设计文档.md         详细设计文档
```

---

## 密钥与安全

- 所有密钥通过环境变量注入：`application.yml` / `compose.yaml` 中均为 `${VAR}` 占位，真实值只在被 gitignore 的 `.env`。
- `.env.example` 为占位模板，不含任何真实凭据。
- 敏感业务操作需前端二次确认（HITL）后才落库。

---

## 可观测性

启用 Langfuse 后（`compose.yaml` 已内置），每次 AI 调用会上报 trace，可在 `http://localhost:3000` 查看。默认账号见 `.env` 中的 `LANGFUSE_INIT_USER_EMAIL` / `LANGFUSE_INIT_USER_PASSWORD`。

---

## 已知限制

- 短期对话记忆、LangGraph 检查点（含挂起的 HITL 确认）为进程内存储，**后端重启后丢失**；长期记忆的语义向量存于 Chroma，重启不丢。
- Milvus 默认关闭，知识库降级为内存实现（7 条 FAQ）。
- 演示用 `customerId` 固定为 `C001`。

更多权衡与细节见 [`技术设计文档.md`](技术设计文档.md)。
