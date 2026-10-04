package io.github.biglv666.apigovernance.alert.dedup;

/**
 * 集群告警去重闸门（0.7.0 新增）—— 多实例部署时保证同一告警全集群只分发一条。
 *
 * <p>抢占语义：{@link #tryAcquire} 返回 {@code true} 表示本实例抢到该告警的
 * 「分发权」（键此前不存在，已被占用并开始计时）；返回 {@code false} 表示
 * 窗口内已有其他实例分发过，本实例应静默丢弃。
 *
 * <h3>实现契约</h3>
 * <ul>
 *   <li>实现必须<b>线程安全</b>（多请求线程并发调用）；</li>
 *   <li>实现<b>必须 fail-open</b>：存储故障时返回 {@code true}（回退为各实例独立分发，
 *       与未开启去重一致），绝不允许因去重组件故障阻塞或吞掉告警；</li>
 *   <li>调用点位于「本机抑制已通过」之后的低频路径（每类型每窗口每实例至多一次），
 *       实现可承受一次远程往返。</li>
 * </ul>
 *
 * <p>注册自定义 {@code @Bean AlertDeduplicationGate} 可替换内置 Redis 实现。
 *
 * @author API Governance Team
 * @since 0.7.0
 */
public interface AlertDeduplicationGate {

    /**
     * 尝试抢占 {@code dedupKey} 在 {@code ttlMs} 窗口内的分发权。
     *
     * @param dedupKey 去重键（全局唯一命名空间，如 {@code type|apiKey}）
     * @param ttlMs    抢占保持时长（毫秒），窗口过后自动释放
     * @return true = 本实例获得分发权；false = 窗口内其他实例已分发
     */
    boolean tryAcquire(String dedupKey, long ttlMs);

    /**
     * 闸门名称（日志展示）。
     *
     * @return 如 {@code redis}
     */
    String getName();
}
