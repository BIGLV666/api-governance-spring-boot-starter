# 后台管理接口

> 本文档为[项目主页](../README.md)的拆分章节；索引与快速开始见主页。

## 后台管理接口

基础路径默认 `/api-governance`（可配置），供管理工具调用：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/status` | 治理系统状态 |
| GET | `/config` | 当前配置 |
| GET | `/filters` | 过滤器链信息 |
| GET | `/rate-limiter/status` | 限流器状态 |
| GET | `/rate-limiter/count?key=` | 指定 key 当前计数 |
| POST | `/rate-limiter/reset?key=` | 重置指定 key |
| POST | `/rate-limiter/reset-all` | 重置全部限流 |
| GET | `/rate-limiter/rules` | 动态限流规则列表（0.7.0 新增） |
| PUT | `/rate-limiter/rules` | 新增/覆盖一条动态规则，立即生效（0.7.0 新增，JSON body） |
| DELETE | `/rate-limiter/rules?pattern=` | 删除一条动态规则（幂等，0.7.0 新增） |
| GET | `/metrics` | 全部 API 指标汇总（0.4.0 起支持 `page`/`size` 分页） |
| GET | `/async/handlers` | 异步 Handler 注册清单（0.5.0 新增） |
| GET | `/async/status` | 异步线程池水位与插件状态（0.5.0 新增） |
| GET | `/metrics/detail?key=` | 单 API 明细（含最近记录） |
| GET | `/metrics/slow?key=` | 单 API 慢方法列表 |
| GET | `/metrics/slow/all` | **所有 API 慢方法聚合（Map）** |
| DELETE | `/metrics` | 清空全部指标 |
| DELETE | `/metrics/single?key=` | 清空指定 API 指标 |

> `key` 即 API 唯一标识，格式为 `全限定类名#方法名`，例如 `com.example.UserController#get`；
> 类内存在同名重载映射方法时追加参数类型后缀，例如 `com.example.UserController#get(Long)`。
> `GET /config` 返回的配置已对敏感字段掩码（0.3.0 起）：`management.auth-token`、
> `alert.webhook.secret-token`、`alert.webhook.sign-secret` 非空时以 `******` 返回；
> `alert.webhook.url` 的 query 参数（可能携带机器人 access_token）同样以 `******` 掩码。
> 生产环境建议开启内置令牌鉴权（0.2.0 新增），或继续通过网关鉴权 / IP 白名单保护：

```yaml
api:
  governance:
    management:
      auth-token: ${GOVERNANCE_TOKEN}   # 通过环境变量注入，非空即启用鉴权
      auth-header: X-Governance-Token    # 请求头名称，可自定义
```

启用后，所有管理接口请求必须携带匹配的令牌请求头，否则返回 401（恒定时间比较，防时序侧信道）。
未配置令牌时行为与 0.1.0 完全一致。

**写操作开关（0.4.0 新增）**：`management.mutations-enabled: false` 可一键禁用全部变更类端点
（`POST /rate-limiter/reset*`、`PUT /rate-limiter/rules`、`DELETE /rate-limiter/rules`、
`DELETE /metrics*`），禁用时返回失败提示，只读端点不受影响，
适合只读监控场景。

**动态限流规则端点（0.7.0 新增）**：

```bash
# 提交规则（pattern 支持尾部 * 前缀通配；limit: -1=放开 / 0=封禁 / >0=上限；window 缺省取全局 default-window）
curl -X PUT http://host:8080/api-governance/rate-limiter/rules \
  -H "Content-Type: application/json" -H "X-Governance-Token: $GOVERNANCE_TOKEN" \
  -d '{"pattern": "com.x.OrderController#create", "limit": 100, "window": 60}'

# {"success": true, "message": "规则已生效", "pattern": "com.x.OrderController#create", "limit": 100, "window": 60}

# 查询全部规则（含存储类型 in-memory / redis 与写入时间）
curl http://host:8080/api-governance/rate-limiter/rules

# 删除规则（幂等：pattern 不存在同样返回成功）
curl -X DELETE "http://host:8080/api-governance/rate-limiter/rules?pattern=com.x.OrderController%23create"
```

> 参数校验失败（缺 pattern/limit、中间通配、window&lt;1、超出 max-rules 上限）与 Redis 存储写入
> 失败均返回 `{"success": false, "message": "..."}`，不会产生半写入状态。
> 语义与优先级详见[限流 → 动态限流规则](rate-limiting.md)。

**慢方法聚合接口示例**：

```bash
# 获取所有 API 的慢方法记录（一次性查看全部，无需逐个查询）
GET /api-governance/metrics/slow/all
```

返回格式：

```json
{
  "slowThresholdMs": 1000,
  "totalApis": 2,
  "slowRecords": {
    "com.example.UserController#getUser": [
      {
        "timestamp": 1704038400000,
        "elapsedMs": 1200,
        "success": true,
        "slow": true,
        "httpMethod": "GET",
        "path": "/api/user",
        "error": null
      }
    ],
    "com.example.OrderController#create": [
      {
        "timestamp": 1704038410000,
        "elapsedMs": 2500,
        "success": false,
        "slow": true,
        "httpMethod": "POST",
        "path": "/api/order",
        "error": "timeout"
      },
      {
        "timestamp": 1704038405000,
        "elapsedMs": 1500,
        "success": true,
        "slow": true,
        "httpMethod": "POST",
        "path": "/api/order",
        "error": null
      }
    ]
  }
}
```

> 每个方法的慢请求列表按**时间倒序**排列（最新的在前），包含时间戳、耗时、请求方式、路径、错误信息等完整数据。

---
