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
            new RuleMatcher(Map.of(), Map.of(), List.of());

    /** 原始规则映射（pattern 含尾部 *，展示与复制写用）。 */
    private final Map<String, DynamicRateRule> rules;

    /** 精确规则表：key = API 标识。 */
    private final Map<String, DynamicRateRule> exactRules;

    /** 前缀规则（按前缀长度降序，先命中者即最长前缀）。 */
    private final List<PrefixRule> prefixRules;

    private RuleMatcher(Map<String, DynamicRateRule> rules,
                        Map<String, DynamicRateRule> exactRules,
                        List<PrefixRule> prefixRules) {
        this.rules = rules;
        this.exactRules = exactRules;
        this.prefixRules = prefixRules;
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
        return new RuleMatcher(Map.copyOf(rules), Collections.unmodifiableMap(exact),
                List.copyOf(prefixes));
    }

    /**
     * 查找 API 标识命中的最优规则：精确 &gt; 最长前缀。
     *
     * @param apiKey API 标识
     * @return 命中的规则；无规则时为空
     */
    Optional<DynamicRateRule> findMatch(String apiKey) {
        DynamicRateRule exact = exactRules.get(apiKey);
        if (exact != null) {
            return Optional.of(exact);
        }
        for (PrefixRule candidate : prefixRules) {
            if (apiKey.startsWith(candidate.prefix())) {
                return Optional.of(candidate.rule());
            }
        }
        return Optional.empty();
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
