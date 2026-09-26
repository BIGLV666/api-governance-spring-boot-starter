package io.github.biglv666.apigovernance.aspect;

import io.github.biglv666.apigovernance.config.ApiGovernanceProperties;
import io.github.biglv666.apigovernance.filter.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 治理切面 apiKey 构建测试：验证重载映射方法的消歧后缀。
 *
 * @author API Governance Team
 * @since 0.5.1
 */
class GovernanceAspectApiKeyTest {

    private final GovernanceAspect aspect =
            new GovernanceAspect(new FilterChain(List.of(), List.of()), new ApiGovernanceProperties());

    @Test
    void overloadedMappedMethodsGetDistinctApiKeys() throws Exception {
        Class<OverloadController> type = OverloadController.class;
        Method getLong = type.getMethod("get", Long.class);
        Method getString = type.getMethod("get", String.class);
        Method list = type.getMethod("list");

        assertThat(invokeBuildApiKey(type, getLong)).isEqualTo(type.getName() + "#get(Long)");
        assertThat(invokeBuildApiKey(type, getString)).isEqualTo(type.getName() + "#get(String)");
        // 非重载方法保持既有契约：全限定类名#方法名
        assertThat(invokeBuildApiKey(type, list)).isEqualTo(type.getName() + "#list");
    }

    @Test
    void zeroArgOverloadGetsEmptyParenSuffix() throws Exception {
        Class<OverloadWithNoArg> type = OverloadWithNoArg.class;
        Method get = type.getMethod("get");

        assertThat(invokeBuildApiKey(type, get)).isEqualTo(type.getName() + "#get()");
    }

    private String invokeBuildApiKey(Class<?> targetClass, Method method) throws Exception {
        Method buildApiKey = GovernanceAspect.class.getDeclaredMethod("buildApiKey", Class.class, Method.class);
        buildApiKey.setAccessible(true);
        return (String) buildApiKey.invoke(aspect, targetClass, method);
    }

    @RestController
    static class OverloadController {
        @GetMapping("/a")
        public String get(Long id) {
            return "a";
        }

        @GetMapping("/b")
        public String get(String name) {
            return "b";
        }

        @GetMapping("/c")
        public String list() {
            return "c";
        }
    }

    @RestController
    static class OverloadWithNoArg {
        @GetMapping("/n")
        public String get() {
            return "n";
        }

        @GetMapping("/m")
        public String get(Long id) {
            return "m";
        }
    }
}
