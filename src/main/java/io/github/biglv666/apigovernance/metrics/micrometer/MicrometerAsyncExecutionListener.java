package io.github.biglv666.apigovernance.metrics.micrometer;

import io.github.biglv666.apigovernance.async.AsyncHandlerInfo;
import io.github.biglv666.apigovernance.async.event.AsyncEvent;
import io.github.biglv666.apigovernance.async.spi.AsyncExecutionListener;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 异步执行 Micrometer 桥接监听器 —— 把异步 Handler 的执行结果同步为标准指标。
 *
 * <h3>指标清单</h3>
 * <ul>
 *   <li>{@code api.governance.async.executions}（Counter，标签 action/outcome）：
 *       执行总数，outcome ∈ success / failure / rejected；</li>
 *   <li>{@code api.governance.async.execution.duration}（Timer，标签 action/outcome）：
 *       执行耗时分布（rejected 不计时）；</li>
 *   <li>{@code api.governance.async.pool.active} / {@code api.governance.async.pool.queue.size}
 *       （Gauge）：线程池活跃线程数与队列水位（仅内置 {@code ThreadPoolTaskExecutor} 可用）。</li>
 * </ul>
 *
 * <p>{@code action} 标签取值由应用声明的 action 名决定，请保持 action 数量有限，
 * 避免把参数值拼进 action 名导致标签基数膨胀。
 *
 * @author API Governance Team
 * @since 0.5.0
 */
public class MicrometerAsyncExecutionListener implements AsyncExecutionListener {

    /** 执行总数 Counter 名称。 */
    public static final String METRIC_ASYNC_EXECUTIONS = "api.governance.async.executions";

    /** 执行耗时 Timer 名称。 */
    public static final String METRIC_ASYNC_DURATION = "api.governance.async.execution.duration";

    private final MeterRegistry registry;

    /**
     * 构造监听器并（当执行器为内置线程池时）注册线程池水位 Gauge。
     *
     * @param registry       Micrometer 注册表（非空）
     * @param executorObjectProvider 框架异步执行器（可为 null：无内置线程池时不注册 Gauge）
     */
    public MicrometerAsyncExecutionListener(MeterRegistry registry, Executor executor) {
        this.registry = registry;
        registerPoolGauges(executor);
    }

    @Override
    public void onSuccess(AsyncHandlerInfo handler, AsyncEvent event, long durationNanos) {
        record(handler, event, "success", durationNanos);
    }

    @Override
    public void onFailure(AsyncHandlerInfo handler, AsyncEvent event, long durationNanos) {
        record(handler, event, "failure", durationNanos);
    }

    @Override
    public void onRejected(AsyncHandlerInfo handler, AsyncEvent event) {
        registry.counter(METRIC_ASYNC_EXECUTIONS,
                "action", event.action(), "outcome", "rejected").increment();
    }

    private void record(AsyncHandlerInfo handler, AsyncEvent event, String outcome, long durationNanos) {
        registry.counter(METRIC_ASYNC_EXECUTIONS,
                "action", event.action(), "outcome", outcome).increment();
        Timer.builder(METRIC_ASYNC_DURATION)
                .tag("action", event.action())
                .tag("outcome", outcome)
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /**
     * 注册线程池水位 Gauge（仅内置 ThreadPoolTaskExecutor 支持，自定义执行器跳过）。
     */
    private void registerPoolGauges(Executor executor) {
        if (!(executor instanceof ThreadPoolTaskExecutor taskExecutor)) {
            return;
        }
        ThreadPoolExecutor pool = taskExecutor.getThreadPoolExecutor();
        Gauge.builder("api.governance.async.pool.active", pool, ThreadPoolExecutor::getActiveCount)
                .description("Active threads of the api-governance async pool")
                .register(registry);
        Gauge.builder("api.governance.async.pool.queue.size", pool,
                        p -> p.getQueue().size())
                .description("Queued tasks of the api-governance async pool")
                .register(registry);
    }
}
