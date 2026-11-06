package io.github.biglv666.apigovernance.ratelimit.rules;

import java.util.List;

/**
 * 动态规则变更审计日志插件（0.8.0 新增）。
 *
 * <p>默认实现为进程内有界环形缓冲（{@link InMemoryRuleAuditLog}），内存占用恒定；
 * 需要跨实例/持久化审计时注册自定义 {@code @Bean RuleAuditLog} 覆盖即可。
 *
 * <p>审计写入是 best-effort：实现抛出的任何异常都不得影响规则变更本身，
 * 调用方（管理接口 / 种子加载器）会吞掉异常并记 warn。
 *
 * @author API Governance Team
 * @since 0.8.0
 */
public interface RuleAuditLog {

    /**
     * 记录一条审计条目。
     *
     * @param entry 审计条目
     */
    void record(RuleAuditEntry entry);

    /**
     * 最近的审计条目（时间倒序，最新在前）。
     *
     * @param limit 最多返回条数（至少 1）
     * @return 审计条目
     */
    List<RuleAuditEntry> recent(int limit);

    /** 当前保存的条目数。 */
    int size();

    /** 容量上限（0 表示不记录）。 */
    int getCapacity();

    /** 实现名称（管理接口展示）。 */
    String getName();
}
