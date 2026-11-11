package io.github.biglv666.apigovernance.ratelimit.rules;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 动态限流规则存储插件接口（0.7.0 新增，0.8.0 扩展 TTL / 批量 / 健康）。
 *
 * <p>治理切面在每请求解析完注解 / yml 默认值后查询本接口，命中即覆盖限流参数。
 * 注册自定义 {@code @Bean RateRuleStore} 可完全替换内置存储（内置提供内存版与 Redis 版）。
 *
 * <h3>线程与性能契约</h3>
 * <ul>
 *   <li>{@link #findMatch} 位于请求热路径，必须无锁且不做任何远程调用；</li>
 *   <li>{@link #put} / {@link #remove} 由管理接口低频调用，可在内部做复制或远程写入；</li>
 *   <li>实现需保证写后读的可见性（最终一致即可）。</li>
 * </ul>
 *
 * <h3>0.8.0 兼容说明</h3>
 * <p>新增的 {@link #put(DynamicRateRule)}、{@link #putAll(Collection)} 与
 * {@link #replaceAll(Collection)} 均提供默认实现：只实现 0.7.0 的
 * {@link #put(String, int, int)} 的自定义存储仍可编译运行，但会丢失 TTL / 操作元数据
 * （规则仍然生效）。需要完整 TTL 语义的插件请覆盖 {@link #put(DynamicRateRule)}。
 *
 * @author API Governance Team
 * @since 0.7.0
 */
public interface RateRuleStore {

    /**
     * 查找 API 标识命中的最优动态规则（精确 &gt; 最长前缀），已过期规则不参与匹配。
     *
     * @param apiKey API 标识（全限定类名#方法名）
     * @return 命中的规则；无规则时为空
     */
    Optional<DynamicRateRule> findMatch(String apiKey);

    /**
     * 当前全部规则（管理接口展示用，返回只读快照）。
     *
     * <p>内置实现只返回未过期规则；自定义实现可自行决定是否过滤。
     *
     * @return pattern -&gt; 规则
     */
    Map<String, DynamicRateRule> all();

    /**
     * 新增或覆盖一条规则（同 pattern 后写覆盖前写）。
     *
     * @param pattern 匹配模式
     * @param limit   限流阈值（-1 不限流 / 0 封禁 / &gt;0 窗口上限）
     * @param window  时间窗口（秒）
     * @throws IllegalArgumentException 规则不合法或超出规则数上限
     * @throws IllegalStateException    远程存储写入失败（Redis 版）
     */
    void put(String pattern, int limit, int window);

    /**
     * 新增或覆盖一条完整规则（含 TTL / 操作元数据，0.8.0 新增）。
     *
     * <p>默认实现退化为 0.7.0 的三参写入，忽略 TTL 与元数据；但<b>不允许静默丢弃
     * 并发上限</b>：{@code maxConcurrent != 0} 时直接抛异常，避免管理接口报告成功、
     * 实际却没有并发控制。需要并发语义的自定义存储请覆盖本方法。
     *
     * @param rule 完整规则
     * @throws IllegalArgumentException 规则不合法、已过期、超出上限，
     *                                  或存储实现不支持 {@code maxConcurrent}
     * @throws IllegalStateException    远程存储写入失败（Redis 版）
     */
    default void put(DynamicRateRule rule) {
        Objects.requireNonNull(rule, "rule");
        if (rule.getMaxConcurrent() != 0) {
            throw new IllegalArgumentException("当前 RateRuleStore 实现不支持 maxConcurrent，"
                    + "请覆盖 put(DynamicRateRule) 或移除该字段");
        }
        put(rule.getPattern(), rule.getLimit(), rule.getWindow());
    }

    /**
     * 批量新增或覆盖规则（0.8.0 新增，导入接口使用）。
     *
     * <p>默认逐条调用 {@link #put(DynamicRateRule)}；内置存储覆盖为一次快照重建 /
     * 一次 Redis 批量写入，避免 N 次版本号自增。
     *
     * @param rules 规则集合
     * @throws IllegalArgumentException 规则不合法或超出规则数上限
     * @throws IllegalStateException    远程存储写入失败（Redis 版）
     */
    default void putAll(Collection<DynamicRateRule> rules) {
        for (DynamicRateRule rule : rules) {
            put(rule);
        }
    }

    /**
     * 删除一条规则；pattern 不存在时静默成功（幂等）。
     *
     * @param pattern 匹配模式
     * @throws IllegalStateException 远程存储写入失败（Redis 版）
     */
    void remove(String pattern);

    /**
     * 用给定规则集整体替换现有规则（0.8.0 新增，导入 replace 模式使用）。
     *
     * <p>默认实现先删除不在新集合中的 pattern，再 {@link #putAll(Collection)}。
     *
     * @param rules 新规则集
     * @throws IllegalArgumentException 规则不合法或超出规则数上限
     * @throws IllegalStateException    远程存储写入失败（Redis 版）
     */
    default void replaceAll(Collection<DynamicRateRule> rules) {
        Map<String, DynamicRateRule> incoming = new LinkedHashMap<>();
        for (DynamicRateRule rule : rules) {
            incoming.put(rule.getPattern(), rule);
        }
        for (String existing : all().keySet()) {
            if (!incoming.containsKey(existing)) {
                remove(existing);
            }
        }
        putAll(incoming.values());
    }

    /**
     * 当前未过期规则（管理接口展示用，0.8.0 新增）。
     *
     * @param nowMs 当前时间戳（毫秒）
     * @return pattern -&gt; 未过期规则
     */
    default Map<String, DynamicRateRule> activeRules(long nowMs) {
        Map<String, DynamicRateRule> active = new LinkedHashMap<>();
        for (Map.Entry<String, DynamicRateRule> entry : all().entrySet()) {
            if (!entry.getValue().isExpired(nowMs)) {
                active.put(entry.getKey(), entry.getValue());
            }
        }
        return active;
    }

    /**
     * 存储名称（日志与管理接口展示）。
     *
     * @return 如 {@code in-memory} / {@code redis}
     */
    String getName();

    /**
     * 存储当前是否健康（0.8.0 新增，供 Actuator 健康检查使用）。
     *
     * <p>默认恒为 true；Redis 实现按最近一次成功/失败刷新时间判断。
     *
     * @return true 表示健康
     */
    default boolean isHealthy() {
        return true;
    }

    /**
     * 最近一次成功刷新/写入的时间戳（毫秒，0 = 尚未成功）。
     *
     * @return 时间戳
     */
    default long getLastSuccessfulRefreshMs() {
        return 0L;
    }

    /**
     * 最近一次失败的时间戳（毫秒，0 = 从未失败）。
     *
     * @return 时间戳
     */
    default long getLastFailureMs() {
        return 0L;
    }
}
