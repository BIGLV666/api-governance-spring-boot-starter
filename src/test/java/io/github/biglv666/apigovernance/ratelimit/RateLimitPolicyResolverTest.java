package io.github.biglv666.apigovernance.ratelimit;

import io.github.biglv666.apigovernance.annotation.RateLimit;
import io.github.biglv666.apigovernance.config.ApiGovernanceProperties;
import io.github.biglv666.apigovernance.ratelimit.rules.InMemoryRateRuleStore;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限流策略解析器测试：注解优先级、全局默认回退、动态规则覆盖与 API 标识反查解释。
 *
 * <p>解析器是切面与管理接口「解释端点」的单一事实来源，本测试同时锁定 0.7.0 的
 * 优先级语义，避免 0.8.0 重构引入行为漂移。
 *
 * @author API Governance Team
 * @since 0.8.0
 */
class RateLimitPolicyResolverTest {

    @RestController
    @RateLimit(limit = 10, window = 30, key = "#id")
    static class TestController {

        @RateLimit(limit = 3)
        @GetMapping("/strict")
        public String strict() {
            return "ok";
        }

        @RateLimit(limit = 5, window = 60, key = "#id")
        @GetMapping("/with-key")
        public String withKey(Long id) {
            return "ok";
        }

        @GetMapping("/inherited")
        public String inherited() {
            return "ok";
        }

        @GetMapping("/overloaded")
        public String overloaded() {
            return "ok";
        }

        @GetMapping("/overloaded")
        public String overloaded(Long id) {
            return "ok";
        }

        public String notMapped() {
            return "ok";
        }
    }

    private static Method method(String name, Class<?>... params) throws Exception {
        return TestController.class.getDeclaredMethod(name, params);
    }

    private static ApiGovernanceProperties properties(int defaultLimit, int defaultWindow) {
        ApiGovernanceProperties properties = new ApiGovernanceProperties();
        properties.getRateLimit().setDefaultLimit(defaultLimit);
        properties.getRateLimit().setDefaultWindow(defaultWindow);
        return properties;
    }

    @Test
    void methodAnnotationWinsOverClassAnnotation() throws Exception {
        RateLimitPolicy policy = RateLimitPolicyResolver.resolve(TestController.class,
                method("strict"), TestController.class.getName() + "#strict",
                properties(-1, 1), null);

        assertThat(policy.getAnnotationLimit()).isEqualTo(3);
        // 方法只声明 limit，window 回退全局默认（不继承类级 window，保持 0.7.0 语义）
        assertThat(policy.getAnnotationWindow()).isEqualTo(1);
        assertThat(policy.getAnnotationSource()).isEqualTo(RateLimitPolicy.Source.METHOD_ANNOTATION);
        assertThat(policy.getLimit()).isEqualTo(3);
        assertThat(policy.isEnabled()).isTrue();
    }

    @Test
    void classAnnotationAppliesToUnannotatedMethod() throws Exception {
        RateLimitPolicy policy = RateLimitPolicyResolver.resolve(TestController.class,
                method("inherited"), TestController.class.getName() + "#inherited",
                properties(-1, 1), null);

        assertThat(policy.getAnnotationLimit()).isEqualTo(10);
        assertThat(policy.getAnnotationWindow()).isEqualTo(30);
        assertThat(policy.getAnnotationSource()).isEqualTo(RateLimitPolicy.Source.CLASS_ANNOTATION);
        assertThat(policy.getKeyExpression()).isEqualTo("#id");
    }

    @Test
    void globalDefaultAppliesWhenNoAnnotation() {
        ApiGovernanceProperties properties = properties(200, 5);
        RateLimitPolicy policy = RateLimitPolicyResolver.resolve(Object.class, null,
                "com.x.Other#get", properties, null);

        assertThat(policy.getAnnotationLimit()).isEqualTo(200);
        assertThat(policy.getAnnotationWindow()).isEqualTo(5);
        assertThat(policy.getAnnotationSource()).isEqualTo(RateLimitPolicy.Source.GLOBAL_DEFAULT);
        assertThat(policy.getLimit()).isEqualTo(200);
        assertThat(policy.isEnabled()).isTrue();
    }

    @Test
    void minusOneGlobalLimitMeansUnlimited() {
        RateLimitPolicy policy = RateLimitPolicyResolver.resolve(null, null,
                "com.x.Other#get", properties(-1, 1), null);

        assertThat(policy.isAnnotationEnabled()).isFalse();
        assertThat(policy.isEnabled()).isFalse();
        assertThat(policy.getSource()).isEqualTo(RateLimitPolicy.Source.NONE);
    }

    @Test
    void dynamicRuleOverridesAnnotationAndReportsSource() throws Exception {
        InMemoryRateRuleStore store = new InMemoryRateRuleStore(10);
        String apiKey = TestController.class.getName() + "#strict";
        store.put(apiKey, 100, 60);

        RateLimitPolicy policy = RateLimitPolicyResolver.resolve(TestController.class,
                method("strict"), apiKey, properties(-1, 1), store);

        assertThat(policy.getAnnotationLimit()).isEqualTo(3);
        assertThat(policy.getLimit()).isEqualTo(100);
        assertThat(policy.getWindow()).isEqualTo(60);
        assertThat(policy.getSource()).isEqualTo(RateLimitPolicy.Source.DYNAMIC_RULE);
        assertThat(policy.getDynamicRule()).isNotNull();
        assertThat(policy.getDynamicRule().getLimit()).isEqualTo(100);
    }

    @Test
    void dynamicRuleMinusOneDisablesLimit() throws Exception {
        InMemoryRateRuleStore store = new InMemoryRateRuleStore(10);
        String apiKey = TestController.class.getName() + "#strict";
        store.put(apiKey, -1, 60);

        RateLimitPolicy policy = RateLimitPolicyResolver.resolve(TestController.class,
                method("strict"), apiKey, properties(-1, 1), store);

        assertThat(policy.isEnabled()).isFalse();
        assertThat(policy.getLimit()).isEqualTo(-1);
        assertThat(policy.getSource()).isEqualTo(RateLimitPolicy.Source.DYNAMIC_RULE);
    }

    @Test
    void spelKeyExpressionComesFromAnnotation() throws Exception {
        RateLimitPolicy policy = RateLimitPolicyResolver.resolve(TestController.class,
                method("withKey", Long.class), TestController.class.getName() + "#withKey",
                properties(-1, 1), null);

        assertThat(policy.getKeyExpression()).isEqualTo("#id");
        assertThat(policy.getAnnotationLimit()).isEqualTo(5);
        assertThat(policy.getAnnotationWindow()).isEqualTo(60);
    }

    @Test
    void resolveForApiKeyFindsClassAndMethod() {
        String apiKey = TestController.class.getName() + "#strict";
        RateLimitPolicyResolver.ApiKeyResolution resolution =
                RateLimitPolicyResolver.resolveForApiKey(apiKey, properties(-1, 1), null);

        assertThat(resolution.classResolved()).isTrue();
        assertThat(resolution.methodResolved()).isTrue();
        assertThat(resolution.ambiguous()).isFalse();
        assertThat(resolution.message()).isNull();
        assertThat(resolution.policy().getAnnotationLimit()).isEqualTo(3);
    }

    @Test
    void resolveForApiKeyAcceptsParameterList() {
        String apiKey = TestController.class.getName() + "#withKey(Long)";
        RateLimitPolicyResolver.ApiKeyResolution resolution =
                RateLimitPolicyResolver.resolveForApiKey(apiKey, properties(-1, 1), null);

        assertThat(resolution.classResolved()).isTrue();
        assertThat(resolution.methodResolved()).isTrue();
        assertThat(resolution.policy().getAnnotationLimit()).isEqualTo(5);
        assertThat(resolution.policy().getKeyExpression()).isEqualTo("#id");
    }

    @Test
    void resolveForApiKeyFlagsOverloadAmbiguity() {
        String apiKey = TestController.class.getName() + "#overloaded";
        RateLimitPolicyResolver.ApiKeyResolution resolution =
                RateLimitPolicyResolver.resolveForApiKey(apiKey, properties(-1, 1), null);

        assertThat(resolution.classResolved()).isTrue();
        assertThat(resolution.methodResolved()).isTrue();
        assertThat(resolution.ambiguous()).isTrue();
        assertThat(resolution.message()).contains("同名");
    }

    @Test
    void resolveForApiKeyDegradesOnUnknownClassWithoutThrowing() {
        RateLimitPolicyResolver.ApiKeyResolution resolution =
                RateLimitPolicyResolver.resolveForApiKey("com.not.exists.Controller#get",
                        properties(50, 2), null);

        assertThat(resolution.classResolved()).isFalse();
        assertThat(resolution.methodResolved()).isFalse();
        assertThat(resolution.message()).contains("无法加载类");
        assertThat(resolution.policy().getAnnotationLimit()).isEqualTo(50);
    }

    @Test
    void resolveForApiKeyFallsBackToClassWhenMethodMissing() {
        String apiKey = TestController.class.getName() + "#notMapped";
        RateLimitPolicyResolver.ApiKeyResolution resolution =
                RateLimitPolicyResolver.resolveForApiKey(apiKey, properties(-1, 1), null);

        assertThat(resolution.classResolved()).isTrue();
        assertThat(resolution.methodResolved()).isFalse();
        assertThat(resolution.message()).contains("未找到映射方法");
        // 无方法级注解时按类级解释
        assertThat(resolution.policy().getAnnotationLimit()).isEqualTo(10);
        assertThat(resolution.policy().getAnnotationSource())
                .isEqualTo(RateLimitPolicy.Source.CLASS_ANNOTATION);
    }

    @Test
    void resolveForApiKeyRejectsMalformedKey() {
        RateLimitPolicyResolver.ApiKeyResolution resolution =
                RateLimitPolicyResolver.resolveForApiKey("no-hash", properties(-1, 1), null);

        assertThat(resolution.classResolved()).isFalse();
        assertThat(resolution.message()).contains("格式应为");
    }
}
