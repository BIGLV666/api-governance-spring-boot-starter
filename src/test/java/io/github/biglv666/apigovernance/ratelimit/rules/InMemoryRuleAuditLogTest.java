package io.github.biglv666.apigovernance.ratelimit.rules;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 规则审计环形缓冲测试：容量淘汰、时间倒序、limit 边界与禁用语义。
 *
 * @author API Governance Team
 * @since 0.8.0
 */
class InMemoryRuleAuditLogTest {

    private static RuleAuditEntry entry(int index) {
        return new RuleAuditEntry(1000L + index, RuleAuditEntry.Action.PUT,
                "a.Api" + index + "#get", null, null, index, 10, 0L,
                "ops", "127.0.0.1", true, "ok");
    }

    @Test
    void recentReturnsNewestFirst() {
        InMemoryRuleAuditLog log = new InMemoryRuleAuditLog(10);
        log.record(entry(1));
        log.record(entry(2));
        log.record(entry(3));

        List<RuleAuditEntry> recent = log.recent(10);
        assertThat(recent).extracting(RuleAuditEntry::afterLimit)
                .containsExactly(3, 2, 1);
        assertThat(log.size()).isEqualTo(3);
    }

    @Test
    void capacityEvictsOldestEntries() {
        InMemoryRuleAuditLog log = new InMemoryRuleAuditLog(2);
        log.record(entry(1));
        log.record(entry(2));
        log.record(entry(3));

        assertThat(log.size()).isEqualTo(2);
        assertThat(log.recent(10)).extracting(RuleAuditEntry::afterLimit)
                .containsExactly(3, 2);
    }

    @Test
    void recentLimitIsBoundedByAvailableEntries() {
        InMemoryRuleAuditLog log = new InMemoryRuleAuditLog(10);
        log.record(entry(1));
        log.record(entry(2));

        assertThat(log.recent(1)).hasSize(1);
        assertThat(log.recent(1).get(0).afterLimit()).isEqualTo(2);
        assertThat(log.recent(0)).isEmpty();
        assertThat(log.recent(-5)).isEmpty();
    }

    @Test
    void zeroCapacityDisablesRecording() {
        InMemoryRuleAuditLog log = new InMemoryRuleAuditLog(0);
        log.record(entry(1));

        assertThat(log.size()).isZero();
        assertThat(log.recent(10)).isEmpty();
        assertThat(log.getName()).isEqualTo("disabled");
        assertThat(log.getCapacity()).isZero();
    }

    @Test
    void negativeCapacityIsTreatedAsDisabled() {
        InMemoryRuleAuditLog log = new InMemoryRuleAuditLog(-3);
        log.record(entry(1));

        assertThat(log.getCapacity()).isZero();
        assertThat(log.size()).isZero();
    }

    @Test
    void nullEntryIsIgnored() {
        InMemoryRuleAuditLog log = new InMemoryRuleAuditLog(5);
        log.record(null);

        assertThat(log.size()).isZero();
        assertThat(log.getName()).isEqualTo("in-memory");
    }
}
