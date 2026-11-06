package io.github.biglv666.apigovernance;

import io.github.biglv666.apigovernance.config.ApiGovernanceAutoConfiguration;
import io.github.biglv666.apigovernance.health.ApiGovernanceHealthIndicator;
import io.github.biglv666.apigovernance.health.ApiGovernanceInfoContributor;
import io.github.biglv666.apigovernance.ratelimit.rules.InMemoryRuleAuditLog;
import io.github.biglv666.apigovernance.ratelimit.rules.RateRuleStore;
import io.github.biglv666.apigovernance.ratelimit.rules.RuleAuditEntry;
import io.github.biglv666.apigovernance.ratelimit.rules.RuleAuditLog;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.8.0 运维闭环装配测试：Actuator 健康/Info 按需装配、规则审计 Bean、启动种子加载。
 *
 * @author API Governance Team
 * @since 0.8.0
 */
class OpsClosureAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ApiGovernanceAutoConfiguration.class));

    @Test
    void actuatorBeansRegisteredByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(ApiGovernanceHealthIndicator.class);
            assertThat(ctx).hasSingleBean(ApiGovernanceInfoContributor.class);
        });
    }

    @Test
    void healthIndicatorDisabledByProperty() {
        runner.withPropertyValues("api.governance.management.health-enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(ApiGovernanceHealthIndicator.class);
                    assertThat(ctx).doesNotHaveBean(ApiGovernanceInfoContributor.class);
                });
    }

    @Test
    void auditLogUsesConfiguredCapacity() {
        runner.withPropertyValues("api.governance.rate-limit.dynamic-rules.audit-capacity=7")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(RuleAuditLog.class);
                    RuleAuditLog auditLog = ctx.getBean(RuleAuditLog.class);
                    assertThat(auditLog).isInstanceOf(InMemoryRuleAuditLog.class);
                    assertThat(auditLog.getCapacity()).isEqualTo(7);
                });
    }

    @Test
    void zeroAuditCapacityDisablesLog() {
        runner.withPropertyValues("api.governance.rate-limit.dynamic-rules.audit-capacity=0")
                .run(ctx -> {
                    RuleAuditLog auditLog = ctx.getBean(RuleAuditLog.class);
                    assertThat(auditLog.getCapacity()).isZero();
                    auditLog.record(new RuleAuditEntry(System.currentTimeMillis(),
                            RuleAuditEntry.Action.PUT, "a.A#get", null, null, 1, 10, 0L,
                            "ops", "", true, "test"));
                    assertThat(auditLog.size()).isZero();
                });
    }

    @Test
    void seedLocationLoadsRulesOnStartup() {
        runner.withPropertyValues(
                        "api.governance.rate-limit.dynamic-rules.seed-location="
                                + "classpath:rule-seed-test.yml")
                .run(ctx -> {
                    RateRuleStore store = ctx.getBean(RateRuleStore.class);
                    assertThat(store.all()).containsOnlyKeys(
                            "com.x.OrderController#create", "com.x.OrderController#read");
                    RuleAuditLog auditLog = ctx.getBean(RuleAuditLog.class);
                    assertThat(auditLog.size()).isEqualTo(2);
                    assertThat(auditLog.recent(10))
                            .allMatch(entry -> entry.action() == RuleAuditEntry.Action.SEED)
                            .allMatch(RuleAuditEntry::success);
                });
    }

    @Test
    void invalidSeedFailsStartupByDefault() {
        runner.withPropertyValues(
                        "api.governance.rate-limit.dynamic-rules.seed-location="
                                + "classpath:rule-seed-bad.yml")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void invalidSeedSkippedWhenFailFastDisabled() {
        runner.withPropertyValues(
                        "api.governance.rate-limit.dynamic-rules.seed-location="
                                + "classpath:rule-seed-bad.yml",
                        "api.governance.rate-limit.dynamic-rules.seed-fail-fast=false")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    RateRuleStore store = ctx.getBean(RateRuleStore.class);
                    assertThat(store.all()).containsOnlyKeys("com.x.Good#get");
                });
    }

    @Test
    void seedIgnoredWhenDynamicRulesDisabled() {
        runner.withPropertyValues(
                        "api.governance.rate-limit.dynamic-rules.enabled=false",
                        "api.governance.rate-limit.dynamic-rules.seed-location="
                                + "classpath:rule-seed-test.yml")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(RateRuleStore.class);
                });
    }
}
