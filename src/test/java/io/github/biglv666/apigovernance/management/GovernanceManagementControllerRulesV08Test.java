package io.github.biglv666.apigovernance.management;

import io.github.biglv666.apigovernance.annotation.RateLimit;
import io.github.biglv666.apigovernance.config.ApiGovernanceProperties;
import io.github.biglv666.apigovernance.filter.FilterChain;
import io.github.biglv666.apigovernance.metrics.MetricsRegistry;
import io.github.biglv666.apigovernance.ratelimit.rules.InMemoryRateRuleStore;
import io.github.biglv666.apigovernance.ratelimit.rules.InMemoryRuleAuditLog;
import io.github.biglv666.apigovernance.ratelimit.rules.RuleAuditEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 0.8.0 规则管理端点测试：TTL/元数据、审计、导出、导入、dry-run 与生效规则解释。
 *
 * @author API Governance Team
 * @since 0.8.0
 */
class GovernanceManagementControllerRulesV08Test {

    @RestController
    @RateLimit(limit = 4, window = 20)
    static class EffectiveController {

        @GetMapping("/effective")
        public String effective() {
            return "ok";
        }
    }

    private ApiGovernanceProperties properties;

    private InMemoryRateRuleStore store;

    private InMemoryRuleAuditLog auditLog;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        properties = new ApiGovernanceProperties();
        store = new InMemoryRateRuleStore(20);
        auditLog = new InMemoryRuleAuditLog(50);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new GovernanceManagementController(properties, null,
                                new FilterChain(List.of(), List.of()),
                                new MetricsRegistry(100, 100, 300_000L), null, null,
                                store, auditLog))
                .build();
    }

    @Test
    void putRuleWithTtlAndMetadata() throws Exception {
        mockMvc.perform(put("/api-governance/rate-limiter/rules")
                        .header("X-Governance-Operator", "alice")
                        .contentType("application/json")
                        .content("{\"pattern\":\"com.x.A#get\",\"limit\":5,\"window\":10,"
                                + "\"ttlSeconds\":120,\"reason\":\"大促收紧\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.operator").value("alice"))
                .andExpect(jsonPath("$.reason").value("大促收紧"))
                .andExpect(jsonPath("$.expiresAt").isNumber())
                .andExpect(jsonPath("$.ttlSecondsRemaining").isNumber());

        assertThat(store.all().get("com.x.A#get")
                .getTtlSecondsRemaining(System.currentTimeMillis())).isBetween(1L, 120L);

        mockMvc.perform(get("/api-governance/rate-limiter/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.rules[0].operator").value("alice"))
                .andExpect(jsonPath("$.rules[0].reason").value("大促收紧"))
                .andExpect(jsonPath("$.rules[0].ttlSecondsRemaining").isNumber());
    }

    @Test
    void putRuleFallsBackToConfiguredDefaultTtl() throws Exception {
        properties.getRateLimit().getDynamicRules().setDefaultTtlSeconds(300);

        mockMvc.perform(put("/api-governance/rate-limiter/rules")
                        .contentType("application/json")
                        .content("{\"pattern\":\"a.A#get\",\"limit\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ttlSecondsRemaining").isNumber());

        assertThat(store.all().get("a.A#get")
                .getTtlSecondsRemaining(System.currentTimeMillis())).isBetween(1L, 300L);
    }

    @Test
    void putRuleRejectsNegativeTtl() throws Exception {
        mockMvc.perform(put("/api-governance/rate-limiter/rules")
                        .contentType("application/json")
                        .content("{\"pattern\":\"a.A#get\",\"limit\":1,\"ttlSeconds\":-1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("ttlSeconds")));
    }

    @Test
    void auditRecordsPutAndDeleteInReverseOrder() throws Exception {
        mockMvc.perform(put("/api-governance/rate-limiter/rules")
                        .header("X-Governance-Operator", "alice")
                        .contentType("application/json")
                        .content("{\"pattern\":\"a.A#get\",\"limit\":5,\"window\":10}"))
                .andExpect(jsonPath("$.success").value(true));
        mockMvc.perform(delete("/api-governance/rate-limiter/rules")
                        .header("X-Governance-Operator", "bob")
                        .param("pattern", "a.A#get"))
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api-governance/rate-limiter/rules/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.log").value("in-memory"))
                .andExpect(jsonPath("$.capacity").value(50))
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.entries[0].action").value("DELETE"))
                .andExpect(jsonPath("$.entries[0].operator").value("bob"))
                .andExpect(jsonPath("$.entries[0].beforeLimit").value(5))
                .andExpect(jsonPath("$.entries[1].action").value("PUT"))
                .andExpect(jsonPath("$.entries[1].operator").value("alice"))
                .andExpect(jsonPath("$.entries[1].afterLimit").value(5))
                .andExpect(jsonPath("$.entries[1].clientIp").isString());
    }

    @Test
    void auditClampsLimitToAtLeastOne() throws Exception {
        mockMvc.perform(put("/api-governance/rate-limiter/rules")
                        .contentType("application/json")
                        .content("{\"pattern\":\"a.A#get\",\"limit\":1}"))
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api-governance/rate-limiter/rules/audit").param("limit", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void exportReturnsStableFormat() throws Exception {
        store.put("a.A#get", 5, 10);
        store.put("a.B#get", 0, 60);

        mockMvc.perform(get("/api-governance/rate-limiter/rules/export"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.format").value("api-governance-rules-v1"))
                .andExpect(jsonPath("$.store").value("in-memory"))
                .andExpect(jsonPath("$.exportedAt").isNumber())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.rules.length()").value(2));
    }

    @Test
    void importMergeCreatesRulesAndAudits() throws Exception {
        mockMvc.perform(post("/api-governance/rate-limiter/rules/import")
                        .contentType("application/json")
                        .content("{\"mode\":\"merge\",\"rules\":["
                                + "{\"pattern\":\"a.A#get\",\"limit\":7,\"ttlSeconds\":60},"
                                + "{\"pattern\":\"a.B#get\",\"limit\":8}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.deleted").value(0));

        assertThat(store.all()).containsOnlyKeys("a.A#get", "a.B#get");
        assertThat(auditLog.recent(10))
                .hasSize(2)
                .allMatch(entry -> entry.action() == RuleAuditEntry.Action.IMPORT)
                .allMatch(RuleAuditEntry::success);
    }

    @Test
    void importDryRunDoesNotWriteOrAudit() throws Exception {
        mockMvc.perform(post("/api-governance/rate-limiter/rules/import")
                        .contentType("application/json")
                        .content("{\"dryRun\":true,\"rules\":["
                                + "{\"pattern\":\"a.A#get\",\"limit\":7}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.message").value(containsString("dry-run")));

        assertThat(store.all()).isEmpty();
        assertThat(auditLog.size()).isZero();
    }

    @Test
    void importReplaceDeletesRulesNotInPayload() throws Exception {
        store.put("a.Old#get", 1, 10);

        mockMvc.perform(post("/api-governance/rate-limiter/rules/import")
                        .contentType("application/json")
                        .content("{\"mode\":\"replace\",\"rules\":["
                                + "{\"pattern\":\"a.New#get\",\"limit\":2}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.deleted").value(1));

        assertThat(store.all()).containsOnlyKeys("a.New#get");
        // 被替换删除的旧规则同样产生审计
        assertThat(auditLog.recent(10))
                .anyMatch(entry -> "a.Old#get".equals(entry.pattern())
                        && entry.message().contains("removed by import replace"));
    }

    @Test
    void importRejectsWholeBatchWhenAnyRuleInvalid() throws Exception {
        store.put("a.Keep#get", 1, 10);

        mockMvc.perform(post("/api-governance/rate-limiter/rules/import")
                        .contentType("application/json")
                        .content("{\"rules\":["
                                + "{\"pattern\":\"a.Good#get\",\"limit\":1},"
                                + "{\"pattern\":\"a.Bad#get\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors[0].index").value(1))
                .andExpect(jsonPath("$.message").value(containsString("未写入任何规则")));

        assertThat(store.all()).containsOnlyKeys("a.Keep#get");
    }

    @Test
    void importRejectsWhenMaxRulesWouldBeExceeded() throws Exception {
        InMemoryRateRuleStore small = new InMemoryRateRuleStore(1);
        small.put("a.Existing#get", 1, 10);
        MockMvc smallMvc = MockMvcBuilders.standaloneSetup(
                        new GovernanceManagementController(properties, null,
                                new FilterChain(List.of(), List.of()),
                                new MetricsRegistry(100, 100, 300_000L), null, null,
                                small, auditLog))
                .build();

        smallMvc.perform(post("/api-governance/rate-limiter/rules/import")
                        .contentType("application/json")
                        .content("{\"rules\":[{\"pattern\":\"a.New#get\",\"limit\":1}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("上限")));
        assertThat(small.all()).containsOnlyKeys("a.Existing#get");
    }

    @Test
    void effectiveEndpointExplainsDynamicRuleOverride() throws Exception {
        String apiKey = EffectiveController.class.getName() + "#effective";
        store.put(apiKey, 7, 15);

        mockMvc.perform(get("/api-governance/rate-limiter/rules/effective")
                        .param("apiKey", apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.classResolved").value(true))
                .andExpect(jsonPath("$.methodResolved").value(true))
                .andExpect(jsonPath("$.annotation.limit").value(4))
                .andExpect(jsonPath("$.annotation.source").value("class-annotation"))
                .andExpect(jsonPath("$.effective.limit").value(7))
                .andExpect(jsonPath("$.effective.window").value(15))
                .andExpect(jsonPath("$.effective.enabled").value(true))
                .andExpect(jsonPath("$.effective.source").value("dynamic-rule"))
                .andExpect(jsonPath("$.dynamicRule.pattern").value(apiKey))
                .andExpect(jsonPath("$.store").value("in-memory"));
    }

    @Test
    void effectiveEndpointDegradesForUnknownClass() throws Exception {
        properties.getRateLimit().setDefaultLimit(33);
        properties.getRateLimit().setDefaultWindow(9);

        mockMvc.perform(get("/api-governance/rate-limiter/rules/effective")
                        .param("apiKey", "com.not.exists.Controller#get"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.classResolved").value(false))
                .andExpect(jsonPath("$.effective.limit").value(33))
                .andExpect(jsonPath("$.effective.window").value(9))
                .andExpect(jsonPath("$.effective.source").value("global-default"));
    }
}
