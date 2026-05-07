# AGENTS.md



## 项目简介

MiniDB-Lab 是一个用订单履约业务验证 MVCC、MySQL 协议代理、分片路由、事务一致性和数据库可观测能力的教学型数据库工程实验平台。



## 构建与运行指令

当前仓库仍处于文档规划阶段，尚未创建 Maven/Node 工程。后续约定命令如下：

```bash
mvn -B verify
mvn -pl mini-mvcc test
mvn -pl mini-proxy test
mvn -pl order-api spring-boot:run
npm run dev
npm run lint
```

正式代码落地后，必须补充并维护本地自动验证脚本，建议入口为 `tools/verify_local.py`，统一调度环境检查、构建、测试、lint 和关键信息收集。



## 代码规范

- Java 使用 Java 17，Maven 标准目录结构，缩进 4 空格，不使用 Tab。
- 包名按模块与上下文划分：`mini-mvcc`、`mini-proxy`、`order-api`、`web-console`。
- 类名使用 `UpperCamelCase`，方法和变量使用 `lowerCamelCase`，常量使用 `UPPER_SNAKE_CASE`。
- SQL 迁移必须通过 Flyway 版本化迁移脚本执行，禁止手工直接改生产结构。
- 后端 API 使用 Spring Boot、OpenAPI、JUnit 5、Testcontainers；代理使用 Netty；SQL 解析第一版优先 Druid SQL Parser。
- 前端如实施，使用 React + Ant Design Pro/ProComponents，定位为企业后台和数据库实验台，不做营销首页。
- 所有写接口必须有 `Idempotency-Key`，所有状态更新必须携带原状态条件。
- 所有外部输入必须验证、清洗、鉴权、审计；支付回调必须验签。



## 常用命令

```bash
# 环境检查
java -version
mvn -version
docker --version
mysql --version
node --version
npm --version

# 后端构建与测试
mvn -B verify
mvn -pl mini-mvcc test
mvn -pl mini-proxy test
mvn -pl order-api test

# 前端开发与检查
npm run dev
npm run lint
npm run test

# 数据库迁移
mvn -pl order-api flyway:migrate
mvn -pl order-api flyway:info
```



## 核心约束

- 项目定位是教学型实验平台，不是生产级数据库、生产级分库分表中间件或完整电商/ERP。
- 第一版只实现最小可运行链路：MVCC RC/RR、MySQL Proxy `COM_QUERY`、单库订单闭环、幂等、异常、可观测。
- 禁止跨分片事务，禁止自动跨分片 Join，缺少分片键的高风险查询必须拒绝。
- 真实订单数据使用 MySQL InnoDB，自研 MVCC 只作为实验引擎，不承载真实订单持久化。
- 数据库迁移必须通过迁移脚本执行，禁止绕过迁移系统直接改结构。
- 不允许改变技术栈、不允许重构架构、不允许扩展问题定义。
- GitHub 中编写和提交代码的只有用户本人；AI 只能建议、生成草案、解释和协助本地修改，不能自行提交或推送。



## 核心逻辑速览

- `mini-mvcc`：实现事务 ID、版本链、Undo Log、Read View、RC/RR 差异、写写冲突和回滚演示。
- `mini-proxy`：实现 MySQL 最小协议代理、握手、`COM_QUERY`、SQL 类型识别、读写分离、连接池、分片路由和事务绑定。
- `order-api`：实现创建订单、扣库存、支付回调、取消、履约发货、幂等、异常工单和 outbox。
- `sql`：保存 Flyway 迁移脚本、初始化数据、索引和一致性检查 SQL。
- `web-console`：实现运营总览、订单队列、履约工作台、异常中心和数据库实验观测台。
- `tests` 或 `test`：统一承载单元、集成、场景、并发和验收测试；测试目录不上传追踪。



## 安全与代码质量

- 所有用户输入、HTTP 请求、MySQL 协议包、SQL 条件、支付回调、AI 输出都视为不可信。
- 所有用户输入必须验证、清洗、限制长度和类型；SQL 必须参数化，禁止拼接用户输入。
- 提交前必须通过 lint、测试和构建，确保无已知漏洞。
- 并发代码必须说明锁、原子变量、事务边界、连接生命周期和失败释放策略。
- 内存安全重点关注 Netty `ByteBuf` 释放、连接池泄漏、事务上下文清理和大结果集限制。
- 依赖新增前必须解释用途、收益、风险和替代方案，并获得用户同意。
- 复杂功能如并发控制、内存监控、协议解析、安全策略，先给伪代码或设计思路，分析利弊，经确认后再实现。



## AI 协作原则

- 执行任何修改前，先读取 `AGENT_EXECUTION_PROTOCOL.md` 和 `AGENTS.md`。
- 修改前必须输出中文“变更前确认”：`GOAL_CONFIRMATION`、`IMPACT_ANALYSIS`、`RISK_LEVEL`，并等待用户明确批准。
- AI 可建议修改代码，但必须说明改动位置、原因、影响和验证方式。
- 重大重构、核心/安全模块、数据库结构、依赖变更、迁移策略变更必须先讨论。
- 遵循精准定位、最小修改原则，避免次生问题，注意依赖影响。
- 如操作可能导致破坏，必须建议备份或提供回退方案。



## 核心参考

涉及官方配置、API、SDK、协议或框架行为时，必须主动搜索并核对官方文档，将其作为实现和调用准则。优先参考：

- Java、Maven、Spring Boot、Netty、JUnit、Testcontainers 官方文档。
- MySQL、Flyway、OpenAPI、OpenTelemetry 官方文档。
- ShardingSphere、Vitess、ProxySQL、BusTub、PostgreSQL MVCC 相关官方资料。
- OWASP ASVS 与官方安全实践。

实现必须严格遵循官方参数定义、请求方式、返回值处理和最佳实践。



## 禁止删除

未经用户明确许可，AI 不得删除任何项目文件、库文件或配置文件。特别禁止自行删除：

- 数据库文件与清空库内容。
- 配置文件与密钥。
- 用户上传资源。
- 依赖锁定文件。
- 项目文档、源代码、测试和构建配置。



## 检测和设定

- 当前环境基线：Java 未检测到，Maven 未检测到，Docker 29.2.1，MySQL client 9.1.0，Node 25.2.1，npm 11.6.2，Git 2.53.0。
- 真实接口补充项：OpenAPI、Proxy 管理接口、MVCC 实验接口、Audit 查询接口。
- POC 验收标准：MVCC RC/RR 测试通过；Proxy 可执行 `SELECT 1`；订单闭环可运行；分片按 `user_id` 路由；幂等、outbox、异常和审计可查。

