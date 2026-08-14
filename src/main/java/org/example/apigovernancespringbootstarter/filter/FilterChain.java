package org.example.apigovernancespringbootstarter.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 过滤器链管理器 —— 标准管道过滤器的执行引擎。
 *
 * <p>Spring 会自动收集容器内所有 {@link PreFilter} / {@link PostFilter} Bean（含内置与用户自定义），
 * 本类按 {@link Filter#getOrder()} 排序后依次执行：
 * <pre>
 *   前置链（任一 false 短路）→ 业务方法 → 后置链（finally 保证执行）
 * </pre>
 *
 * @author API Governance Team
 * @since 1.0
 */
public class FilterChain {

    private static final Logger log = LoggerFactory.getLogger(FilterChain.class);

    /** 排序后的前置过滤器。 */
    private final List<PreFilter> preFilters;

    /** 排序后的后置过滤器。 */
    private final List<PostFilter> postFilters;

    /**
     * 构造过滤器链。
     *
     * @param preFilters  容器内所有前置过滤器
     * @param postFilters 容器内所有后置过滤器
     */
    public FilterChain(List<PreFilter> preFilters, List<PostFilter> postFilters) {
        this.preFilters = new ArrayList<>(preFilters);
        this.postFilters = new ArrayList<>(postFilters);
        this.preFilters.sort(Comparator.comparingInt(Filter::getOrder));
        this.postFilters.sort(Comparator.comparingInt(Filter::getOrder));

        log.info("初始化过滤器链 - 前置过滤器: {}, 后置过滤器: {}", preFilters.size(), postFilters.size());
        if (log.isDebugEnabled()) {
            this.preFilters.forEach(f ->
                    log.debug("  前置过滤器: {} (order={})", f.getName(), f.getOrder()));
            this.postFilters.forEach(f ->
                    log.debug("  后置过滤器: {} (order={})", f.getName(), f.getOrder()));
        }
    }

    /**
     * 执行前置过滤器链。
     *
     * @param context 过滤器上下文
     * @return true 全部通过；false 被某个过滤器拒绝（短路）
     */
    public boolean executePreFilters(FilterContext context) {
        for (PreFilter filter : preFilters) {
            if (!filter.isEnabled()) {
                continue;
            }
            try {
                if (!filter.doFilter(context)) {
                    log.warn("前置过滤器拒绝请求 - 过滤器: {}, API: {}, 原因: {}",
                            filter.getName(), context.getApiKey(), context.getRejectReason());
                    return false;
                }
            } catch (Exception e) {
                // 前置过滤器异常视为拒绝，避免未预期的业务继续执行
                log.error("前置过滤器执行异常 - 过滤器: {}, API: {}",
                        filter.getName(), context.getApiKey(), e);
                context.setRejectReason("过滤器异常: " + filter.getName());
                return false;
            }
        }
        return true;
    }

    /**
     * 执行后置过滤器链（逐个执行，互不影响）。
     *
     * @param context 过滤器上下文
     */
    public void executePostFilters(FilterContext context) {
        for (PostFilter filter : postFilters) {
            if (!filter.isEnabled()) {
                continue;
            }
            try {
                filter.doFilter(context);
            } catch (Exception e) {
                // 后置过滤器异常不影响主流程，仅记录
                log.error("后置过滤器执行异常 - 过滤器: {}, API: {}",
                        filter.getName(), context.getApiKey(), e);
            }
        }
    }

    /** 获取前置过滤器（按顺序），供管理接口展示。 */
    public List<PreFilter> getPreFilters() {
        return preFilters;
    }

    /** 获取后置过滤器（按顺序），供管理接口展示。 */
    public List<PostFilter> getPostFilters() {
        return postFilters;
    }

    /** 前置过滤器数量。 */
    public int getPreFilterCount() {
        return preFilters.size();
    }

    /** 后置过滤器数量。 */
    public int getPostFilterCount() {
        return postFilters.size();
    }
}
