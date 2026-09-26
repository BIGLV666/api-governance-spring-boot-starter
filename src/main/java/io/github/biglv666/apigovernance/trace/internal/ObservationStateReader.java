package io.github.biglv666.apigovernance.trace.internal;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MQ 组件 observation 状态读取器（框架内部工具）。
 *
 * <p>Spring Kafka / Spring AMQP 的模板、监听容器工厂与容器都用 {@code observationEnabled}
 * 字段记录当前开关，但大多只暴露 setter（getter 为 protected 或不存在）。本工具按需
 * 反射读取该字段，供 BeanPostProcessor 实现「宿主已显式配置 observation 时不再覆盖」的
 * 尊重语义：读到的值为 {@code TRUE} 时跳过增强，为 {@code FALSE}（宿主显式关闭）时同样跳过，
 * 仅在字段不可读（库版本变更等）时返回 {@code null}，由调用方回退为旧行为（默认增强）。
 *
 * <p>字段按类缓存（{@code Class#getDeclaredField} 反射代价集中付一次）；
 * 沿继承链向上查找，兼容 primitive 与包装类型。
 *
 * @author API Governance Team
 * @since 0.5.1
 */
public final class ObservationStateReader {

    /** 字段缓存：类全名 -> observationEnabled 字段（empty 表示类层级中不存在）。 */
    private static final Map<String, Optional<Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    private ObservationStateReader() {
    }

    /**
     * 读取 bean 的 {@code observationEnabled} 字段值。
     *
     * @param bean MQ 模板 / 容器工厂 / 监听容器实例
     * @return 当前开关状态；字段不存在或不可访问时返回 {@code null}（语义未知）
     */
    public static Boolean readObservationEnabled(Object bean) {
        Field field = FIELD_CACHE
                .computeIfAbsent(bean.getClass().getName(), key -> findField(bean.getClass()))
                .orElse(null);
        if (field == null) {
            return null;
        }
        try {
            Object value = field.get(bean);
            return value instanceof Boolean b ? b : null;
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    /**
     * 沿继承链查找 {@code observationEnabled} 字段（boolean 或 Boolean），并设为可访问。
     */
    private static Optional<Field> findField(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                Field field = current.getDeclaredField("observationEnabled");
                if (field.getType() == boolean.class || field.getType() == Boolean.class) {
                    field.setAccessible(true);
                    return Optional.of(field);
                }
            } catch (NoSuchFieldException ignored) {
                // 继续向父类查找
            } catch (SecurityException e) {
                // 受限环境不允许 setAccessible：按「状态未知」处理
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
