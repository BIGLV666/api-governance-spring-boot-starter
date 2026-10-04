package io.github.biglv666.apigovernance.ratelimit.rules;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 内存版动态规则存储（0.7.0 新增）。
 *
 * <p>规则保存在进程内的不可变快照中（写复制、volatile 发布），读写无锁。
 * <b>单节点语义</b>：管理接口的写操作只影响接收请求的这一个实例 ——
 * 本机限流（{@code type=local}）模式下各实例规则互相独立；需要全集群
 * 一致请使用 Redis 限流（{@code type=redis}）配套的 {@code RedisRateRuleStore}。
 *
 * @author API Governance Team
 * @since 0.7.0
 */
public class InMemoryRateRuleStore implements RateRuleStore {

    /** 规则数上限（含前缀规则），超出后拒绝写入，防止误操作灌爆内存。 */
    private final int maxRules;

    /** 当前规则快照：volatile 发布，读无锁。 */
    private volatile RuleMatcher matcher = RuleMatcher.of(Map.of());

    /**
     * 构造内存规则存储。
     *
     * @param maxRules 规则数上限（至少 1）
     */
    public InMemoryRateRuleStore(int maxRules) {
        this.maxRules = Math.max(1, maxRules);
    }

    @Override
    public Optional<DynamicRateRule> findMatch(String apiKey) {
        return matcher.findMatch(apiKey);
    }

    @Override
    public Map<String, DynamicRateRule> all() {
        return matcher.rules();
    }

    @Override
    public void put(String pattern, int limit, int window) {
        DynamicRateRule rule = DynamicRateRule.of(pattern, limit, window);
        synchronized (this) {
            RuleMatcher current = matcher;
            boolean replacing = current.rules().containsKey(rule.getPattern());
            if (!replacing && current.size() >= maxRules) {
                throw new IllegalArgumentException(
                        "动态规则数已达上限 " + maxRules + "，请先删除无用规则或调大 "
                                + "api.governance.rate-limit.dynamic-rules.max-rules");
            }
            Map<String, DynamicRateRule> next = new HashMap<>(current.rules());
            next.put(rule.getPattern(), rule);
            matcher = RuleMatcher.of(next);
        }
    }

    @Override
    public void remove(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return;
        }
        String trimmed = pattern.trim();
        synchronized (this) {
            RuleMatcher current = matcher;
            if (current.rules().containsKey(trimmed)) {
                Map<String, DynamicRateRule> next = new HashMap<>(current.rules());
                next.remove(trimmed);
                matcher = RuleMatcher.of(next);
            }
        }
    }

    @Override
    public String getName() {
        return "in-memory";
    }
}
