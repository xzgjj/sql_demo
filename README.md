# MiniDB-Lab / sql_demo

## 项目简介

MiniDB-Lab 是一个用订单履约业务验证 MVCC、MySQL 协议代理、分片路由、事务一致性和数据库可观测能力的教学型数据库工程实验平台。

项目类型：数据库工程实验平台 / 后端服务原型 / Web 控制台规划项目。

本项目不是生产级数据库，也不是完整电商系统。它通过“创建订单、扣库存、支付回调、履约发货”等真实业务场景，帮助学习者和后端开发者理解数据库内部机制、中间件路由逻辑以及业务一致性风险。

当前仓库处于 0-1 规划阶段，代码工程骨架尚未创建。

## 核心功能

- 简易 MVCC 事务管理器：演示事务 ID、版本链、Undo Log、Read View、Read Committed 与 Repeatable Read 的差异。
- 简易 MySQL 协议代理：规划支持 MySQL 握手、`COM_QUERY`、SQL 转发、连接池、读写分离和事务绑定。
- 订单履约业务验证：覆盖创建订单、库存扣减、支付回调、取消订单、履约发货、幂等和异常补偿。
- 分片路由实验：按 `user_id` 进行单分片路由，拒绝缺少分片键的高风险查询。
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
| 测试 | JUnit 5、Testcontainers |
| 接口文档 | OpenAPI |
| 可观测 | OpenTelemetry、Micrometer、Spring Boot Actuator |
| 构建工具 | Maven |
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

### 模块目录与功能

| 目录 | 功能 |
| --- | --- |
| `mini-mvcc/` | 简易 MVCC 实验引擎，负责事务可见性、版本链、Undo Log、Read View、隔离级别和写冲突演示 |
| `mini-proxy/` | 简易 MySQL 协议代理，负责握手、`COM_QUERY`、SQL 转发、读写分离、分片路由、连接池和事务绑定 |
| `order-api/` | 订单履约业务 API，负责订单、库存、支付、履约、取消、幂等、Outbox 和异常工单 |
| `sql/` | Flyway 迁移脚本、初始化数据、索引脚本和一致性检查 SQL |
| `api/` | OpenAPI 接口契约，描述订单、支付、履约、异常和数据库实验接口 |
| `web-console/` | 运营工作台、订单队列、履约任务工作台、异常中心和数据库实验观测台 |
| `tools/` | 本地自动化验证脚本，统一检查环境、构建、测试和关键信息 |

### 核心链路

```text
创建订单
  -> 幂等检查
  -> 库存条件扣减
  -> 写订单与订单明细
  -> 写库存流水
  -> 写 Outbox 事件
  -> 返回订单结果
```

```text
SQL 请求
  -> mini-proxy 接收 MySQL 协议包
  -> SQL Parser 提取表、类型和分片键
  -> Router 判断主库/读库/分片
  -> Backend Pool 借用连接
  -> MySQL 返回结果集
```

## 快速开始

当前阶段暂无完整可运行工程，以下为后续开发约定命令。

环境检查：

```bash
java -version
mvn -version
docker --version
mysql --version
node --version
npm --version
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
mvn -pl order-api spring-boot:run
```

前端启动：

```bash
npm install
npm run dev
```

数据库迁移：

```bash
mvn -pl order-api flyway:migrate
mvn -pl order-api flyway:info
```

## 环境变量

当前阶段暂无必需环境变量。

后续进入代码实现后，预计会补充以下配置项：

```env
DATABASE_URL=
DATABASE_USERNAME=
DATABASE_PASSWORD=
MINIDB_PROXY_PORT=3307
MINIDB_SHARD_COUNT=4
MINIDB_DEFAULT_ISOLATION=REPEATABLE_READ
```

所有真实密钥、数据库密码和外部服务凭证不得提交到仓库。

## 项目结构

规划中的项目结构如下：

```text
sql_demo/
  README.md

  mini-mvcc/
    src/main/java/
    src/test/java/

  mini-proxy/
    src/main/java/
    src/test/java/

  order-api/
    src/main/java/
    src/test/java/

  sql/
    V001__schema.sql
    V002__seed_data.sql
    checks/

  api/
    openapi.yaml

  web-console/
    src/

  tests/
    scenarios/
    k6/

  tools/
    verify_local.py
```

## 使用说明

推荐按以下顺序理解和实现项目：

1. 先理解订单履约场景：创建订单、扣库存、支付成功、履约发货和取消订单。
2. 再实现 `mini-mvcc`：用内存 KV 演示 RC/RR、Undo Log、Read View 和版本链。
3. 再实现 `mini-proxy`：先跑通 MySQL 客户端连接代理并执行 `SELECT 1`。
4. 然后实现订单单库闭环：所有写接口必须幂等，所有状态更新必须带原状态条件。
5. 最后接入代理和分片：按 `user_id % N` 路由订单相关 SQL。

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

规划中的本地开发部署形态：

```text
web-console   :5173
order-api     :8080
mini-proxy    :3307
mysql-primary :3306
mysql-replica :3308 optional
mysql-shard-0 :3310 optional
mysql-shard-1 :3311 optional
```

MVP 阶段优先本地运行和 CI 验证，不依赖 Kubernetes、云服务或真实支付渠道。

## License

暂无。
