package io.github.biglv666.apigovernance.ratelimit.rules;

import java.util.Map;
import java.util.Optional;

/**
 * 动态限流规则存储插件接口（0.7.0 新增）—— 「一切皆插件」在规则维度的落地。
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
 * @author API Governance Team
 * @since 0.7.0
 */
public interface RateRuleStore {

    /**
     * 查找 API 标识命中的最优动态规则（精确 &gt; 最长前缀）。
     *
     * @param apiKey API 标识（全限定类名#方法名）
     * @return 命中的规则；无规则时为空
     */
    Optional<DynamicRateRule> findMatch(String apiKey);

    /**
     * 当前全部规则（管理接口展示用，返回只读快照）。
     *
     * @return pattern -> 规则
     */
    Map<String, DynamicRateRule> all();

    /**
     * 新增或覆盖一条规则（同 pattern 后写覆盖前写）。
     *
     * @param pattern 匹配模式
     * @param limit   限流阈值（-1 不限流 / 0 封禁 / >0 窗口上限）
     * @param window  时间窗口（秒）
     * @throws IllegalArgumentException 规则不合法或超出规则数上限
     * @throws IllegalStateException    远程存储写入失败（Redis 版）
     */
    void put(String pattern, int limit, int window);

    /**
     * 删除一条规则；pattern 不存在时静默成功（幂等）。
     *
     * @param pattern 匹配模式
     * @throws IllegalStateException 远程存储写入失败（Redis 版）
     */
    void remove(String pattern);

    /**
     * 存储名称（日志与管理接口展示）。
     *
     * @return 如 {@code in-memory} / {@code redis}
     */
    String getName();
}
