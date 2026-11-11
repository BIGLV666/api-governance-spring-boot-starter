package io.github.biglv666.apigovernance.ratelimit.rules;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 0.7.0 自定义存储兼容测试：三参 put 的旧实现不得静默丢弃 maxConcurrent。
 *
 * @author API Governance Team
 * @since 1.0.0
 */
class RateRuleStoreCompatibilityTest {

    /** 只实现 0.7.0 三参 put 的存储。 */
    private static final RateRuleStore LEGACY = new RateRuleStore() {
        @Override
        public Optional<DynamicRateRule> findMatch(String apiKey) {
            return Optional.empty();
        }

        @Override
        public Map<String, DynamicRateRule> all() {
            return Map.of();
        }

        @Override
        public void put(String pattern, int limit, int window) {
            // 旧实现
        }

        @Override
        public void remove(String pattern) {
        }

        @Override
        public String getName() {
            return "legacy";
        }
    };

    @Test
    void legacyStoreRejectsMaxConcurrentInsteadOfSilentlyDroppingIt() {
        DynamicRateRule rule = DynamicRateRule.of("a.A#get", 1, 1, null, "ops", "test", 3);

        assertThatThrownBy(() -> LEGACY.put(rule))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxConcurrent");
    }

    @Test
    void legacyStoreStillAcceptsRulesWithoutMaxConcurrent() {
        DynamicRateRule rule = DynamicRateRule.of("a.A#get", 1, 1);

        assertThatCode(() -> LEGACY.put(rule)).doesNotThrowAnyException();
    }
}
