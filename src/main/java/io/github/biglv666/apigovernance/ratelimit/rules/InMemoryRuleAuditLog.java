package io.github.biglv666.apigovernance.ratelimit.rules;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 进程内有界环形缓冲审计日志（0.8.0 新增）。
 *
 * <p>容量固定，写满后淘汰最旧条目；读写均加锁，但管理接口写入频率极低，
 * 不会成为热路径。{@code capacity <= 0} 时退化为空转实现（不记录）。
 *
 * @author API Governance Team
 * @since 0.8.0
 */
public class InMemoryRuleAuditLog implements RuleAuditLog {

    private final int capacity;
    private final Deque<RuleAuditEntry> entries;

    /**
     * 构造审计日志。
     *
     * @param capacity 容量上限（&lt;= 0 表示禁用）
     */
    public InMemoryRuleAuditLog(int capacity) {
        this.capacity = Math.max(0, capacity);
        this.entries = new ArrayDeque<>(Math.min(this.capacity, 1024));
    }

    @Override
    public synchronized void record(RuleAuditEntry entry) {
        if (entry == null || capacity == 0) {
            return;
        }
        if (entries.size() >= capacity) {
            entries.removeFirst();
        }
        entries.addLast(entry);
    }

    @Override
    public synchronized List<RuleAuditEntry> recent(int limit) {
        if (limit <= 0 || entries.isEmpty()) {
            return List.of();
        }
        int size = Math.min(limit, entries.size());
        List<RuleAuditEntry> result = new ArrayList<>(size);
        var iterator = entries.descendingIterator();
        while (iterator.hasNext() && result.size() < size) {
            result.add(iterator.next());
        }
        return result;
    }

    @Override
    public synchronized int size() {
        return entries.size();
    }

    @Override
    public int getCapacity() {
        return capacity;
    }

    @Override
    public String getName() {
        return capacity == 0 ? "disabled" : "in-memory";
    }
}
