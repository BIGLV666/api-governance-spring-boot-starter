package io.github.biglv666.apigovernance.management;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.biglv666.apigovernance.config.ApiGovernanceProperties;
import io.github.biglv666.apigovernance.filter.FilterChain;
import io.github.biglv666.apigovernance.metrics.MetricsRegistry;
import io.github.biglv666.apigovernance.ratelimit.rules.InMemoryRateRuleStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 动态限流规则管理端点测试：列表/提交/删除的参数校验、写开关、未启用降级。
 *
 * @author API Governance Team
 * @since 0.7.0
 */
class GovernanceManagementControllerRulesTest {

    private ApiGovernanceProperties properties;

    private InMemoryRateRuleStore store;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        properties = new ApiGovernanceProperties();
        store = new InMemoryRateRuleStore(2);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new GovernanceManagementController(properties, null,
                                new FilterChain(List.of(), List.of()),
                                new MetricsRegistry(100, 100, 300_000L), null, null, store))
                .build();
    }

    @Test
    void putRuleTakesEffectImmediately() throws Exception {
        mockMvc.perform(put("/api-governance/rate-limiter/rules")
                        .contentType("application/json")
                        .content("{\"pattern\":\"com.x.UserController#get\",\"limit\":100,\"window\":60}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.pattern").value("com.x.UserController#get"));

        mockMvc.perform(get("/api-governance/rate-limiter/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.store").value("in-memory"))
                .andExpect(jsonPath("$.rules[0].limit").value(100))
                .andExpect(jsonPath("$.rules[0].window").value(60));
    }

    @Test
    void putRuleDefaultsWindowToGlobalDefault() throws Exception {
        mockMvc.perform(put("/api-governance/rate-limiter/rules")
                        .contentType("application/json")
                        .content("{\"pattern\":\"com.x.A#get\",\"limit\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.window").value(1));
    }

    @Test
    void putRuleRejectsMissingPatternAndLimit() throws Exception {
        mockMvc.perform(put("/api-governance/rate-limiter/rules")
                        .contentType("application/json")
                        .content("{\"limit\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("缺少必填字段 pattern（字符串，支持尾部 * 通配）"));

        mockMvc.perform(put("/api-governance/rate-limiter/rules")
                        .contentType("application/json")
                        .content("{\"pattern\":\"com.x.A#get\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(put("/api-governance/rate-limiter/rules")
                        .contentType("application/json")
                        .content("{\"pattern\":\"com.x.A#get\",\"limit\":\"abc\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void putRuleRejectsInvalidRuleAndMaxRules() throws Exception {
        // 中间通配被 DynamicRateRule 校验拒绝
        mockMvc.perform(put("/api-governance/rate-limiter/rules")
                        .contentType("application/json")
                        .content("{\"pattern\":\"com.*.A#get\",\"limit\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));

        // 上限 2：第三条不同 pattern 被拒绝
        store.put("a.A#get", 1, 1);
        store.put("a.B#get", 1, 1);
        mockMvc.perform(put("/api-governance/rate-limiter/rules")
                        .contentType("application/json")
                        .content("{\"pattern\":\"a.C#get\",\"limit\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("上限")));
    }

    @Test
    void deleteRuleIsIdempotent() throws Exception {
        store.put("a.A#get", 1, 1);
        mockMvc.perform(delete("/api-governance/rate-limiter/rules")
                        .param("pattern", "a.A#get"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // 再删一次（已不存在）依然成功
        mockMvc.perform(delete("/api-governance/rate-limiter/rules")
                        .param("pattern", "a.A#get"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        mockMvc.perform(get("/api-governance/rate-limiter/rules"))
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void mutationsDisabledRejectsWritesButKeepsReads() throws Exception {
        properties.getManagement().setMutationsEnabled(false);
        mockMvc.perform(put("/api-governance/rate-limiter/rules")
                        .contentType("application/json")
                        .content("{\"pattern\":\"a.A#get\",\"limit\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("mutations-enabled=false")));

        mockMvc.perform(delete("/api-governance/rate-limiter/rules")
                        .param("pattern", "a.A#get"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(get("/api-governance/rate-limiter/rules"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void disabledStoreReportsGracefully() throws Exception {
        MockMvc withoutStore = MockMvcBuilders.standaloneSetup(
                        new GovernanceManagementController(properties, null,
                                new FilterChain(List.of(), List.of()),
                                new MetricsRegistry(100, 100, 300_000L), null, null, null))
                .build();

        for (var request : List.of(
                get("/api-governance/rate-limiter/rules"),
                put("/api-governance/rate-limiter/rules")
                        .contentType("application/json")
                        .content("{\"pattern\":\"a.A#get\",\"limit\":1}"),
                delete("/api-governance/rate-limiter/rules").param("pattern", "a.A#get"))) {
            withoutStore.perform(request)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.message").value(
                            org.hamcrest.Matchers.containsString("动态规则未启用")));
        }
    }

    @Test
    void rulesSurviveRoundTripThroughJson() throws Exception {
        store.put("a.A#get", 0, 15);
        String json = mockMvc.perform(get("/api-governance/rate-limiter/rules"))
                .andReturn().getResponse().getContentAsString();
        Map<?, ?> parsed = new ObjectMapper().readValue(json, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = (List<Map<String, Object>>) parsed.get("rules");
        org.assertj.core.api.Assertions.assertThat(rules).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(rules.get(0))
                .containsEntry("limit", 0)
                .containsEntry("window", 15);
    }
}
