package io.github.biglv666.apigovernance.ratelimit.rules;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规则匹配器 TTL 语义测试：用注入时间替代 sleep，锁定「过期即视为从未存在」。
 *
 * @author API Governance Team
 * @since 0.8.0
 */
class RuleMatcherTtlTest {

    private static final long NOW = 1_700_000_000_000L;

    private static final long SECOND = 1000L;

    private static DynamicRateRule rule(String pattern, int limit, int window,
                                        long expiresAtMs) {
        return DynamicRateRule.restore(pattern, limit, window, NOW - 5_000L,
                expiresAtMs, "ops", "test");
    }

    @Test
    void expiredExactRuleFallsThroughToPrefixRule() {
        Map<String, DynamicRateRule> rules = new LinkedHashMap<>();
        rules.put("com.x.UserController#get", rule("com.x.UserController#get", 1, 10,
                NOW - SECOND));
        rules.put("com.x.*", rule("com.x.*", 9, 10, 0L));
        RuleMatcher matcher = RuleMatcher.of(rules);

        assertThat(matcher.findMatch("com.x.UserController#get", NOW))
                .map(DynamicRateRule::getLimit).contains(9);
    }

    @Test
    void unexpiredRuleStillWins() {
        Map<String, DynamicRateRule> rules = new LinkedHashMap<>();
        rules.put("com.x.UserController#get", rule("com.x.UserController#get", 1, 10,
                NOW + SECOND));
        rules.put("com.x.*", rule("com.x.*", 9, 10, 0L));
        RuleMatcher matcher = RuleMatcher.of(rules);

        assertThat(matcher.findMatch("com.x.UserController#get", NOW))
                .map(DynamicRateRule::getLimit).contains(1);
        assertThat(matcher.activeSize(NOW)).isEqualTo(2);
    }

    @Test
    void activeRulesDropsExpiredEntries() {
        Map<String, DynamicRateRule> rules = new LinkedHashMap<>();
        rules.put("a.A#get", rule("a.A#get", 1, 10, NOW - SECOND));
        rules.put("a.B#get", rule("a.B#get", 2, 10, 0L));
        RuleMatcher matcher = RuleMatcher.of(rules);

        assertThat(matcher.activeRules(NOW)).containsOnlyKeys("a.B#get");
        assertThat(matcher.activeSize(NOW)).isEqualTo(1);
        // 原始快照仍保留（供存储层复制写，读路径负责过滤）
        assertThat(matcher.rules()).hasSize(2);
    }

    @Test
    void allExpiredRulesYieldNoMatch() {
        Map<String, DynamicRateRule> rules = new LinkedHashMap<>();
        rules.put("a.A#get", rule("a.A#get", 1, 10, NOW - SECOND));
        RuleMatcher matcher = RuleMatcher.of(rules);

        assertThat(matcher.findMatch("a.A#get", NOW)).isEmpty();
        assertThat(matcher.activeRules(NOW)).isEmpty();
    }
}
