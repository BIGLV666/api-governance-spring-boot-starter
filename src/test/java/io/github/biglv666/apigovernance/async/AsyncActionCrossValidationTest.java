package io.github.biglv666.apigovernance.async;

import io.github.biglv666.apigovernance.async.annotation.AsyncAction;
import io.github.biglv666.apigovernance.async.annotation.AsyncHandler;
import io.github.biglv666.apigovernance.async.internal.AsyncHandlerRegistry;
import io.github.biglv666.apigovernance.config.ApiGovernanceAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动期 action 交叉校验测试：{@code @AsyncHandler} 引用不存在的 {@code @AsyncAction}
 * 默认 fail-fast（0.5.0），可配 {@code ignore-unmatched-handlers=true} 放行。
 *
 * @author API Governance Team
 * @since 0.5.0
 */
class AsyncActionCrossValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ApiGovernanceAutoConfiguration.class));

    static class LoginActionBean {
        @AsyncAction("user.login")
        public void login() {
        }
    }

    static class MatchedHandlerBean {
        @AsyncHandler("user.login")
        public void onLogin() {
        }
    }

    static class MismatchedHandlerBean {
        // 故意拼错的 action：user.logn 不存在
        @AsyncHandler("user.logn")
        public void onLogin() {
        }
    }

    @Test
    void matchedActionAndHandlerStartSuccessfully() {
        runner.withUserConfiguration(LoginActionBean.class, MatchedHandlerBean.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(AsyncHandlerRegistry.class);
                    assertThat(ctx.getBean(AsyncHandlerRegistry.class).getHandlerInfos()).hasSize(1);
                });
    }

    @Test
    void unmatchedHandlerFailsStartupByDefault() {
        runner.withUserConfiguration(LoginActionBean.class, MismatchedHandlerBean.class)
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void unmatchedHandlerCanBeIgnoredByProperty() {
        runner.withPropertyValues("api.governance.async.ignore-unmatched-handlers=true")
                .withUserConfiguration(LoginActionBean.class, MismatchedHandlerBean.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(AsyncHandlerRegistry.class);
                    assertThat(ctx.getBean(AsyncHandlerRegistry.class).getHandlerInfos()).hasSize(1);
                });
    }

    @Test
    void handlerWithoutAnyDeclaredActionAlsoFails() {
        // 只有 handler、整个应用没有声明任何 action 的极端场景
        runner.withUserConfiguration(MismatchedHandlerBean.class)
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
