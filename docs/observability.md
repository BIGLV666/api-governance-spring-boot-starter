# 可观测性（内存指标 / Micrometer / Grafana / 告警）

> 本文档为[项目主页](../README.md)的拆分章节；索引与快速开始见主页。

## 内存指标统计

指标保存在内存中（随进程启动而创建、随进程关闭而销毁，不持久化）。为防止内存膨胀：

- 每个 API 的「最近请求记录」使用**有界滑动窗口**（`window-size` 条数上限 + `window-seconds` 时间上限）；
- 全局 API 数量上限 `max-apis`，超限按 LRU 淘汰。

记录内容：总请求数、成功/失败/拒绝数、慢方法数、最小/最大/平均耗时，以及最近请求明细。

---

---

## Micrometer 指标桥接（0.2.0 新增）

治理事件会自动同步为标准 Micrometer 指标（容器存在 `MeterRegistry` Bean 即生效，
`api.governance.metrics.micrometer-enabled` 可关闭），直接对接 Prometheus / Grafana 等生态：

| 指标 | 类型 | 标签 | 说明 |
|------|------|------|------|
| `api.governance.requests` | Counter | api, method, outcome | 请求总数；outcome ∈ success/error/reject |
| `api.governance.request.duration` | Timer | api, method | 请求耗时分布（不含被拒绝请求） |
| `api.governance.apis.tracked` | Gauge | 无 | 当前统计的 API 数量 |

```yaml
management:
  endpoints:
    web:
      exposure:
        include: prometheus   # 暴露 /actuator/prometheus
```

> `api` 标签 = `全限定类名#方法名`，基数上限为 Controller 方法数，无标签膨胀风险。
> Meter 一旦创建即常驻 Micrometer 注册表，清理需走 Micrometer 自身机制（内存注册表的 LRU
> 淘汰与 DELETE 指标清空不会同步删除 Meter）。

### Grafana 面板（0.6.0 新增）

仓库内置现成面板 [`grafana/api-governance-dashboard.json`](../grafana/api-governance-dashboard.json)
（10 个面板：治理请求总量 / 拒绝占比 / 异常占比 / API 数量、请求速率按 outcome 堆叠、
耗时 p50/p95/p99、被限流接口 Top 10、异步执行速率与线程池水位），已在真实
PaperWise 应用 + Prometheus + Grafana 链路上验证：

![Grafana 面板](../grafana/dashboard-screenshot.png)

**导入方式**：Grafana → Dashboards → Import → 上传 JSON，导入时选择你的 Prometheus
数据源（JSON 中数据源 uid 为 `prometheus-gov`，导入向导会提示替换）；顶部「应用」变量
按 `application` 标签过滤，多应用共用一个 Prometheus 时可直接切换。

**前置条件**：

```xml
<!-- 1. 引入 Prometheus 注册表（actuator 为 api-governance 的可选依赖，需显式声明） -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

```yaml
# 2. 暴露抓取端点
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus
  metrics:
    distribution:
      # 3. 分位面板（p50/p95/p99）需要 histogram 桶；不开启时该面板无数据，其余面板不受影响
      percentiles-histogram:
        api.governance.request.duration: true
```

---

---

## 告警插件（0.2.0 新增）

慢方法、限流拒绝、限流器故障三类事件可回调自定义通知器：

```java
@Component
public class MyAlertNotifier implements GovernanceAlertNotifier {

    @Override
    public void notify(GovernanceAlertEvent event) {
        // 事件只携带元数据：type / apiKey / path / elapsedMs / message / timestamp
        // 通知器在请求线程被同步调用，慢 IO 请自行异步化
    }
}
```

内置 `WebhookAlertNotifier`（零额外依赖，基于 JDK HttpClient 异步发送）可对接钉钉/企微/飞书机器人（0.3.0 起为原生消息格式，钉钉支持加签）：

```yaml
api:
  governance:
    alert:
      enabled: true
      suppress-interval-ms: 10000   # 同 (类型, apiKey) 10 秒内只发一次，防告警风暴
      webhook:
        enabled: true
        platform: dingtalk            # generic / dingtalk / wecom / feishu
        url: "https://oapi.dingtalk.com/robot/send?access_token=xxx"
        sign-secret: ${DINGTALK_SECRET}   # 钉钉机器人开启「加签」安全设置时必填
```

- `dingtalk`/`wecom`：发送 `{"msgtype":"text","text":{"content":...}}` 原生格式；
- `feishu`：发送 `{"msg_type":"text","content":{"text":...}}` 原生格式；
- `dingtalk` 且配置 `sign-secret` 时，自动按钉钉规范在 URL 追加 `timestamp` 与 `sign`（HMAC-SHA256 + Base64）；
- `generic`（默认）：框架自有 JSON 格式（0.2.0 行为），字段为完整事件元数据。

统一分发器（`AlertDispatcher`）负责告警风暴抑制与异常隔离：任一通知器抛出异常只记 warn 日志，
绝不影响业务请求。事件不含方法入参、返回值与异常堆栈，无敏感信息外泄风险。

**告警恢复通知（0.6.0 新增，`api.governance.alert.recovery-enabled` 默认开）**：
异常状况解除后自动补发恢复事件（`recovered=true`，携带抑制期内被静默丢弃的条数）：

- **限流器故障**：故障后首次成功获取配额即上报恢复（精确信号，如 Redis 恢复后第一个请求）；
- **慢方法 / 限流拒绝**：对应 (类型, apiKey) 安静超过一个抑制窗口后，由下一次告警分发
  惰性扫描补发恢复（系统完全安静时恢复通知会延迟到下一个事件到达）；
- 恢复事件本身不受抑制窗口约束；钉钉/企微/飞书文案以「【API治理恢复】」前缀区分。

**集群告警去重（0.7.0 新增，`api.governance.alert.cluster-dedup-enabled` 默认关）**：

抑制窗口是 JVM 内状态，多实例部署时同一告警会由每个实例各发一条 —— 10 个实例
= 10 条相同钉钉消息。开启后，本机抑制通过的事件再经 Redis `SET NX EX` 抢占集群分发权，
同一 `(告警类型, apiKey)` 每抑制窗口**全集群只发一条**；恢复通知同样去重（独立短窗口 30s）。

```yaml
api:
  governance:
    alert:
      cluster-dedup-enabled: true   # 需类路径存在 Spring Data Redis；独立于 rate-limit.type
```

- 抢占 TTL 与本机抑制窗口对齐（`suppress-interval-ms`）；未抢到的实例把事件计入本地
  抑制计数后丢弃，恢复语义沿用；
- **fail-open**：Redis 故障时回退为各实例独立分发（与未开启一致），告警绝不因去重组件
  故障而丢失；每分钟限频一条 warn；
- 注册自定义 `AlertDeduplicationGate` Bean 可替换内置 Redis 实现。

---

## Actuator 健康检查与 Info（0.8.0 新增）

类路径存在 `spring-boot-starter-actuator` 时自动注册（未引入则完全不装配，核心能力不受影响）：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
```

**`/actuator/health`** 暴露 `apiGovernance` 组件状态：

| 字段 | 说明 |
|------|------|
| `limiter.failureActive` | 限流器最近一次故障后是否尚未恢复（Redis 恢复后首个成功请求清零） |
| `limiter.lastSuccess` / `lastFailure` | 最近成功 / 失败时间戳 |
| `ruleStore.healthy` / `stale` | 规则存储健康状态；`stale` = 超过 `refresh-interval-ms × health-stale-multiplier` 未成功刷新 |
| `ruleStore.ruleCount` | 当前未过期规则数 |
| `asyncExecutor.saturated` | 异步线程池是否已饱和（仅告警，不判 DOWN） |
| `warnings` | 人类可读的降级提示列表 |

**UP / DOWN 判定遵循 fail-strategy，避免误触发 K8s 重启**：

- `fail-strategy=open`（默认，可用性优先）：限流器故障 / 规则存储陈旧仍返回 **UP**，只在
  `warnings` 中提示；
- `fail-strategy=close`（配额优先）：上述情况返回 **DOWN**，让探针按既定策略处置。

关闭健康指示器：`api.governance.management.health-enabled=false`。判定器异常时返回
`UNKNOWN` 而非抛出，绝不影响 `/actuator/health` 整体响应。

**`/actuator/info`** 暴露 `apiGovernance` 节点：组件版本、治理总开关、限流类型/算法/
默认值/故障策略/动态规则开关、告警与追踪开关 —— 用于线上确认实际生效配置（yml 拼写错误、
环境变量未注入时一眼可见）。

---
