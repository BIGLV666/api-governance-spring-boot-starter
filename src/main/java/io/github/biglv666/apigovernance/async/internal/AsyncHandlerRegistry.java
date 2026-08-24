package io.github.biglv666.apigovernance.async.internal;

import io.github.biglv666.apigovernance.async.AsyncHandlerInfo;
import io.github.biglv666.apigovernance.async.annotation.AsyncAction;
import io.github.biglv666.apigovernance.async.annotation.AsyncHandler;
import io.github.biglv666.apigovernance.async.event.AsyncEvent;
import io.github.biglv666.apigovernance.async.event.AsyncPhase;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discovers, validates and caches asynchronous handlers once at startup.
 */
public final class AsyncHandlerRegistry implements SmartInitializingSingleton {

    private static final Comparator<RegisteredAsyncHandler> HANDLER_ORDER =
            Comparator.comparingInt((RegisteredAsyncHandler handler) -> handler.info().order())
                    .thenComparing(handler -> handler.info().beanName())
                    .thenComparing(handler -> handler.info().method());

    private final ConfigurableListableBeanFactory beanFactory;
    private volatile Map<HandlerKey, List<RegisteredAsyncHandler>> handlers = Map.of();

    public AsyncHandlerRegistry(ConfigurableListableBeanFactory beanFactory) {
        this.beanFactory = beanFactory;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Map<HandlerKey, List<RegisteredAsyncHandler>> discovered = new HashMap<>();
        for (String beanName : beanFactory.getBeanDefinitionNames()) {
            Class<?> beanType = beanFactory.getType(beanName, false);
            if (beanType == null || isInfrastructureType(beanType)) {
                continue;
            }
            validateActions(beanName, beanType);
            registerHandlers(beanName, beanType, discovered);
        }
        discovered.values().forEach(list -> list.sort(HANDLER_ORDER));
        Map<HandlerKey, List<RegisteredAsyncHandler>> immutable = new HashMap<>();
        discovered.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
        handlers = Map.copyOf(immutable);
    }

    List<RegisteredAsyncHandler> getHandlers(String action, AsyncPhase phase) {
        return handlers.getOrDefault(new HandlerKey(action, phase), List.of());
    }

    /**
     * Returns registered handler metadata for diagnostics and tests.
     */
    public List<AsyncHandlerInfo> getHandlerInfos() {
        return handlers.values().stream()
                .flatMap(Collection::stream)
                .map(RegisteredAsyncHandler::info)
                .sorted(Comparator.comparing(AsyncHandlerInfo::action)
                        .thenComparing(AsyncHandlerInfo::phase)
                        .thenComparingInt(AsyncHandlerInfo::order)
                        .thenComparing(AsyncHandlerInfo::beanName)
                        .thenComparing(AsyncHandlerInfo::method))
                .toList();
    }

    private void validateActions(String beanName, Class<?> beanType) {
        Map<Method, AsyncAction> actions = MethodIntrospector.selectMethods(beanType,
                (MethodIntrospector.MetadataLookup<AsyncAction>) method ->
                        AnnotatedElementUtils.findMergedAnnotation(method, AsyncAction.class));
        actions.forEach((method, annotation) -> {
            requirePublic(beanName, method, "@AsyncAction");
            requireText(annotation.value(), beanName, method, "@AsyncAction value");
        });
    }

    private void registerHandlers(String beanName, Class<?> beanType,
                                  Map<HandlerKey, List<RegisteredAsyncHandler>> discovered) {
        Map<Method, Set<AsyncHandler>> methods = MethodIntrospector.selectMethods(beanType,
                (MethodIntrospector.MetadataLookup<Set<AsyncHandler>>) method -> {
                    Set<AsyncHandler> annotations = AnnotatedElementUtils
                            .getMergedRepeatableAnnotations(method, AsyncHandler.class);
                    return annotations.isEmpty() ? null : annotations;
                });
        if (methods.isEmpty()) {
            return;
        }

        Object bean = beanFactory.getBean(beanName);
        methods.forEach((method, annotations) -> {
            validateHandlerMethod(beanName, method);
            Method invocableMethod = AopUtils.selectInvocableMethod(method, bean.getClass());
            boolean eventParameter = method.getParameterCount() == 1;
            for (AsyncHandler annotation : annotations) {
                requireText(annotation.value(), beanName, method, "@AsyncHandler value");
                AsyncHandlerInfo info = new AsyncHandlerInfo(
                        annotation.value(), annotation.phase(), annotation.order(), beanName,
                        beanType.getName(), method.toGenericString());
                discovered.computeIfAbsent(
                                new HandlerKey(annotation.value(), annotation.phase()),
                                ignored -> new ArrayList<>())
                        .add(new RegisteredAsyncHandler(bean, invocableMethod, eventParameter, info));
            }
        });
    }

    private void validateHandlerMethod(String beanName, Method method) {
        requirePublic(beanName, method, "@AsyncHandler");
        if (method.getReturnType() != Void.TYPE) {
            throw invalid(beanName, method, "@AsyncHandler method must return void");
        }
        if (method.getParameterCount() > 1
                || (method.getParameterCount() == 1
                && method.getParameterTypes()[0] != AsyncEvent.class)) {
            throw invalid(beanName, method,
                    "@AsyncHandler method must declare no parameters or one AsyncEvent parameter");
        }
    }

    private void requirePublic(String beanName, Method method, String annotation) {
        if (!Modifier.isPublic(method.getModifiers())) {
            throw invalid(beanName, method, annotation + " method must be public");
        }
    }

    private void requireText(String value, String beanName, Method method, String label) {
        if (value == null || value.isBlank()) {
            throw invalid(beanName, method, label + " must not be blank");
        }
    }

    private IllegalStateException invalid(String beanName, Method method, String message) {
        return new IllegalStateException(message + ": bean='" + beanName
                + "', method='" + method.toGenericString() + "'");
    }

    private boolean isInfrastructureType(Class<?> beanType) {
        String packageName = ClassUtils.getPackageName(beanType);
        return packageName.startsWith("org.springframework.")
                || packageName.startsWith("org.aspectj.");
    }

    private record HandlerKey(String action, AsyncPhase phase) {
    }
}
