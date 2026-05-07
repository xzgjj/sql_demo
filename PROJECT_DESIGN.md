# MiniDB-Lab 实现设计与阶段一基线

## 目标复位

MiniDB-Lab 的目标不是把需求拆成零散任务，而是做一个可运行、可讲解、可验证的数据库工程实验平台。系统必须从用户行为、状态变化、接口调用、数据流转和数据库约束五个角度同时成立。

阶段一的目标是建立工程基线：代码目录、构建入口、自动验证脚本、CI 骨架、对标对象和验收标准。

## 对标对象

| 对标对象 | 参考点 | 本项目取舍 |
| --- | --- | --- |
| BusTub | 教学型 DBMS，强调分层实现、课程项目和测试驱动 | 学习“可运行实验 + 自动测试”的组织方式，不复刻完整 C++ DBMS |
| Apache ShardingSphere | 数据分片、读写分离、Proxy/JDBC 双形态、SQL 语义理解 | 学习路由、读写分离和协议代理边界；第一版只做最小 Proxy 子集 |
| Vitess | Vindex 定义分片键和路由策略，Lookup Vindex 支持非分片键定位 | 学习 `user_id` 主分片键和 `order_route` 路由表思想，第一版避免 2PC |
| Maven 标准目录 | 标准 `src/main/java`、`src/test/java`、多模块工程结构 | 阶段一按 Maven 标准创建 `mini-mvcc`、`mini-proxy`、`order-api` |

参考来源：

- BusTub: https://github.com/cmu-db/bustub
- ShardingSphere Overview: https://shardingsphere.apache.org/document/5.3.2/en/overview/
- Vitess Vindexes: https://vitess.io/docs/25.0/reference/features/vindexes/
- Maven Standard Directory Layout: https://maven.apache.org/guides/introduction/introduction-to-the-standard-directory-layout.html

## 用户交互主线

### 1. 运营人员处理订单

```text
进入订单队列
  -> 选择保存视图
  -> 查看订单状态、分片、卡点
  -> 打开详情抽屉
  -> 查看时间线、SQL 路由、幂等记录
  -> 执行取消、退款或创建异常工单
  -> 页面局部刷新并展示状态变化
```

关键反馈：

- 状态变化后刷新当前订单卡片。
- 并发状态变化时提示 `ORDER_STATUS_CHANGED`。
- 缺少分片键时提示补充订单号、用户 ID 或时间范围。

### 2. 仓库人员履约

```text
进入履约工作台
  -> 查看待领取任务
  -> 领取任务
  -> 乐观锁校验成功后进入拣货中
  -> 完成 checklist
  -> 录入承运商和运单号
  -> 发货成功，订单与库存状态同步更新
```

关键反馈：

- 任务已被他人领取时返回 `TASK_ALREADY_CLAIMED`。
- 发货失败时保留任务状态，写异常工单。

### 3. 数据库学习者运行实验

```text
进入 Database Lab
  -> 选择场景 Create Order / Payment Callback / MVCC Repeatable Read
  -> 选择隔离级别
  -> 运行实验
  -> 查看 Step Timeline
  -> 查看 SQL Route、Transaction Context、Version Chain、Outbox
```

关键反馈：

- 每一步必须显示输入、输出、状态变化和失败原因。
- 同一幂等键重复运行时展示“返回第一次响应”。

## 接口与数据结构补全

### 核心接口

| 接口 | 目标 | 关键约束 |
| --- | --- | --- |
| `POST /api/orders` | 创建订单 | 必须有 `Idempotency-Key`，库存条件更新 |
| `POST /api/payments/callbacks/mock-pay` | 支付回调 | 必须验签、幂等、金额校验 |
| `POST /api/orders/{order_id}/cancel` | 取消订单 | 状态条件更新，待支付取消释放库存 |
| `POST /api/fulfillment/tasks/{task_id}/claim` | 领取履约任务 | `status + version` 乐观锁 |
| `POST /api/fulfillment/tasks/{task_id}/ship` | 发货 | 同事务更新任务、订单、发货记录、库存 |
| `POST /api/lab/scenarios/{scenario}/run` | 运行数据库实验 | 返回步骤、路由、事务、版本链 |

### 核心数据结构

| 数据结构 | 说明 |
| --- | --- |
| `CreateOrderCommand` | `user_id`、商品项、幂等键、请求 ID |
| `IdempotencyRecord` | 幂等键、请求 hash、响应快照、状态 |
| `SqlRouteTrace` | SQL、表名、分片键、目标数据源、连接 ID |
| `TransactionContext` | 事务 ID、隔离级别、绑定连接、当前分片 |
| `MvccVersionView` | key、版本列表、创建事务、删除事务、可见性判断 |
| `ExceptionTicket` | 异常类型、证据、建议动作、处理状态 |

## 阶段一验收标准

- 根目录存在 `pom.xml`，包含 `mini-mvcc`、`mini-proxy`、`order-api` 三个模块。
- 每个模块符合 Maven 标准目录结构。
- 每个模块至少有一个可编译主类和一个最小测试。
- 存在 `tools/verify_local.py`，可检测环境、项目结构、忽略规则、追踪策略和 Maven 构建。
- 本地无 Java/Maven 时，默认验证模式能给出明确跳过原因；严格模式用于 CI。
- `.github/workflows/ci.yml` 使用 JDK 17 执行严格验证。
- `.gitignore` 继续保护 `doc/`、本地上下文和测试目录不上传。

## 可调用工具规划

| 工具 | 用途 | 阶段 |
| --- | --- | --- |
| `tools/verify_local.py` | 本地统一验证入口 | 阶段一开始持续维护 |
| Maven | 构建、测试、多模块管理 | 阶段一 |
| JUnit 5 | 单元测试 | 阶段一 |
| Testcontainers | MySQL 集成测试 | 阶段四 |
| Flyway | 数据库迁移 | 阶段四 |
| Docker | MySQL、本地依赖、CI 一致性 | 阶段四 |
| k6/JMeter | 并发与幂等压测 | 阶段五 |

## 最坏情况预案

- Java/Maven 未安装：验证脚本默认模式不崩溃，报告缺失工具；CI 使用严格模式保证仓库质量。
- Maven 依赖下载失败：保留最小模块边界，优先修复构建环境，不扩展业务代码。
- 路由表丢失：后续阶段设计只读修复脚本和异常工单，不做自动破坏性修复。
- 支付回调重复或乱序：依赖唯一索引、状态条件和幂等记录，不信任外部回调顺序。
- 代理连接泄漏：后续 `mini-proxy` 必须为事务、客户端断开、异常包返回设计连接释放路径。
