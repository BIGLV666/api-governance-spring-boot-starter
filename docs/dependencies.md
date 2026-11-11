# 依赖说明

> 本文档为[项目主页](../README.md)的拆分章节；索引与快速开始见主页。

| 依赖 | 作用 | 是否可选 |
|------|------|----------|
| spring-boot-starter-aspectj | AOP + 核心容器（Boot 4 起由 starter-aop 更名） | 否 |
| spring-web | @RestController 等 Web 注解 | 否 |
| jakarta.servlet-api | 管理接口鉴权过滤器（provided，运行期由宿主容器提供） | 否（不传递） |
| spring-boot-starter-actuator | Micrometer 指标桥接 + 健康指示器 / Info 贡献器（0.8.0 起；可选，未引入时不装配，治理能力不受影响） | 是 |
| micrometer-tracing-bridge-otel | OpenTelemetry bridge（0.4.0 起可选） | 是 |
| opentelemetry-exporter-otlp | OTLP 链路上报（0.4.0 起可选） | 是 |
| spring-kafka | Kafka 消息链路适配（宿主使用 Kafka 时激活） | 是 |
| spring-rabbit | RabbitMQ 消息链路适配（宿主使用 RabbitMQ 时激活） | 是 |
| spring-boot-configuration-processor | yml 配置元数据 | 是 |
| spring-boot-starter-data-redis | Redis 限流 | 是 |

> 未引入 `spring-boot-starter-web`（不捆绑内嵌容器）、未引入 Lombok，
> 告警 Webhook 基于 JDK 17 HttpClient（零第三方依赖），
> Redis 为可选依赖，仅在使用分布式限流或集群告警去重时引入。

## 兼容矩阵（1.0.0 起）

| 维度 | 范围 | 说明 |
|------|------|------|
| Spring Boot | 4.1.x | 基线 Spring Boot 4.1.2（Spring Framework 7、Jakarta EE 11、Jackson 3） |
| Java | 17 – 25 | 编译目标 17；CI 矩阵在 17 / 21 / 25 上全量回归 |
| 动态规则存储 | 随 `rate-limit.type` | local → 进程内快照（单节点）；redis → Redis Hash + 版本号（全集群） |
| 并发限制 | 进程内 | 本地信号量（单节点）；Redis 分布式租约不在 1.0.0 范围 |
| 集群告警去重 | 需 Spring Data Redis | `cluster-dedup-enabled=true` 且类路径有 Redis 时装配，独立于限流 type |

> **Boot 3 用户**：0.8.0 是最后一个 Boot 3.3–3.5 兼容版本；1.0.0 只支持 Boot 4.x。
> Boot 4 将 Jackson 升级到 Jackson 3（`tools.jackson.*`），宿主若直接依赖 Jackson 2
> 需要自行完成迁移（本 starter 主代码不直接依赖 Jackson）。
> Actuator 健康 API 迁移到 `org.springframework.boot.health.contributor`；
> MockMvc 测试自动配置迁移到 `spring-boot-starter-webmvc-test`。

---
