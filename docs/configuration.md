# 配置参考（application.yml）

> 本文档为[项目主页](../README.md)的拆分章节；索引与快速开始见主页。

## 配置（application.yml）

```yaml
api:
  governance:
    enabled: true                     # 治理总开关（默认 true）
    include-packages: []              # 治理范围包前缀（0.4.0 新增，空=全部）
    exclude-packages: []              # 排除的包前缀（0.4.0 新增，优先于 include）
    log:
      enabled: true                   # 日志总开关（默认 true）
      log-request-params: false       # 是否输出入参（默认 false，避免敏感信息）
      log-response: false             # 是否输出响应体（默认 false）
      slow-threshold-ms: 1000         # 慢方法阈值（毫秒，默认 1000）
    rate-limit:
      type: local                     # local=本机 / redis=分布式
      algorithm: token-bucket         # token-bucket / sliding-window / custom
      default-limit: -1               # 全局默认限流阈值（-1=不限制）
      default-window: 1               # 全局默认窗口（秒）
      max-entries: 10000              # 本机限流器最大键数量（SpEL 参数维度限流高基数时可调小）
      status-code: 429                # 限流拒绝的 HTTP 状态码
      message: "请求过于频繁，请稍后重试"  # 限流拒绝提示语
      fail-strategy: open             # 限流器故障降级：open=放行 / close=503 拒绝（作用于 Redis）
    filters:                          # 内置过滤器开关（0.3.0 新增），也可注册同类型 Bean 覆盖
      metadata-collector: true
      traffic-statistics: true
      rate-limit: true
      slow-method: true
      logging: true
    metrics:
      window-size: 100                # 每个 API 保留的最近记录条数
      window-seconds: 300             # 记录保留时长（秒）
      max-apis: 1000                  # 最大统计 API 数量（超限 LRU 淘汰）
      micrometer-enabled: true        # 指标桥接到 Micrometer（存在 MeterRegistry 时生效）
    alert:
      enabled: true                   # 告警总开关
      suppress-interval-ms: 10000     # 同 (类型, apiKey) 告警最小间隔（防风暴）
      recovery-enabled: true          # 0.6.0 新增：状况解除后补发恢复通知（携带抑制计数）
      webhook:
        enabled: false                # 内置 Webhook 通知器
        url: ""                       # webhook 地址（钉钉/企微/飞书机器人）
        timeout-ms: 3000
        platform: generic             # generic / dingtalk / wecom / feishu（0.3.0 新增）
        sign-secret: ""               # 钉钉加签密钥（仅 dingtalk 生效，建议 ${DINGTALK_SECRET} 注入）
        secret-token: ""              # 可选，以 X-Governance-Token 头携带
    management:
      enabled: true                   # 管理接口开关（默认 true）
      base-path: /api-governance      # 管理接口基础路径
      mutations-enabled: true         # 写操作开关（0.4.0 新增，false 时 reset/delete 端点返回失败）
      auth-token: ""                  # 非空时启用管理接口鉴权（建议 ${GOVERNANCE_TOKEN} 注入）
      auth-header: X-Governance-Token # 鉴权令牌请求头名称
    async:
      enabled: true                   # 异步方法生命周期插件开关
      core-pool-size: 2               # 独立线程池核心线程数
      max-pool-size: 8                # 独立线程池最大线程数
      queue-capacity: 1000            # 有界队列容量
      keep-alive-seconds: 60
      thread-name-prefix: api-governance-async-
      await-termination-seconds: 5
      ignore-unmatched-handlers: false # 0.5.0 新增：true 时 handler 引用未知 action 仅 warn（默认启动失败）
      web-context-enrichment: true     # 0.5.0 新增：事件 data 快照 requestUri/httpMethod/clientIp
```

> **说明**：默认 `default-limit: -1`（不限流），即「拦截但不限流」。若希望全局限流，
> 将 `default-limit` 设为正值即可；个别接口可用 `@RateLimit` 覆盖。

---
