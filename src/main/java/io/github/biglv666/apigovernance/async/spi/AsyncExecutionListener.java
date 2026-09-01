package io.github.biglv666.apigovernance.async.spi;

import io.github.biglv666.apigovernance.async.AsyncHandlerInfo;
import io.github.biglv666.apigovernance.async.event.AsyncEvent;

/**
 * 异步任务执行监听器 —— 观测异步 Handler 的执行结果（指标/审计插件挂载点）。
 *
 * <p>由 {@code AsyncDispatcher} 在调用线程上回调（成功/失败在异步线程，
 * 队列拒绝在提交线程），实现类必须快速返回、不得阻塞。
 * 三个回调均为 default 空实现，按需覆盖；监听器自身抛出的异常会被隔离并记 warn。
 *
 * <p>注意：{@code event} 只携带不可变快照（不含方法入参与返回值），请勿在回调中持有。
 *
 * @author API Governance Team
 * @since 0.5.0
 */
public interface AsyncExecutionListener {

    /**
     * 异步 Handler 执行成功。
     *
     * @param handler       Handler 元数据
     * @param event         事件快照
     * @param durationNanos 执行耗时（纳秒）
     */
    default void onSuccess(AsyncHandlerInfo handler, AsyncEvent event, long durationNanos) {
    }

    /**
     * 异步 Handler 执行抛出异常（异常已被异常处理器处理，此回调仅用于观测）。
     *
     * @param handler       Handler 元数据
     * @param event         事件快照
     * @param durationNanos 执行耗时（纳秒）
     */
    default void onFailure(AsyncHandlerInfo handler, AsyncEvent event, long durationNanos) {
    }

    /**
     * 异步任务提交被线程池拒绝（队列满且达到最大线程数）。
     *
     * @param handler Handler 元数据
     * @param event   事件快照
     */
    default void onRejected(AsyncHandlerInfo handler, AsyncEvent event) {
    }
}
