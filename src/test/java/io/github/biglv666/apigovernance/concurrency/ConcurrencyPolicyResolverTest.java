package io.github.biglv666.apigovernance.concurrency;

import io.github.biglv666.apigovernance.annotation.ConcurrencyLimit;
import io.github.biglv666.apigovernance.config.ApiGovernanceProperties;
import io.github.biglv666.apigovernance.ratelimit.rules.DynamicRateRule;
import io.github.biglv666.apigovernance.ratelimit.rules.InMemoryRateRuleStore;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发策略解析测试：注解优先级、全局默认、动态规则覆盖与关闭语义。
 *
 * @author API Governance Team
 * @since 1.0.0
 */
class ConcurrencyPolicyResolverTest {

    @RestController
    @ConcurrencyLimit(permits = 20)
    static class TestController {

        @ConcurrencyLimit(permits = 5)
        @GetMapping("/strict")
        public String strict() {
            return "ok";
        }

        @ConcurrencyLimit(permits = 0)
        @GetMapping("/off")
        public String off() {
            return "ok";
        }

        @GetMapping("/inherited")
        public String inherited() {
            return "ok";
        }
    }

    private static Method method(String name) throws Exception {
        return TestController.class.getDeclaredMethod(name);
    }

    private static ApiGovernanceProperties properties(int defaultPermits) {
        ApiGovernanceProperties properties = new ApiGovernanceProperties();
        properties.getConcurrencyLimit().setDefaultPermits(defaultPermits);
        return properties;
    }

    @Test
    void methodAnnotationWinsOverClass() throws Exception {
        ConcurrencyPolicy policy = ConcurrencyPolicyResolver.resolve(TestController.class,
                method("strict"), TestController.class.getName() + "#strict",
                properties(-1), null);

        assertThat(policy.isEnabled()).isTrue();
        assertThat(policy.getPermits()).isEqualTo(5);
        assertThat(policy.getSource()).isEqualTo(ConcurrencyPolicy.Source.METHOD_ANNOTATION);
    }

    @Test
    void classAnnotationAppliesToUnannotatedMethod() throws Exception {
        ConcurrencyPolicy policy = ConcurrencyPolicyResolver.resolve(TestController.class,
                method("inherited"), TestController.class.getName() + "#inherited",
                properties(-1), null);

        assertThat(policy.getPermits()).isEqualTo(20);
        assertThat(policy.getSource()).isEqualTo(ConcurrencyPolicy.Source.CLASS_ANNOTATION);
    }

    @Test
    void zeroAnnotationDisablesGlobalDefault() throws Exception {
        ConcurrencyPolicy policy = ConcurrencyPolicyResolver.resolve(TestController.class,
                method("off"), TestController.class.getName() + "#off",
                properties(50), null);

        assertThat(policy.isEnabled()).isFalse();
        assertThat(policy.getPermits()).isZero();
        assertThat(policy.getSource()).isEqualTo(ConcurrencyPolicy.Source.NONE);
    }

    @Test
    void globalDefaultAppliesWhenNoAnnotation() {
        ConcurrencyPolicy policy = ConcurrencyPolicyResolver.resolve(Object.class, null,
                "com.x.Other#get", properties(50), null);

        assertThat(policy.getPermits()).isEqualTo(50);
        assertThat(policy.getSource()).isEqualTo(ConcurrencyPolicy.Source.GLOBAL_DEFAULT);
    }

    @Test
    void dynamicRuleOverridesAnnotation() throws Exception {
        InMemoryRateRuleStore store = new InMemoryRateRuleStore(10);
        String apiKey = TestController.class.getName() + "#strict";
        store.put(DynamicRateRule.of(apiKey, 100, 60, null, "ops", "test", 3));

        ConcurrencyPolicy policy = ConcurrencyPolicyResolver.resolve(TestController.class,
                method("strict"), apiKey, properties(-1), store);

        assertThat(policy.getPermits()).isEqualTo(3);
        assertThat(policy.getSource()).isEqualTo(ConcurrencyPolicy.Source.DYNAMIC_RULE);
    }

    @Test
    void dynamicRuleMinusOneDisablesLimits() throws Exception {
        InMemoryRateRuleStore store = new InMemoryRateRuleStore(10);
        String apiKey = TestController.class.getName() + "#strict";
        store.put(DynamicRateRule.of(apiKey, 100, 60, null, "ops", "test", -1));

        ConcurrencyPolicy policy = ConcurrencyPolicyResolver.resolve(TestController.class,
                method("strict"), apiKey, properties(-1), store);

        assertThat(policy.isEnabled()).isFalse();
        assertThat(policy.getSource()).isEqualTo(ConcurrencyPolicy.Source.DYNAMIC_RULE);
    }

    @Test
    void dynamicRuleZeroDoesNotIntervene() throws Exception {
        InMemoryRateRuleStore store = new InMemoryRateRuleStore(10);
        String apiKey = TestController.class.getName() + "#strict";
        store.put(DynamicRateRule.of(apiKey, 100, 60));

        ConcurrencyPolicy policy = ConcurrencyPolicyResolver.resolve(TestController.class,
                method("strict"), apiKey, properties(-1), store);

        assertThat(policy.getPermits()).isEqualTo(5);
        assertThat(policy.getSource()).isEqualTo(ConcurrencyPolicy.Source.METHOD_ANNOTATION);
    }

    @Test
    void masterSwitchDisablesEverything() throws Exception {
        ApiGovernanceProperties properties = properties(50);
        properties.getConcurrencyLimit().setEnabled(false);

        ConcurrencyPolicy policy = ConcurrencyPolicyResolver.resolve(TestController.class,
                method("strict"), TestController.class.getName() + "#strict", properties, null);

        assertThat(policy.isEnabled()).isFalse();
        assertThat(policy.getSource()).isEqualTo(ConcurrencyPolicy.Source.NONE);
    }
}
