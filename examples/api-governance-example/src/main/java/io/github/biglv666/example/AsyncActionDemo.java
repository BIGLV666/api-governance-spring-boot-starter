package io.github.biglv666.example;

import io.github.biglv666.apigovernance.async.annotation.AsyncAction;
import io.github.biglv666.apigovernance.async.annotation.AsyncHandler;
import io.github.biglv666.apigovernance.async.event.AsyncEvent;
import io.github.biglv666.apigovernance.async.event.AsyncPhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

/**
 * 异步方法生命周期插件演示：目标方法保持同步执行，
 * 框架在其生命周期阶段提交旁路异步任务。
 *
 * <p>完整契约见根目录 <a href="../../ASYNC_ACTIONS.md">ASYNC_ACTIONS.md</a>。
 *
 * @author API Governance Team
 * @since 0.3.0
 */
public class AsyncActionDemo {

    private AsyncActionDemo() {
    }

    /**
     * 演示服务：登录动作标注 {@code @AsyncAction}，业务本身完全不变。
     */
    @Service
    public static class LoginService {

        private static final Logger log = LoggerFactory.getLogger(LoginService.class);

        /**
         * 登录动作：方法执行前后会触发 {@link LoginHandlers} 中的旁路任务。
         *
         * @param userId 用户 ID
         * @return 登录结果
         */
        @AsyncAction("user.login")
        public String login(long userId) {
            log.info("业务登录逻辑执行中 - userId: {}", userId);
            return "token-" + userId;
        }
    }

    /**
     * 演示处理器：按阶段与顺序注册旁路任务（默认异步、不改变业务结果）。
     */
    @Component
    public static class LoginHandlers {

        private static final Logger log = LoggerFactory.getLogger(LoginHandlers.class);

        /**
         * 方法成功返回后写登录日志。
         *
         * @param event 异步事件快照（只携带元数据，不含参数与返回值）
         */
        @AsyncHandler(value = "user.login", phase = AsyncPhase.AFTER_SUCCESS, order = 100)
        public void saveLoginLog(AsyncEvent event) {
            log.info("[异步] 记录登录日志 - action: {}, elapsedMs: {}", event.action(), event.elapsedMillis());
        }

        /**
         * 方法抛出异常后告警。
         *
         * @param event 异步事件快照（异常仅 message，不含堆栈）
         */
        @AsyncHandler(value = "user.login", phase = AsyncPhase.AFTER_ERROR, order = 100)
        public void alertOnError(AsyncEvent event) {
            log.warn("[异步] 登录失败告警 - action: {}, error: {}", event.action(),
                    event.error() == null ? "-" : event.error().message());
        }
    }
}
