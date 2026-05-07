# MiniDB-Lab / sql_demo

MiniDB-Lab 是一个用订单履约业务验证 MVCC、MySQL 协议代理、分片路由、事务一致性和数据库可观测能力的教学型数据库工程实验平台。

## 项目定位

本项目不是生产级数据库、生产级分库分表中间件，也不是完整电商或 ERP 系统。它的目标是把三个平时割裂的知识面串起来：

- 数据库内部：MVCC、Undo Log、Read View、隔离级别和写写冲突。
- 数据库中间件：MySQL 协议代理、SQL 解析、读写分离、分片路由和事务绑定。
- 业务验证场景：订单、库存、支付、履约、幂等、outbox 和异常补偿。

## 核心模块规划

```text
sql_demo/
  mini-mvcc/     # 简易 MVCC 事务管理器
  mini-proxy/    # 简易 MySQL 协议代理
  order-api/     # 订单履约业务 API
  sql/           # Flyway 迁移、初始化数据和一致性检查 SQL
  web-console/   # 运营工作台与数据库实验观测台
```

当前仓库处于 0-1 规划阶段，代码工程骨架尚未创建。

## 推荐技术栈

- Java 17
- Maven
- Spring Boot
- Netty
- MySQL InnoDB
- Flyway
- JUnit 5 + Testcontainers
- React + Ant Design Pro

## 第一版目标

- `mini-mvcc` 跑通 RC/RR 可见性差异、版本链、回滚和写冲突。
- `mini-proxy` 支持最小 MySQL 握手、`COM_QUERY` 和 `SELECT 1` 转发。
- 订单单库闭环跑通创建订单、扣库存、支付回调、取消、履约发货。
- 所有写接口支持幂等。
- 数据库迁移全部通过 Flyway 脚本执行。
- 缺少分片键的高风险查询被拒绝。

## 明确不做

- 完整 SQL 数据库。
- 完整 MySQL 协议兼容。
- 跨分片事务。
- 自动跨分片 Join。
- 真实支付 SDK。
- 完整电商前台、ERP 或 WMS。

## 后续开发顺序

1. 补齐 JDK 17 和 Maven 3.9+。
2. 创建 Maven 多模块工程。
3. 建立本地自动验证脚本。
4. 实现 `mini-mvcc` POC。
5. 实现 `mini-proxy` POC。
6. 实现订单单库闭环。
7. 接入代理与分片。
8. 实现数据库实验观测台。

## 当前状态

当前仅上传公开入口说明和忽略规则。项目详细规划文档、AI 协作上下文和测试目录按项目要求不纳入 Git 追踪。

