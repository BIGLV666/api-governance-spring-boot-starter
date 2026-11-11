package io.github.biglv666.apigovernance.concurrency;

/**
 * 并发限制器插件接口（bulkhead）。
 *
 * <p>契约：
 * <ul>
 *   <li>{@link #tryAcquire(String, int)} 位于请求热路径，必须<b>非阻塞</b>、
 *       不做远程调用；拿不到名额立即返回 {@code null}；</li>
 *   <li>返回的 {@link ConcurrencyPermit} 必须在 {@code finally} 中释放；</li>
 *   <li>实现需保证同一 key 的并发数不超过给定 permits，异常/超时路径不得泄漏名额。</li>
 * </ul>
 *
 * <p>注册自定义 {@code ConcurrencyLimiter} Bean 可替换内置本地实现
 * （例如接入 Redis 分布式信号量）。
 *
 * @author API Governance Team
 * @since 1.0.0
 */
public interface ConcurrencyLimiter {

    /**
     * 尝试获取一个并发名额（非阻塞）。
     *
     * @param key     API 标识（限流键）
     * @param permits 并发上限（必须大于 0）
     * @return 成功返回许可句柄；达到上限返回 {@code null}
     */
    ConcurrencyPermit tryAcquire(String key, int permits);

    /**
     * 指定 key 当前在途请求数（无记录返回 0）。
     *
     * @param key API 标识
     * @return 在途请求数
     */
    int getActiveCount(String key);

    /**
     * 当前维护的 key 数量（观测用）。
     *
     * @return key 数量
     */
    int getKeyCount();

    /**
     * 限流器名称（日志与管理接口展示）。
     *
     * @return 名称
     */
    String getName();
}
