package io.github.biglv666.apigovernance.management;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import io.github.biglv666.apigovernance.config.ApiGovernanceProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 管理接口轻量鉴权过滤器 —— 为后台管理接口提供可选的静态令牌校验。
 *
 * <p>面向没有网关 / 安全框架的小型部署：配置 {@code api.governance.management.auth-token}
 * 后，所有管理接口请求必须携带 {@code auth-header} 指定的请求头（默认
 * {@code X-Governance-Token}）且值与令牌一致，否则返回 401。令牌为空（默认）时
 * 本过滤器不注册，行为与 0.1.0 完全一致。
 *
 * <h3>安全设计</h3>
 * <ul>
 *   <li>使用 {@link MessageDigest#isEqual} 恒定时间比较，防止时序侧信道逐字节猜解令牌；</li>
 *   <li>401 响应体不回显失败原因细节，不泄露令牌或路径信息；</li>
 *   <li>令牌支持占位符注入环境变量（yml 中 {@code auth-token: ${GOVERNANCE_TOKEN}}），
 *       避免硬编码；过滤器与日志均不打印令牌。</li>
 * </ul>
 *
 * <p>本过滤器只做轻量令牌校验，不能替代完整的认证授权体系；有 Spring Security
 * 或网关的环境请优先使用它们，并保持令牌为空以关闭本过滤器。
 *
 * @author API Governance Team
 * @since 0.2.0
 */
public class GovernanceManagementAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(GovernanceManagementAuthFilter.class);

    private final ApiGovernanceProperties properties;

    /**
     * 构造鉴权过滤器。
     *
     * @param properties 全局配置（读取令牌与请求头名称）
     */
    public GovernanceManagementAuthFilter(ApiGovernanceProperties properties) {
        this.properties = properties;
    }

    /**
     * 校验管理接口请求的令牌。
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String expected = properties.getManagement().getAuthToken();
        String provided = request.getHeader(properties.getManagement().getAuthHeader());

        if (isTokenValid(expected, provided)) {
            filterChain.doFilter(request, response);
            return;
        }
        // 401 响应不携带失败细节，防止探测与信息泄露
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=utf-8");
        response.getWriter().write("{\"success\":false,\"code\":\"UNAUTHORIZED\",\"message\":\"未授权\"}");
        log.warn("管理接口鉴权失败 - uri: {}", request.getRequestURI());
    }

    /**
     * 恒定时间比较令牌，避免时序侧信道。任一为空视为不匹配。
     */
    private boolean isTokenValid(String expected, String provided) {
        if (expected == null || expected.isEmpty() || provided == null || provided.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
