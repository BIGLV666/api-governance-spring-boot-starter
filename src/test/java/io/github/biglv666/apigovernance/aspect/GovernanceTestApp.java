package io.github.biglv666.apigovernance.aspect;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 治理切面端到端测试专用应用 —— 仅存在于测试类路径。
 *
 * <p>作为顶层类放在测试包下，{@code @SpringBootTest} 的向上搜索能稳定找到它，
 * 组件扫描也能稳定注册同包下的 {@link GovernanceTestController} 与 {@link CapturingGovernanceFilter}。
 *
 * @author API Governance Team
 * @since 0.3.0
 */
@SpringBootApplication
public class GovernanceTestApp {
}
