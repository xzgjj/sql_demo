# MiniDB-Lab / sql_demo



## 项目简介

MiniDB-Lab 是一个用订单履约业务验证 MVCC、MySQL 协议代理、分片路由、事务一致性和数据库可观测能力的教学型数据库工程实验平台。

项目类型：数据库工程实验平台 / 后端服务原型 / Web 控制台项目。

本项目不是生产级数据库，也不是完整电商系统。它通过“创建订单、扣库存、支付回调、履约发货”等真实业务场景，帮助学习者和后端开发者理解数据库内部机制、中间件路由逻辑以及业务一致性风险。

当前仓库已建立 Maven 多模块工程、自动验证脚本、订单履约 API、MySQL 协议代理、自研 MVCC 实验引擎和 React Web 控制台。Web 控制台按职责拆分为订单履约终端和数据库实验终端，二者通过真实订单链路关联，但界面和用户路径保持独立。

v7 阶段开始，项目推进方式调整为”整体目标导向”：先把用户行为路径、状态反馈、接口数据结构、数据库设计、可观测证据、对标取舍和阶段验收标准补齐，再按阶段逐次推进。所有后续阶段均在 v7 分支内完成，按项目阶段编号区分。详细设计记录在本地文档 `doc/14_v7_整体设计补齐与落地验收.md`，该目录按项目规则不提交远端。



## 核心功能

- 简易 MVCC 事务管理器：演示事务 ID、版本链、Undo Log、Read View、Read Committed 与 Repeatable Read 的差异。
- 简易 MySQL 协议代理：支持 MySQL 握手、`COM_QUERY`、SQL 转发、连接池、读写分离、事务绑定和分片路由。
- 订单履约业务验证：覆盖创建订单、库存扣减、支付回调、取消订单、履约发货、幂等和异常补偿。
- 分片路由实验：按 `user_id` 进行单分片路由，通过 `order_no` / `payment_no` 查路由表后定位用户分片，拒绝缺少分片键的高风险查询。
- 数据库实验观测台：展示 SQL 路由、事务上下文、幂等记录、Outbox 事件和 MVCC 版本链。
- 工程约束验证：通过测试、迁移脚本、状态机和审计记录保证关键逻辑可验证、可回溯。



- **项目实机终端运行情况如下**



- **前台**

![PixPin_2026-05-09_21-16-27](../../../图片/md/PixPin_2026-05-09_21-16-27.png)



- **数据库后台**

![PixPin_2026-05-09_21-16-38](../../../图片/md/PixPin_2026-05-09_21-16-38.png)





## 技术栈

| 分类 | 技术 |
| --- | --- |
| 前端 | React、Ant Design Pro / ProComponents |
| 后端 | Java 17、Spring Boot、Netty |
| 数据库 | MySQL InnoDB |
| 数据库迁移 | Flyway |
| SQL 解析 | Druid SQL Parser，后续可评估 Apache Calcite |
| 测试 | JUnit 5、Testcontainers、Vitest |
| 接口文档 | OpenAPI |
| 可观测 | OpenTelemetry、Micrometer、Spring Boot Actuator |
| 构建工具 | Maven、npm |
| 其他 | Docker、GitHub Actions |



## 项目架构

整体架构围绕三条主线展开：数据库原理实验、中间件代理实验、订单履约业务验证。

```text
Browser / Web Console
        |
        v
order-api ---------------> MySQL InnoDB
   |                             ^
   |                             |
   v                             |
mini-mvcc                 mini-proxy
事务可见性实验              MySQL 协议代理 / 路由 / 连接池
```

更完整的运行关系如下：

```mermaid
flowchart LR
    browser["Browser / Web Console"] --> business["订单履约终端"]
    browser --> lab["数据库实验终端"]

    business --> api["order-api :8080"]
    lab --> api
    lab --> mvcc["mini-mvcc<br/>RC / RR / Version Chain"]

    api --> proxy["mini-proxy :3306"]
    proxy --> primary["PRIMARY<br/>products / inventory / idempotency / outbox / route"]
    proxy --> shard0["shard_0"]
    proxy --> shard1["shard_1"]
    proxy --> shard2["shard_2"]
    proxy --> shard3["shard_3"]
```

### 模块目录与功能

| 目录 | 功能 |
| --- | --- |
| `mini-mvcc/` | 简易 MVCC 实验引擎，负责事务可见性、版本链、Undo Log、Read View、隔离级别和写冲突演示 |
| `mini-proxy/` | 简易 MySQL 协议代理，负责握手、`COM_QUERY`、SQL 转发、读写分离、分片路由、连接池和事务绑定 |
| `order-api/` | 订单履约业务 API，负责订单、库存、支付、履约、取消、幂等、Outbox 和异常工单 |
| `web-console/` | React Web 控制台，包含订单履约终端和数据库实验终端 |
| `tools/` | 本地自动化验证脚本，统一检查环境、项目结构、构建、测试、lint 和 Git 忽略策略 |
| `logs/` | 本地运行日志目录，仅保留说明文件，日志文件不进入版本控制 |
| `.github/workflows/` | GitHub Actions CI，使用 JDK 17 执行严格验证 |



### 核心链路

```text
创建订单
  -> 幂等检查
  -> 库存条件扣减
  -> 写订单与订单明细
  -> 写库存流水
  -> 写 Outbox 事件
  -> 写 order_route 路由记录
  -> 返回订单结果
```

```text
SQL 请求
  -> mini-proxy 接收 MySQL 协议包
  -> SQL Parser 提取表、类型和分片键
  -> Router 判断 PRIMARY / 分片
  -> Backend Pool 借用连接
  -> MySQL 返回结果集
```

```mermaid
sequenceDiagram
    participant U as 用户
    participant W as Web Console
    participant A as order-api
    participant P as mini-proxy
    participant R as PRIMARY order_route
    participant S as MySQL Shard

    U->>W: 创建订单 / 支付 / 取消 / 发货
    W->>A: REST API + Idempotency-Key
    A->>A: 校验状态机与幂等记录
    A->>P: 参数化 SQL
    P->>R: order_no/payment_no 查 user_id
    P->>S: user_id % 4 路由
    S-->>A: 返回业务写入结果
    A-->>W: 返回业务状态与下一步动作
```



## 快速开始

环境检查：

```bash
java -version
mvn -version
docker --version
mysql --version
node --version
npm --version
```

运行本地自动验证：

```bash
python tools/verify_local.py
python tools/verify_local.py verify

后端：

order-api：http://127.0.0.1:8080
订单履约终端：http://127.0.0.1:5173/business/dashboard
```

严格模式用于 CI 或完整环境：

```bash
python tools/verify_local.py --strict
```

统一工程维护命令：

```bash
# 预演清理 Maven target/ 和前端 dist/，不实际删除
python tools/verify_local.py clean

# 执行清理，仅限白名单构建产物目录
python tools/verify_local.py clean --apply

# 检查日志大小和同名前缀日志数量，默认限制 20 MB、10 个文件
python tools/verify_local.py log-check

# 自定义日志约束；超出数量的旧日志加 --apply 后归档到 logs/archive/
python tools/verify_local.py log-check --max-log-size-mb 20 --max-log-files 10
python tools/verify_local.py log-check --apply
```

- 后端构建与测试：


```bash
mvn -B verify
mvn -pl mini-mvcc test
mvn -pl mini-proxy test
mvn -pl order-api test
```

- 后端服务启动：


```bash
mvn -f order-api/pom.xml org.springframework.boot:spring-boot-maven-plugin:3.2.5:test-run -Dspring-boot.run.profiles=test
```

前端启动：

```bash
cd web-console
npm install
npm run dev
```

数据库迁移：

```bash
mvn -pl order-api flyway:migrate
mvn -pl order-api flyway:info
```



### 本地访问地址

| 页面 / 服务 | 地址 |
| --- | --- |
| 订单履约终端 | `http://127.0.0.1:5173/business/dashboard` |
| 订单队列 | `http://127.0.0.1:5173/business/orders` |
| 履约工作台 | `http://127.0.0.1:5173/business/fulfillment` |
| 异常中心 | `http://127.0.0.1:5173/business/exceptions` |
| 数据库实验终端 | `http://127.0.0.1:5173/database/lab` |
| 链路追踪 | `http://127.0.0.1:5173/database/trace` |
| order-api | `http://127.0.0.1:8080` |
| 健康检查 | `http://127.0.0.1:8080/actuator/health` |
| mini-proxy | `127.0.0.1:3306` |

- 加载演示订单：

- OpenAPI/接口文档：http://localhost:8080/swagger-ui.html

运行模式接口：

```bash
curl http://localhost:8080/api/runtime/mode
```

```bash
curl -X POST http://127.0.0.1:8080/api/console/demo/load ^
  -H "Idempotency-Key: demo-orders-001"
```



## 环境变量

本地测试配置可以直接使用 `application-test.yml`。后续接入真实 MySQL、代理和分片时，建议使用以下配置项：

```env
DATABASE_URL=
DATABASE_USERNAME=
DATABASE_PASSWORD=
MINIDB_PROXY_PORT=3306
MINIDB_SHARD_COUNT=4
MINIDB_DEFAULT_ISOLATION=REPEATABLE_READ
MINIDB_CONSOLE_DEMO_ENABLED=true
MINIDB_BACKEND_HOST=127.0.0.1
MINIDB_BACKEND_PORT_BASE=4407
MINIDB_BACKEND_USERNAME=root
MINIDB_BACKEND_PASSWORD=
MINIDB_PRIMARY_DATABASE=minidb
MINIDB_REPLICA_DATABASE=minidb
MINIDB_SHARD_DATABASE_PREFIX=minidb_shard_
```

所有真实密钥、数据库密码和外部服务凭证不得提交到仓库。



## 项目结构

当前结构：

```text
sql_demo/
  README.md
  PROJECT_DESIGN.md
  pom.xml

  mini-mvcc/
    pom.xml
    src/main/java/com/minidb/mvcc/
    src/test/java/com/minidb/mvcc/

  mini-proxy/
    pom.xml
    src/main/java/com/minidb/proxy/
    src/test/java/com/minidb/proxy/

  order-api/
    pom.xml
    src/main/java/com/minidb/order/
    src/main/resources/db/migration/
    src/test/java/com/minidb/order/

  web-console/
    package.json
    src/

  tools/
    verify_local.py

  logs/
    README.md

  .github/workflows/
    ci.yml
```



## 使用说明

- 推荐按以下顺序理解和实现项目：


1. 先理解订单履约场景：创建订单、扣库存、支付成功、履约发货和取消订单。
2. 再理解 `mini-mvcc`：用内存 KV 演示 RC/RR、Undo Log、Read View 和版本链。
3. 再理解 `mini-proxy`：通过 MySQL 客户端连接代理并执行 SQL。
4. 然后理解订单单库闭环：所有写接口必须幂等，所有状态更新必须带原状态条件。
5. 最后接入代理和分片：按 `user_id % N` 路由订单相关 SQL。
6. 使用 Web 控制台分别验证业务动作和数据库观测，不把两类用户路径混成一个页面。



关键业务约束：

- 下单扣库存必须使用条件更新，不能依赖快照读。
- 支付回调必须验签、幂等、审计。
- 重复下单必须返回第一次处理结果。
- 缺少分片键的高风险查询必须拒绝。
- 数据库结构变更必须通过 Flyway 迁移脚本执行。



### 运行模式边界

本项目当前明确区分两种运行模式：

| 模式 | 用途 | 数据源 | 查询边界 |
| --- | --- | --- | --- |
| 单库直连模式（默认） | 订单闭环、控制台聚合、演示数据、H2/本地 MySQL 快速验证 | `application.yml` 中的 MySQL | 允许控制台全局聚合和按 `order_id` 查询 |
| proxy 实验模式 | 验证 MySQL 协议、分片键路由、缺键拒绝和事务绑定 | `application-proxy.yml`，连接 `mini-proxy` | 分片表必须携带 `user_id`、`order_no` 或 `payment_no`；订单履约写流程不在 proxy 模式执行 |

### proxy 一致性边界

- proxy 模式采用保守一致性策略：`mini-proxy` 不实现 XA、2PC 或跨分片事务，因此一个逻辑事务只能绑定一个物理后端数据源。事务一旦绑定 `PRIMARY` 或某个 `shard_N`，后续访问其他数据源会返回明确错误；缺少分片键、`OR` 分片条件、多行写入、无法证明单分片的 SQL 也会被拒绝。


订单履约完整写链路涉及 `products`、`product_inventory`、`idempotency_records`、`outbox_events`、`order_route` 等 PRIMARY 元数据表，以及 `orders`、`order_items`、`payments`、`fulfillment_tasks` 等分片业务表。当前项目没有分布式事务协调器，因此这些写流程仅在单库直连模式下作为完整业务闭环运行；proxy 模式用于验证分片路由、事务绑定、缺键拒绝、路由观测和只读/实验性查询。



- 前端交互上需要向用户明确区分：

订单履约终端：面向业务流程，使用单库直连模式，用户能创建订单、支付、取消、履约、查看异常和 outbox 状态。

数据库实验终端：面向数据库学习，使用 proxy 实验模式，用户提交 SQL 后看到路由决策、绑定数据源、拒绝原因和事务状态。

当用户在 proxy 模式尝试订单写流程时，接口返回 `PROXY_MODE_UNSUPPORTED_WRITE`，页面应展示“该流程跨 PRIMARY 与分片表，当前实验代理不支持跨数据源事务，请切回单库直连模式执行业务闭环”。



proxy 实验模式启动参考：

```bash
# 先启动或准备你自己的 MySQL 后端，再启动 mini-proxy
set MINIDB_BACKEND_HOST=127.0.0.1
set MINIDB_BACKEND_PORT_BASE=4407
set MINIDB_PRIMARY_DATABASE=minidb
set MINIDB_REPLICA_DATABASE=minidb
set MINIDB_SHARD_DATABASE_PREFIX=minidb_shard_
mvn -pl mini-proxy exec:java -Dexec.mainClass=com.minidb.proxy.MiniProxyServer

# order-api 经 proxy 访问，仅用于分片实验接口验证
mvn -f order-api/pom.xml spring-boot:run -Dspring-boot.run.profiles=proxy
```

proxy smoke 验收入口：

```bash
# 默认 dry-run，不连接数据库、不写数据
python tools/proxy_smoke.py

# 真实联调时显式执行网络检查
python tools/proxy_smoke.py --execute --json
```



- 本轮验收命令：


```bash
mvn -pl mini-proxy test
mvn -pl order-api test
npm --prefix web-console run lint
npm --prefix web-console run test
python tools/verify_local.py --json
```

本轮实际验证结果：

- `mvn -pl mini-proxy test -B`：通过，73 个测试通过，1 个原有手工集成测试跳过。
- `mvn -pl order-api -am test -B`：通过，46 个 order-api 测试和 28 个 mini-mvcc 测试通过。
- `npm --prefix web-console run lint`：通过。
- `npm --prefix web-console run test`：通过，2 个前端测试通过。
- `npm --prefix web-console run build`：通过，TypeScript 编译和 Vite 构建成功。
- `mvn -B verify`：通过，全 Maven reactor 成功。
- `python tools/verify_local.py --json`：通过，包含环境、结构、proxy dry-run、后端测试、前端 lint/test 和 Maven verify。
- `git diff --check`：通过，无空白错误。
- `python tools/proxy_smoke.py --execute --json`：未执行；当前 Docker daemon 未启动，未发现可用的本地 MySQL/proxy 联调环境。本轮已保留 dry-run smoke 作为非破坏性验收，真实网络 smoke 需在后端环境启动后执行。



## 部署说明

暂无正式部署方式。

本地开发部署形态：

```text
web-console   :5173
order-api     :8080
mini-proxy    :3306
mysql-primary :PRIMARY
mysql-shard-0 :shard_0
mysql-shard-1 :shard_1
mysql-shard-2 :shard_2
mysql-shard-3 :shard_3
```

MVP 阶段优先本地运行和 CI 验证，不依赖 Kubernetes、云服务或真实支付渠道。



## License

暂无。
