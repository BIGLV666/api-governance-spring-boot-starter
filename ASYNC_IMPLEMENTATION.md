# 异步方法插件实现原理

本文面向使用者和维护者，解释 `@AsyncAction` / `@AsyncHandler` 从应用启动到运行期执行的完整流程，以及它与 Controller 全局治理链的关系。

API 和配置手册见 [ASYNC_ACTIONS.md](ASYNC_ACTIONS.md)。本文重点说明内部代码为什么这样组织。

## 一、能力边界

该功能是“目标方法生命周期上的异步旁路任务”，不是让目标方法本身异步。

```text
Spring @Async
调用者 -> 提交目标方法 -> 目标方法在线程池执行

本框架
调用者 -> 提交 BEFORE Handler -> 当前线程执行目标方法
       -> 提交成功/失败 Handler -> 提交完成 Handler
```

因此：

- `@AsyncAction` 方法的返回方式、事务声明和异常行为保持原样；
- `@AsyncHandler` 适合日志、通知、非关键统计等旁路逻辑；
- 鉴权、参数校验、加锁等必须在业务前完成的逻辑不能使用异步 Handler；
- 本地线程池不保证任务持久化，关键任务应使用 MQ 或事务 outbox。

## 二、核心结构

```text
async
├── annotation
│   ├── AsyncAction             动作触发注解
│   └── AsyncHandler            处理方法注解
├── aspect
│   └── AsyncActionAspect       方法生命周期入口
├── event
│   ├── AsyncPhase              四个生命周期阶段
│   ├── AsyncEvent              跨线程不可变事件
│   └── AsyncError              不持有 Throwable 的错误摘要
├── internal
│   ├── AsyncHandlerRegistry    启动扫描、校验、索引和排序
│   ├── AsyncEventFactory       调用事件增强器并生成快照
│   ├── AsyncDispatcher         查询 Handler 并提交 Executor
│   └── RegisteredAsyncHandler  缓存 Bean 和可调用 Method
└── spi
    ├── AsyncExecutorProvider
    ├── AsyncEventEnricher
    ├── AsyncHandlerExceptionHandler
    └── AsyncTaskRejectionHandler
```

整体分为两个阶段：

```text
应用启动：扫描 -> 校验 -> 建立索引 -> 排序 -> 缓存
方法调用：切面 -> 创建调用视图 -> 查索引 -> 创建快照 -> 提交线程池
```

运行期不会扫描 Spring Bean，也不会遍历所有 Handler。

## 三、两个切面为什么不会执行两次方法

Controller 方法可能同时被两个切面拦截：

- `GovernanceAspect`：全局 Controller 治理，执行限流、鉴权等 `PreFilter` 和日志、指标等 `PostFilter`；
- `AsyncActionAspect`：只匹配显式标记 `@AsyncAction` 的方法。

两个切面的顺序固定为：

```java
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class GovernanceAspect {
}

@Order(Ordered.HIGHEST_PRECEDENCE + 200)
public final class AsyncActionAspect {
}
```

Spring 中 order 数值越小越靠外，因此完整调用链是：

```text
GovernanceAspect.before
  -> PreFilter：元数据、流量统计、限流、鉴权、自定义插件
  -> AsyncActionAspect.before
       -> 提交 BEFORE Handler
       -> Controller 方法
       -> 提交 AFTER_SUCCESS 或 AFTER_ERROR Handler
       -> 提交 AFTER_COMPLETION Handler
  -> PostFilter：耗时、指标、日志、自定义插件
```

切面中的 `joinPoint.proceed()` 表示“进入责任链的下一个节点”，不是直接重新调用 Controller：

```text
GovernanceAspect.proceed()
    -> 进入 AsyncActionAspect

AsyncActionAspect.proceed()
    -> 进入真实 Controller 方法
```

所以两个切面各调用一次 `proceed()` 时，真实业务方法仍然只执行一次，全局过滤器链也只执行一次。

顺序契约带来以下行为：

| 场景 | 是否触发 AsyncAction |
|------|----------------------|
| 治理前置插件通过 | 是 |
| 被限流或鉴权 PreFilter 拒绝 | 否，调用链没有进入异步切面 |
| Controller 使用 `@Skip` | 是，`@Skip` 只跳过治理，外层仍会继续进入异步切面 |
| `api.governance.enabled=false` | 整个自动配置关闭，异步能力也不会装配 |
| `api.governance.async.enabled=false` | 治理照常执行，异步切面不装配 |

对于非 Controller 的 Service Bean，只有 `AsyncActionAspect` 匹配，直接执行异步生命周期链。

## 四、启动期 Handler 注册

`AsyncHandlerRegistry` 实现 `SmartInitializingSingleton`，在普通单例 Bean 创建完成后执行一次注册：

```java
public void afterSingletonsInstantiated() {
    Map<HandlerKey, List<RegisteredAsyncHandler>> discovered = new HashMap<>();
    for (String beanName : beanFactory.getBeanDefinitionNames()) {
        Class<?> beanType = beanFactory.getType(beanName, false);
        validateActions(beanName, beanType);
        registerHandlers(beanName, beanType, discovered);
    }
    discovered.values().forEach(list -> list.sort(HANDLER_ORDER));
    handlers = immutableCopy(discovered);
}
```

实际实现还会跳过 Spring、AspectJ 基础设施类型，并将结果复制为不可修改集合。

每个 Handler 使用如下键建立索引：

```java
private record HandlerKey(String action, AsyncPhase phase) {
}
```

例如：

```text
("user.login", BEFORE)        -> [recordAttempt]
("user.login", AFTER_SUCCESS) -> [saveLog, sendNotice]
("user.login", AFTER_ERROR)   -> [recordFailure]
```

注册时会校验：

- `@AsyncAction.value` 和 `@AsyncHandler.value` 不能为空；
- Action 和 Handler 方法必须是 `public`；
- Handler 必须返回 `void`；
- Handler 只能无参，或只接收一个 `AsyncEvent`；
- 代理 Bean 的 Method 必须可调用。

不合法的处理器会直接导致应用启动失败，避免运行到某次业务请求时才暴露配置错误。

处理器排序规则是：

```java
Comparator.comparingInt(handler -> handler.info().order())
        .thenComparing(handler -> handler.info().beanName())
        .thenComparing(handler -> handler.info().method());
```

`order` 相同后使用 Bean 名和方法签名保证确定性。这个顺序只用于提交任务，不保证线程池中的开始和完成顺序。

## 五、运行期四阶段分发

`AsyncActionAspect` 精确匹配带注解的方法：

```java
@Around("@annotation(...AsyncAction)")
public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
    dispatch(BEFORE);
    try {
        Object result = joinPoint.proceed();
        dispatch(AFTER_SUCCESS);
        return result;
    } catch (Throwable error) {
        dispatch(AFTER_ERROR);
        throw error;
    } finally {
        dispatch(AFTER_COMPLETION);
    }
}
```

一次方法调用生成一个 UUID，四个阶段共享这个 ID，便于日志和监控关联：

```text
id=8d... phase=BEFORE
id=8d... phase=AFTER_SUCCESS
id=8d... phase=AFTER_COMPLETION
```

成功调用的阶段是：

```text
BEFORE -> 目标方法 -> AFTER_SUCCESS -> AFTER_COMPLETION
```

失败调用的阶段是：

```text
BEFORE -> 目标方法抛异常 -> AFTER_ERROR -> AFTER_COMPLETION -> 原异常继续抛出
```

`BEFORE` 仅保证任务先提交。目标方法可能已经开始甚至结束，线程池中的 BEFORE Handler 才真正开始执行。因此它不是同步前置拦截器。

## 六、事件为什么分成调用视图和异步快照

方法参数、返回对象、`Throwable`、Servlet 请求和 AOP JoinPoint 可能存在以下问题：

- 对象可变，跨线程读取时状态不确定；
- 请求结束后 Servlet 对象可能失效；
- 队列持有大型参数或返回值会增加内存压力；
- 登录密码、Token 等敏感数据可能被意外传播；
- 原始异常可能间接持有较大的对象图。

因此实现分成两种对象。

`AsyncInvocation` 只存在于调用线程：

```text
包含 Method、参数、结果和原始 Throwable
只交给事件增强器提取数据
永远不提交到线程池
```

`AsyncEvent` 是跨线程不可变快照：

```java
public record AsyncEvent(
        String id,
        String action,
        AsyncPhase phase,
        String sourceClass,
        String sourceMethod,
        Instant startedAt,
        Instant occurredAt,
        long elapsedMillis,
        AsyncError error,
        Map<String, Object> data) {
}
```

构造时使用 `Map.copyOf(data)` 固定 Map 结构。它是浅拷贝，所以用户放入 `data` 的值也应当是不可变标量、业务 ID 或小型快照 DTO。

## 七、EventEnricher 的执行位置

框架默认不捕获参数和返回值。用户需要 `userId` 等业务信息时，注册 `AsyncEventEnricher`：

```java
@Bean
public AsyncEventEnricher loginEnricher() {
    return (builder, invocation) -> {
        if (!"user.login".equals(invocation.getAction())) {
            return;
        }
        LoginRequest request = (LoginRequest) invocation.getArguments()[0];
        builder.put("username", request.username());
    };
}
```

执行链是：

```text
业务调用线程
  -> EventEnricher 读取 Invocation
  -> AsyncEventBuilder 创建安全快照
  -> AsyncEvent
  -> 提交线程池
```

增强器在调用线程执行，因此必须轻量，不能进行数据库访问或网络调用，也不能保存 `AsyncInvocation` 引用。

多个增强器按照 Spring order 执行。单个增强器失败只记录错误，后续增强器和业务方法不受影响。

## 八、Dispatcher 和线程池

`AsyncDispatcher` 首先按 action 和 phase 做 O(1) 索引查询：

```java
List<RegisteredAsyncHandler> handlers = registry.getHandlers(
        invocation.getAction(), invocation.getPhase());
```

没有匹配 Handler 时直接返回，也不会构造 `AsyncEvent`。有 Handler 时只构造一次事件，然后按排序后的列表提交：

```java
AsyncEvent event = eventFactory.create(invocation);
for (RegisteredAsyncHandler handler : handlers) {
    executor.execute(() -> invoke(event, handler));
}
```

默认 `ThreadPoolTaskExecutor` 使用：

- 可配置 core/max pool size；
- 有界队列；
- `AbortPolicy` 明确拒绝，避免无声丢弃；
- 独立线程名前缀；
- 应用关闭时在限定时间内等待已提交任务。

线程池和队列的配置关系遵循 JDK `ThreadPoolExecutor`：

```text
核心线程未满 -> 创建核心线程
核心线程已满 -> 优先进入队列
队列已满且未到 maxPoolSize -> 创建额外线程
队列已满且线程数达到 maxPoolSize -> 拒绝
```

因此队列容量配置很大时，通常很难增长到 `max-pool-size`。应根据 Handler 延迟、请求峰值和可接受内存占用配置，而不是无边界放大队列。

## 九、异常隔离

框架在三个位置隔离插件失败：

```text
EventEnricher 失败
  -> 记录错误，继续构造事件

Executor 拒绝
  -> AsyncTaskRejectionHandler
  -> 不回抛给业务方法

Handler 执行失败
  -> AsyncHandlerExceptionHandler
  -> 不改变业务返回值或原始异常
```

默认异常和拒绝实现会记录 eventId、action、phase、Bean 和方法信息。用户可注册对应 SPI Bean 完全替换。

## 十、完整使用流程

### 1. 声明业务动作

```java
@Service
public class LoginService {

    @AsyncAction("user.login")
    public LoginResult login(LoginRequest request) {
        return loginRepository.login(request);
    }
}
```

动作应放在真正的业务边界。若登录还可能由 MQ 或定时任务触发，优先标在 Service，而不是只标在 Controller。

### 2. 声明处理器

```java
@Component
public class LoginHandlers {

    @AsyncHandler(value = "user.login", phase = AsyncPhase.AFTER_SUCCESS, order = 100)
    public void saveLoginLog(AsyncEvent event) {
        loginLogRepository.save(
                event.id(),
                (String) event.data().get("username"),
                event.occurredAt());
    }

    @AsyncHandler(value = "user.login", phase = AsyncPhase.AFTER_ERROR, order = 200)
    public void saveLoginFailure(AsyncEvent event) {
        AsyncError error = event.error();
        // 保存失败摘要
    }
}
```

### 3. 按需增加事件数据

```java
@Bean
public AsyncEventEnricher loginEventData() {
    return (builder, invocation) -> {
        if ("user.login".equals(invocation.getAction())
                && invocation.getArguments().length > 0) {
            LoginRequest request = (LoginRequest) invocation.getArguments()[0];
            builder.put("username", request.username());
        }
    };
}
```

不要提取密码、Token、完整请求体或大型实体。

### 4. 配置线程池

```yaml
api:
  governance:
    async:
      enabled: true
      core-pool-size: 4
      max-pool-size: 16
      queue-capacity: 1000
      keep-alive-seconds: 60
      thread-name-prefix: api-governance-async-
      await-termination-seconds: 5
```

### 5. 按需替换 SPI

```java
@Bean
public AsyncTaskRejectionHandler rejectionHandler() {
    return (event, handler, error) -> {
        // 记录指标或发送告警，不能依赖这里保证消息可靠投递
    };
}
```

## 十一、已知限制

- Spring AOP 同类自调用 `this.method()` 不触发 `@AsyncAction`；
- 只支持 Spring Bean 的公开方法；
- `order` 只保证提交顺序；
- 当前不支持同步 Handler、串行异步、重试、超时和任务返回值；
- 当前不支持 `AFTER_COMMIT`，方法正常返回不等于外层事务已提交；
- 本地线程池不提供宕机恢复和任务持久化；
- Controller 和 Service 同时声明同名 action 会产生两次独立事件，框架不会自动去重。

后续加入 `SERIAL` 或 `AFTER_COMMIT` 时，应继续复用 Registry、`AsyncEvent` 和 Dispatcher，不改变现有四阶段及失败隔离契约。
