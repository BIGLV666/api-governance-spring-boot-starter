# 依赖说明

> 本文档为[项目主页](../README.md)的拆分章节；索引与快速开始见主页。

## 依赖说明

| 依赖 | 作用 | 是否可选 |
|------|------|----------|
| spring-boot-starter-aop | AOP + 核心容器 | 否 |
| spring-web | @RestController 等 Web 注解 | 否 |
| jakarta.servlet-api | 管理接口鉴权过滤器（provided，运行期由宿主容器提供） | 否（不传递） |
| spring-boot-starter-actuator | Micrometer 指标桥接（可选，未引入时桥接 Bean 不装配，治理能力不受影响） | 是 |
| micrometer-tracing-bridge-otel | OpenTelemetry bridge（0.4.0 起可选） | 是 |
| opentelemetry-exporter-otlp | OTLP 链路上报（0.4.0 起可选） | 是 |
| spring-kafka | Kafka 消息链路适配（宿主使用 Kafka 时激活） | 是 |
| spring-rabbit | RabbitMQ 消息链路适配（宿主使用 RabbitMQ 时激活） | 是 |
| spring-boot-configuration-processor | yml 配置元数据 | 是 |
| spring-boot-starter-data-redis | Redis 限流 | 是 |

> 未引入 `spring-boot-starter-web`（不捆绑内嵌容器）、未引入 Lombok，
> 告警 Webhook 基于 JDK 17 HttpClient（零第三方依赖），
> Redis 为可选依赖，仅在使用分布式限流时引入。

---
