package io.github.biglv666.apigovernance.ratelimit.rules;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 启动种子规则加载测试：YAML 解析、缺省值回填、批量写入与失败策略。
 *
 * @author API Governance Team
 * @since 0.8.0
 */
class RuleSeedLoaderTest {

    private static final String SEED = "classpath:rule-seed-test.yml";

    private final DefaultResourceLoader resourceLoader = new DefaultResourceLoader();

    @Test
    void loadsRulesFromYaml() {
        List<RuleSeedLoader.SeedRule> rules = RuleSeedLoader.load(SEED, resourceLoader);

        assertThat(rules).hasSize(2);
        assertThat(rules.get(0).getPattern()).isEqualTo("com.x.OrderController#create");
        assertThat(rules.get(0).getLimit()).isEqualTo(100);
        assertThat(rules.get(0).getWindow()).isEqualTo(60);
        assertThat(rules.get(0).getTtlSeconds()).isEqualTo(300L);
        assertThat(rules.get(0).getOperator()).isEqualTo("ops");
        assertThat(rules.get(0).getMaxConcurrent()).isEqualTo(4);
        assertThat(rules.get(1).getWindow()).isNull();
    }

    @Test
    void blankLocationYieldsEmptyList() {
        assertThat(RuleSeedLoader.load("", resourceLoader)).isEmpty();
        assertThat(RuleSeedLoader.load("   ", resourceLoader)).isEmpty();
        assertThat(RuleSeedLoader.load(null, resourceLoader)).isEmpty();
    }

    @Test
    void missingFileFailsFast() {
        assertThatThrownBy(() -> RuleSeedLoader.load("classpath:not-exists.yml", resourceLoader))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不存在");
    }

    @Test
    void applyWritesRulesWithDefaultsAndMetadata() {
        InMemoryRateRuleStore store = new InMemoryRateRuleStore(10);
        InMemoryRuleAuditLog auditLog = new InMemoryRuleAuditLog(10);

        int applied = RuleSeedLoader.apply(RuleSeedLoader.load(SEED, resourceLoader),
                10, 0L, store, auditLog, true);

        assertThat(applied).isEqualTo(2);
        DynamicRateRule create = store.all().get("com.x.OrderController#create");
        assertThat(create).isNotNull();
        assertThat(create.getLimit()).isEqualTo(100);
        assertThat(create.getWindow()).isEqualTo(60);
        assertThat(create.getExpiresAtMs()).isPositive();
        assertThat(create.getTtlSecondsRemaining(System.currentTimeMillis())).isPositive();
        assertThat(create.getOperator()).isEqualTo("ops");
        assertThat(create.getReason()).isEqualTo("大促临时收紧");
        assertThat(create.getMaxConcurrent()).isEqualTo(4);

        DynamicRateRule read = store.all().get("com.x.OrderController#read");
        assertThat(read).isNotNull();
        // 缺省 window 取 defaultWindow，缺省 operator 回填 seed，缺省 reason 回填 seed
        assertThat(read.getWindow()).isEqualTo(10);
        assertThat(read.getOperator()).isEqualTo("seed");
        assertThat(read.getReason()).isEqualTo("seed");

        assertThat(auditLog.size()).isEqualTo(2);
        assertThat(auditLog.recent(10))
                .allMatch(entry -> entry.action() == RuleAuditEntry.Action.SEED)
                .allMatch(RuleAuditEntry::success);
    }

    @Test
    void defaultTtlAppliesWhenSeedHasNone() {
        InMemoryRateRuleStore store = new InMemoryRateRuleStore(10);
        RuleSeedLoader.SeedRule seed = new RuleSeedLoader.SeedRule();
        seed.setPattern("a.A#get");
        seed.setLimit(1);

        int applied = RuleSeedLoader.apply(List.of(seed), 5, 120L, store, null, true);

        assertThat(applied).isEqualTo(1);
        DynamicRateRule rule = store.all().get("a.A#get");
        assertThat(rule.getWindow()).isEqualTo(5);
        assertThat(rule.getTtlSecondsRemaining(System.currentTimeMillis())).isBetween(1L, 120L);
    }

    @Test
    void invalidSeedFailsFastWhenConfigured() {
        InMemoryRateRuleStore store = new InMemoryRateRuleStore(10);
        RuleSeedLoader.SeedRule seed = new RuleSeedLoader.SeedRule();
        seed.setPattern("a.A#get");
        // 缺少 limit

        assertThatThrownBy(() -> RuleSeedLoader.apply(List.of(seed), 1, 0L, store, null, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("a.A#get");
        assertThat(store.all()).isEmpty();
    }

    @Test
    void invalidSeedIsSkippedWhenNotFailFast() {
        InMemoryRateRuleStore store = new InMemoryRateRuleStore(10);
        InMemoryRuleAuditLog auditLog = new InMemoryRuleAuditLog(10);
        RuleSeedLoader.SeedRule bad = new RuleSeedLoader.SeedRule();
        bad.setPattern("a.Bad#get");
        RuleSeedLoader.SeedRule good = new RuleSeedLoader.SeedRule();
        good.setPattern("a.Good#get");
        good.setLimit(5);

        int applied = RuleSeedLoader.apply(List.of(bad, good), 1, 0L, store, auditLog, false);

        assertThat(applied).isEqualTo(1);
        assertThat(store.all()).containsOnlyKeys("a.Good#get");
        assertThat(auditLog.recent(10))
                .anyMatch(entry -> !entry.success() && "a.Bad#get".equals(entry.pattern()));
    }
}
