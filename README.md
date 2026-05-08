# MiniDB-Lab / sql_demo



## 项目简介

MiniDB-Lab 是一个用订单履约业务验证 MVCC、MySQL 协议代理、分片路由、事务一致性和数据库可观测能力的教学型数据库工程实验平台。

项目类型：数据库工程实验平台 / 后端服务原型 / Web 控制台项目。

本项目不是生产级数据库，也不是完整电商系统。它通过“创建订单、扣库存、支付回调、履约发货”等真实业务场景，帮助学习者和后端开发者理解数据库内部机制、中间件路由逻辑以及业务一致性风险。

当前仓库已建立 Maven 多模块工程、自动验证脚本、订单履约 API、MySQL 协议代理、自研 MVCC 实验引擎和 React Web 控制台。Web 控制台按职责拆分为订单履约终端和数据库实验终端，二者通过真实订单链路关联，但界面和用户路径保持独立。



## 核心功能

- 简易 MVCC 事务管理器：演示事务 ID、版本链、Undo Log、Read View、Read Committed 与 Repeatable Read 的差异。
- 简易 MySQL 协议代理：支持 MySQL 握手、`COM_QUERY`、SQL 转发、连接池、读写分离、事务绑定和分片路由。
- 订单履约业务验证：覆盖创建订单、库存扣减、支付回调、取消订单、履约发货、幂等和异常补偿。
- 分片路由实验：按 `user_id` 进行单分片路由，通过 `order_no` / `payment_no` 查路由表后定位用户分片，拒绝缺少分片键的高风险查询。
- 数据库实验观测台：展示 SQL 路由、事务上下文、幂等记录、Outbox 事件和 MVCC 版本链。
- 工程约束验证：通过测试、迁移脚本、状态机和审计记录保证关键逻辑可验证、可回溯。







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
```

严格模式用于 CI 或完整环境：

```bash
python tools/verify_local.py --strict
```

后端构建与测试：

```bash
mvn -B verify
mvn -pl mini-mvcc test
mvn -pl mini-proxy test
mvn -pl order-api test
```

后端服务启动：

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

加载演示订单：

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

推荐按以下顺序理解和实现项目：

1. 先理解订单履约场景：创建订单、扣库存、支付成功、履约发货和取消订单。
2. 再理解 `mini-mvcc`：用内存 KV 演示 RC/RR、Undo Log、Read View 和版本链。
3. 再理解 `mini-proxy`：通过 MySQL 客户端连接代理并执行 SQL。
4. 然后理解订单单库闭环：所有写接口必须幂等，所有状态更新必须带原状态条件。
5. 最后接入代理和分片：按 `user_id % N` 路由订单相关 SQL。
6. 使用 Web 控制台分别验证业务动作和数据库观测，不把两类用户路径混成一个页面。

第一版明确不做：

- 完整 SQL 数据库。
- 完整 MySQL 协议兼容。
- 跨分片事务。
- 自动跨分片 Join。
- 真实支付 SDK。
- 完整电商前台、ERP 或 WMS。

关键业务约束：

- 下单扣库存必须使用条件更新，不能依赖快照读。
- 支付回调必须验签、幂等、审计。
- 重复下单必须返回第一次处理结果。
- 缺少分片键的高风险查询必须拒绝。
- 数据库结构变更必须通过 Flyway 迁移脚本执行。

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
