package org.example.apigovernancespringbootstarter.filter;

import org.springframework.core.Ordered;

/**
 * 过滤器基础接口 —— 治理管道的「插件」契约。
 *
 * <p>所有前置（{@link PreFilter}）与后置（{@link PostFilter}）过滤器都实现本接口。
 * 实现类只需注册为 Spring Bean（{@code @Component} 或 {@code @Bean}），即会自动加入管道，
 * 实现「自定义插拔」。
 *
 * <h3>执行顺序建议</h3>
 * <ul>
 *   <li>1 ~ 99：信息采集、元数据收集</li>
 *   <li>100 ~ 199：流量统计</li>
 *   <li>200 ~ 299：限流判断</li>
 *   <li>300 ~ 399：参数校验等业务前置</li>
 *   <li>400 ~ 499：慢方法/指标统计</li>
 *   <li>500 ~ ：日志记录（建议最后）</li>
 * </ul>
 *
 * @author API Governance Team
 * @since 1.0
 */
public interface Filter extends Ordered {

    /**
     * 过滤器执行顺序：数字越小优先级越高。
     *
     * @return 执行顺序
     */
    @Override
    default int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /**
     * 过滤器是否启用（可用于运行时开关）。
     *
     * @return true 启用，false 跳过
     */
    default boolean isEnabled() {
        return true;
    }

    /**
     * 过滤器名称（用于日志与管理接口展示）。
     *
     * @return 名称，默认取类名
     */
    default String getName() {
        return getClass().getSimpleName();
    }
}
