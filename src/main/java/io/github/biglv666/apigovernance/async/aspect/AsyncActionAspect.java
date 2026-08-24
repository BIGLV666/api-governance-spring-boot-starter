package io.github.biglv666.apigovernance.async.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import io.github.biglv666.apigovernance.async.AsyncInvocation;
import io.github.biglv666.apigovernance.async.annotation.AsyncAction;
import io.github.biglv666.apigovernance.async.event.AsyncPhase;
import io.github.biglv666.apigovernance.async.internal.AsyncDispatcher;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.BridgeMethodResolver;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;

/**
 * Precise AOP entry point for methods explicitly marked with {@link AsyncAction}.
 */
@Aspect
@Order(Ordered.HIGHEST_PRECEDENCE + 200)
public final class AsyncActionAspect {

    private final AsyncDispatcher dispatcher;

    public AsyncActionAspect(AsyncDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Around("@annotation(io.github.biglv666.apigovernance.async.annotation.AsyncAction)")
    public Object around(ProceedingJoinPoint joinPoint) throws Throwable {
        Method signatureMethod = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Class<?> targetClass = AopUtils.getTargetClass(joinPoint.getTarget());
        Method method = ClassUtils.getMostSpecificMethod(signatureMethod, targetClass);
        method = BridgeMethodResolver.findBridgedMethod(method);
        AsyncAction action = AnnotatedElementUtils.findMergedAnnotation(method, AsyncAction.class);
        if (action == null) {
            action = AnnotatedElementUtils.findMergedAnnotation(signatureMethod, AsyncAction.class);
        }
        if (action == null) {
            return joinPoint.proceed();
        }

        String eventId = UUID.randomUUID().toString();
        Instant startedAt = Instant.now();
        long startNanos = System.nanoTime();
        Object[] arguments = joinPoint.getArgs();
        dispatch(eventId, action.value(), AsyncPhase.BEFORE, targetClass, method,
                arguments, null, null, startedAt, 0L);

        Object result = null;
        Throwable error = null;
        try {
            result = joinPoint.proceed();
            dispatch(eventId, action.value(), AsyncPhase.AFTER_SUCCESS, targetClass, method,
                    arguments, result, null, startedAt, elapsedMillis(startNanos));
            return result;
        } catch (Throwable ex) {
            error = ex;
            dispatch(eventId, action.value(), AsyncPhase.AFTER_ERROR, targetClass, method,
                    arguments, null, ex, startedAt, elapsedMillis(startNanos));
            throw ex;
        } finally {
            dispatch(eventId, action.value(), AsyncPhase.AFTER_COMPLETION, targetClass, method,
                    arguments, result, error, startedAt, elapsedMillis(startNanos));
        }
    }

    private void dispatch(String id, String action, AsyncPhase phase,
                          Class<?> targetClass, Method method, Object[] arguments,
                          Object result, Throwable error, Instant startedAt,
                          long elapsedMillis) {
        try {
            dispatcher.dispatch(new AsyncInvocation(id, action, phase, targetClass, method,
                    arguments, result, error, startedAt, elapsedMillis));
        } catch (Throwable ignored) {
            // The asynchronous extension must never alter target method behavior.
        }
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
