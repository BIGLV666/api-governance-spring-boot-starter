# 异步方法生命周期插件

> 本文档为[项目主页](../README.md)的拆分章节；索引与快速开始见主页。

## 异步方法生命周期插件

目标方法保持同步执行，框架只在其生命周期阶段提交附加异步任务：

```java
@Service
public class LoginService {

    @AsyncAction("user.login")
    public LoginResult login(LoginRequest request) {
        return doLogin(request);
    }
}

@Component
public class LoginHandlers {

    @AsyncHandler(value = "user.login", phase = AsyncPhase.AFTER_SUCCESS, order = 100)
    public void saveLoginLog(AsyncEvent event) {
        // 写 DB、发送通知或更新非关键统计
    }
}
```

支持 `BEFORE`、`AFTER_SUCCESS`、`AFTER_ERROR`、`AFTER_COMPLETION`。所有 Handler 默认异步且不改变原业务结果；`order` 只保证提交顺序，不保证完成顺序。跨线程只传递不可变事件快照，默认不捕获完整参数、返回值和原始异常。

完整使用方式、线程池替换、事件增强、安全边界与限制见 [ASYNC_ACTIONS.md](ASYNC_ACTIONS.md)。

### 异步可观测性（0.5.0 新增）

- **启动期防呆**：`@AsyncHandler` 引用不存在的 action 时启动失败（可用 `ignore-unmatched-handlers: true` 放行为 warn），拼写错误不再静默失效；
- **指标**：`api.governance.async.executions`（Counter）、`api.governance.async.execution.duration`（Timer）、`api.governance.async.pool.active` / `queue.size`（Gauge），存在 `MeterRegistry` 时自动注册；
- **告警**：任务被线程池队列拒绝时发布 `ASYNC_TASK_REJECTED` 告警（复用风暴抑制）；
- **管理端点**：`GET /async/handlers` 查看 Handler 注册清单，`GET /async/status` 查看线程池水位；
- **HTTP 上下文快照**：事件 `data` 默认携带当前请求的 `requestUri` / `httpMethod` / `clientIp`（可关）。

---
