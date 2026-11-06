package io.github.biglv666.apigovernance.ratelimit.rules;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 内存版动态规则存储（0.7.0 新增，0.8.0 增加 TTL / 批量写入）。
 *
 * <p>规则保存在进程内的不可变快照中（写复制、volatile 发布），读写无锁。
 * <b>单节点语义</b>：管理接口的写操作只影响接收请求的这一个实例 ——
 * 本机限流（{@code type=local}）模式下各实例规则互相独立；需要全集群
 * 一致请使用 Redis 限流（{@code type=redis}）配套的 {@code RedisRateRuleStore}。
 *
 * <h3>TTL 与过期清理</h3>
 * <p>读路径按需过滤过期规则（规则从不后台线程修改快照）；写路径（put / putAll /
 * replaceAll）顺带把过期规则从快照中剔除，因此内存占用始终不超过 {@code maxRules}
 * 条有效规则。无需额外清理线程。
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
        return matcher.activeRules(System.currentTimeMillis());
    }

    @Override
    public Map<String, DynamicRateRule> activeRules(long nowMs) {
        return matcher.activeRules(nowMs);
    }

    @Override
    public void put(String pattern, int limit, int window) {
        put(DynamicRateRule.of(pattern, limit, window));
    }

    @Override
    public void put(DynamicRateRule rule) {
        Objects.requireNonNull(rule, "rule");
        long now = System.currentTimeMillis();
        if (rule.isExpired(now)) {
            throw new IllegalArgumentException("规则已过期，expiresAt=" + rule.getExpiresAtMs());
        }
        synchronized (this) {
            Map<String, DynamicRateRule> active = currentActive(now);
            boolean replacing = active.containsKey(rule.getPattern());
            if (!replacing && active.size() >= maxRules) {
                throw maxRulesExceeded();
            }
            Map<String, DynamicRateRule> next = new HashMap<>(active);
            next.put(rule.getPattern(), rule);
            matcher = RuleMatcher.of(next);
        }
    }

    @Override
    public void putAll(Collection<DynamicRateRule> rules) {
        Objects.requireNonNull(rules, "rules");
        long now = System.currentTimeMillis();
        Map<String, DynamicRateRule> incoming = validateIncoming(rules, now);
        synchronized (this) {
            Map<String, DynamicRateRule> next = new HashMap<>(currentActive(now));
            next.putAll(incoming);
            ensureWithinLimit(next);
            matcher = RuleMatcher.of(next);
        }
    }

    @Override
    public void replaceAll(Collection<DynamicRateRule> rules) {
        Objects.requireNonNull(rules, "rules");
        long now = System.currentTimeMillis();
        Map<String, DynamicRateRule> incoming = validateIncoming(rules, now);
        ensureWithinLimit(incoming);
        synchronized (this) {
            matcher = RuleMatcher.of(incoming);
        }
    }

    @Override
    public void remove(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return;
        }
        String trimmed = pattern.trim();
        synchronized (this) {
            Map<String, DynamicRateRule> active = currentActive(System.currentTimeMillis());
            if (active.containsKey(trimmed)) {
                Map<String, DynamicRateRule> next = new HashMap<>(active);
                next.remove(trimmed);
                matcher = RuleMatcher.of(next);
            }
        }
    }

    @Override
    public String getName() {
        return "in-memory";
    }

    private Map<String, DynamicRateRule> currentActive(long nowMs) {
        return new LinkedHashMap<>(matcher.activeRules(nowMs));
    }

    private Map<String, DynamicRateRule> validateIncoming(Collection<DynamicRateRule> rules,
                                                          long nowMs) {
        Map<String, DynamicRateRule> incoming = new LinkedHashMap<>();
        for (DynamicRateRule rule : rules) {
            Objects.requireNonNull(rule, "rule");
            if (rule.isExpired(nowMs)) {
                throw new IllegalArgumentException(
                        "规则已过期: " + rule.getPattern() + ", expiresAt=" + rule.getExpiresAtMs());
            }
            incoming.put(rule.getPattern(), rule);
        }
        return incoming;
    }

    private void ensureWithinLimit(Map<String, DynamicRateRule> rules) {
        if (rules.size() > maxRules) {
            throw maxRulesExceeded();
        }
    }

    private IllegalArgumentException maxRulesExceeded() {
        return new IllegalArgumentException(
                "动态规则数已达上限 " + maxRules + "，请先删除无用规则或调大 "
                        + "api.governance.rate-limit.dynamic-rules.max-rules");
    }
}
