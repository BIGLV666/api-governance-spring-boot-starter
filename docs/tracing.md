# 分布式链路追踪

> 本文档为[项目主页](../README.md)的拆分章节；索引与快速开始见主页。

## 分布式链路追踪

> **0.4.0 起 Micrometer Tracing 与 OTLP exporter 为可选依赖**：宿主需要链路上报时请显式引入
> `io.micrometer:micrometer-tracing-bridge-otel` 与 `io.opentelemetry:opentelemetry-exporter-otlp`
>（版本随 Spring Boot BOM 管理）。未引入时治理能力不受影响，异步任务上下文传播自动退化为空实现。

Starter 内置 Micrometer Tracing、OpenTelemetry bridge 与 OTLP exporter。HTTP 请求、框架异步任务以及宿主已有的 Spring Kafka / Spring AMQP 组件会自动传播 W3C `traceparent`，业务代码不需要手动维护 `traceId`。

Kafka 和 RabbitMQ 依赖仍是可选的：宿主使用哪个中间件，就只激活哪个适配器。适配器只增强已有的 `KafkaTemplate`、`RabbitTemplate` 和监听容器，不创建或替换连接、序列化及监听配置。

本地开发不接 Collector 时无需配置。需要上报链路时只配置 OTLP 地址：

```yaml
spring:
  application:
    name: order-service

management:
  otlp:
    tracing:
      endpoint: http://otel-collector:4318/v1/traces
  tracing:
    sampling:
      probability: 0.1
```

默认能力可按需关闭：

```yaml
api:
  governance:
    tracing:
      enabled: true
      async-context-propagation: true
      kafka: true
      rabbit: true
```

日志格式可使用 Spring Boot 的关联字段：

```yaml
logging:
  pattern:
    correlation: "[${spring.application.name:},%X{traceId:-},%X{spanId:-}] "
```

MQ 的 `traceparent`、`tracestate`、`baggage` 由框架写入消息 Header；业务仍应独立维护 `messageId` 和 `correlationId`，不要使用 `traceId` 做消费幂等。

---
