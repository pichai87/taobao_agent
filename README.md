# 电商经营数据分析 Agent

面向 Java / Agent 工程学习的企业架构项目。沿用既定的模块化单体、StateGraph、
语义口径、受控查询、确定性归因、审计和评测思路；不是更换成一个聊天 Demo。

**当前交付：可执行、可测试的后端主链。不是已经完成生产验收的企业系统。**
完整实施状态与上线门槛见 [实施状态](docs/实施状态.md)。

## 先运行

需要 JDK 21+、Maven 3.9.11+。当前工作区已下载 Maven 到上级 .tools，脚本会自动找到，
没有修改系统环境变量。当前机器实际用 JDK 25 编译，目标字节码为 Java 21。

在本目录的 PowerShell 中执行：

```powershell
./scripts/build.ps1
./scripts/start.ps1
```

另开一个终端：

```powershell
./scripts/smoke.ps1
```

默认只监听 127.0.0.1:8080，使用本机 H2 文件数据库和 OFFLINE_RULES（离线规则）规划器。
第一次启动由 Flyway 自动建表、装入 2696 笔合成订单与 270 条流量数据。
本机学习账号 analyst，密码 local-learning-only。**禁止把默认密码和 local 配置用于公开部署。**

测试命令为 `mvn verify`。macOS/Linux 用 Maven 构建后执行：
`java -jar ecom-agent-bootstrap/target/ecom-agent-bootstrap-0.1.0-SNAPSHOT.jar`。

## 你现在能使用什么

- GMV 查询、指定日期对比、品类贡献拆分、七日趋势、品类排序、GMV 定义。
- Spring AI Alibaba StateGraph 实际运行六个节点。
- SQL 模板白名单、参数绑定、查询超时、行数限制、独立读库连接。
- 任务持久化、幂等提交、确认执行、取消、进程中断后的手动恢复。
- HTTP 认证、CSRF、任务所有者隔离、REST、SSE 事件重放。
- 可选 Spring AI 模型意图分类适配器；没有配置密钥不会调用外部模型。

日期以请求中的 date / compareDate 为准，不会暗中从一句话猜日期。
当前分析指标只接受 GMV；指标字典中其他指标是口径说明，不意味着已经支持对应的自由分析。
TOP_N 当前是三个品类的完整排序，不支持任意 N。

## 从哪里开始学

1. [学习手册](docs/学习手册.md)：结合你已经学过的 JavaWeb、引用、集合逐步看代码。
2. [接口与演示](docs/接口与演示.md)：实际发送请求并观察结果。
3. [架构决策](docs/架构决策.md)：为什么这样拆层、模型能做什么。
4. [数据说明](docs/数据说明.md)：数据来源、指标公式、合成数据的局限。
5. [实施状态](docs/实施状态.md)：已完成、未完成、下次接着做哪里。
6. [运行与部署](docs/运行与部署.md)：本机、MySQL、模型配置与风险。
7. [验证记录](docs/验证记录.md)：实际执行过的检查，不以“配置文件存在”代替验证。

原四份计划保留在仓库上级；本文档记录落地状态，不覆盖原规划。

