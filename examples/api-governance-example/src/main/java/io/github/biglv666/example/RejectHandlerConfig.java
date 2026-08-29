package io.github.biglv666.example;

import io.github.biglv666.apigovernance.ratelimit.RateLimitRejectHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 自定义限流拒绝响应演示：注册 RateLimitRejectHandler Bean 即可完全自定义
 * 被限流后的状态码、提示语与扩展属性（默认行为见 yml 的 status-code / message）。
 */
@Configuration
public class RejectHandlerConfig {

    @Bean
    public RateLimitRejectHandler demoRejectHandler() {
        return (context, rateLimitKey) -> {
            context.setRejectStatus(429);
            context.setRejectReason("演示限流：接口 " + context.getApiKey()
                    + " 最多 " + context.getRateLimit() + " 次/" + context.getWindow() + " 秒");
            context.setAttribute("retryAfterSeconds", context.getWindow());
        };
    }
}
