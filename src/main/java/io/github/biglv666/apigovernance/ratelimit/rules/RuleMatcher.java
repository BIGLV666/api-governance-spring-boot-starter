package io.github.biglv666.apigovernance.ratelimit.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 规则匹配结构 —— 一组动态规则的不可变快照（0.7.0 新增，包内部共用）。
 *
 * <p>构建时把规则拆为「精确表」与「前缀表」（按前缀长度降序），查询为
 * 一次 O(1) 哈希查找 + 至多 N 次前缀比较，全程无锁 —— 无论内存版还是
 * Redis 版存储，请求热路径读到的永远是构建好的整份快照，写操作复制重建。
 *
 * <p>同时保留原始 {@code pattern -> 规则} 映射（含尾部 *），供存储层复制写
 * 与管理接口展示，避免从拆分结构反推 pattern。
 *
 * <p>同一 pattern 重复提交按 upsert 语义去重（后写覆盖前写）。
 *
 * @author API Governance Team
 * @since 0.7.0
 */
final class RuleMatcher {

    private static final RuleMatcher EMPTY =
            new RuleMatcher(Map.of(), Map.of(), List.of(), false);

    /** 原始规则映射（pattern 含尾部 *，展示与复制写用）。 */
    private final Map<String, DynamicRateRule> rules;

    /** 精确规则表：key = API 标识。 */
    private final Map<String, DynamicRateRule> exactRules;

    /** 前缀规则（按前缀长度降序，先命中者即最长前缀）。 */
    private final List<PrefixRule> prefixRules;

    /**
     * 是否存在带 TTL 的规则（0.8.0 新增）。
     *
     * <p>无 TTL 规则时 {@link #findMatch(String, long)} 完全不读取时钟，
     * 保证「不使用 TTL」的场景与 0.7.0 一样零额外开销。
     */
    private final boolean hasExpiry;

    private RuleMatcher(Map<String, DynamicRateRule> rules,
                        Map<String, DynamicRateRule> exactRules,
                        List<PrefixRule> prefixRules,
                        boolean hasExpiry) {
        this.rules = rules;
        this.exactRules = exactRules;
        this.prefixRules = prefixRules;
        this.hasExpiry = hasExpiry;
    }

    /**
     * 从规则集合构建匹配快照。
     *
     * @param rules pattern -> 规则
     * @return 不可变匹配结构
     */
    static RuleMatcher of(Map<String, DynamicRateRule> rules) {
        if (rules.isEmpty()) {
            return EMPTY;
        }
        Map<String, DynamicRateRule> exact = new HashMap<>();
        List<PrefixRule> prefixes = new ArrayList<>(rules.size());
        for (Map.Entry<String, DynamicRateRule> entry : rules.entrySet()) {
            String pattern = entry.getKey();
            if (pattern.endsWith("*")) {
                prefixes.add(new PrefixRule(pattern.substring(0, pattern.length() - 1),
                        entry.getValue()));
            } else {
                exact.put(pattern, entry.getValue());
            }
        }
        // 前缀长度降序：遍历首个命中即最长前缀，无需全表扫完后比较
        prefixes.sort((a, b) -> Integer.compare(b.prefix.length(), a.prefix.length()));
        boolean hasExpiry = rules.values().stream()
                .anyMatch(rule -> rule.getExpiresAtMs() > 0);
        return new RuleMatcher(Map.copyOf(rules), Collections.unmodifiableMap(exact),
                List.copyOf(prefixes), hasExpiry);
    }

    /**
     * 查找 API 标识命中的最优规则：精确 &gt; 最长前缀（使用当前时钟）。
     *
     * @param apiKey API 标识
     * @return 命中的未过期规则；无规则或全部过期时为空
     */
    Optional<DynamicRateRule> findMatch(String apiKey) {
        // 无 TTL 规则时不读取时钟，热路径与 0.7.0 完全一致
        return findMatch(apiKey, hasExpiry ? System.currentTimeMillis() : 0L);
    }

    /**
     * 查找 API 标识命中的最优未过期规则：精确 &gt; 最长前缀。
     *
     * <p>精确规则过期后继续尝试前缀规则 —— 过期规则视为从未存在，
     * 不应阻塞更宽泛的兜底规则生效。
     *
     * @param apiKey API 标识
     * @param nowMs  当前时间戳（毫秒）
     * @return 命中的未过期规则；无规则或全部过期时为空
     */
    Optional<DynamicRateRule> findMatch(String apiKey, long nowMs) {
        DynamicRateRule exact = exactRules.get(apiKey);
        if (exact != null && !isExpired(exact, nowMs)) {
            return Optional.of(exact);
        }
        for (PrefixRule candidate : prefixRules) {
            if (!isExpired(candidate.rule(), nowMs)
                    && apiKey.startsWith(candidate.prefix())) {
                return Optional.of(candidate.rule());
            }
        }
        return Optional.empty();
    }

    /**
     * 当前未过期的规则快照（管理接口展示用）。
     *
     * @param nowMs 当前时间戳（毫秒）
     * @return pattern -&gt; 未过期规则
     */
    Map<String, DynamicRateRule> activeRules(long nowMs) {
        if (!hasExpiry) {
            return rules;
        }
        Map<String, DynamicRateRule> active = new HashMap<>(rules.size());
        for (Map.Entry<String, DynamicRateRule> entry : rules.entrySet()) {
            if (!isExpired(entry.getValue(), nowMs)) {
                active.put(entry.getKey(), entry.getValue());
            }
        }
        return Collections.unmodifiableMap(active);
    }

    /** 未过期规则数量（管理接口与健康检查用）。 */
    int activeSize(long nowMs) {
        if (!hasExpiry) {
            return rules.size();
        }
        int active = 0;
        for (DynamicRateRule rule : rules.values()) {
            if (!isExpired(rule, nowMs)) {
                active++;
            }
        }
        return active;
    }

    private boolean isExpired(DynamicRateRule rule, long nowMs) {
        return hasExpiry && rule.isExpired(nowMs);
    }

    /** 原始规则映射（不可变，pattern 含尾部 *）。 */
    Map<String, DynamicRateRule> rules() {
        return rules;
    }

    /** 规则总数（精确 + 前缀）。 */
    int size() {
        return rules.size();
    }

    /** 前缀条目：去 * 后的前缀与对应规则。 */
    private record PrefixRule(String prefix, DynamicRateRule rule) {
    }
}
