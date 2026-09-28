# 过滤器管道（自定义插件）

> 本文档为[项目主页](../README.md)的拆分章节；索引与快速开始见主页。

## 过滤器管道（自定义插件）

实现 `PreFilter` 或 `PostFilter` 并注册为 Spring Bean，即自动加入管道（按 `order` 排序）：

```java
@Component
@Order(300)   // 数字越小越先执行
public class ParamCheckFilter implements PreFilter {
    @Override
    public boolean doFilter(FilterContext ctx) {
        if (ctx.getArgs() == null || ctx.getArgs().length == 0) {
            ctx.setRejectStatus(400);
            ctx.setRejectReason("参数缺失");
            return false;   // 返回 false 短路，业务方法不会执行
        }
        return true;
    }
}
```

内置过滤器顺序（0.3.0 起可通过 `api.governance.filters.*` 关闭，或注册同类型 Bean 覆盖）：

| 阶段 | Order | 过滤器 | 职责 |
|------|-------|--------|------|
| 前置 | 1 | MetadataCollectorFilter | 信息采集（真实请求 URI/HTTP 方法，回退注解推导） |
| 前置 | 100 | TrafficStatisticsFilter | 流量统计（总请求数） |
| 前置 | 200 | RateLimitFilter | 限流判断 |
| 后置 | 400 | SlowMethodFilter | 记录耗时 + 更新统计 + 慢方法告警 |
| 后置 | 500 | LoggingFilter | 日志记录（响应情况） |

`FilterContext` 从 0.3.0 起提供真实 HTTP 请求信息：`getRequestUri()`（真实请求 URI，含路径变量实际值）、
`getClientIp()`（`X-Forwarded-For` → `X-Real-IP` → `remoteAddr`）、`getHttpMethod()`/`getPath()`（真实值优先）。
`clientIp` 可被请求头伪造，仅用于统计与告警展示，请勿作为安全决策依据。

---
