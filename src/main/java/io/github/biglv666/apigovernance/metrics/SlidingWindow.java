package io.github.biglv666.apigovernance.metrics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 有界滑动窗口 —— 用于「内存指标统计」中的请求记录存储，保证内存永远不会膨胀。
 *
 * <h3>设计目标</h3>
 * <p>指标数据保存在内存中（随进程启动而创建、随进程关闭而销毁），因此必须严格控制内存上限。
 * 本类通过<b>双重边界</b>保证大小不会无限增长：
 * <ol>
 *   <li><b>时间边界</b>：超过 {@code windowMillis} 的旧记录会被淘汰；</li>
 *   <li><b>数量边界</b>：元素数量超过 {@code maxSize} 时，淘汰最旧的记录（硬性上限，最可靠）。</li>
 * </ol>
 *
 * <h3>实现说明</h3>
 * <p>由于每个 API 各自持有一个窗口实例，竞争粒度较小，因此采用轻量的 {@code synchronized} 保证
 * 一致性，避免引入额外锁开销。窗口内元素按时间戳单调递增，淘汰时只需从队首移除。
 *
 * @param <E> 窗口内元素类型
 * @author API Governance Team
 * @since 1.0
 */
public class SlidingWindow<E> {

    /**
     * 窗口内元素的硬性数量上限，保证内存不膨胀。
     */
    private final int maxSize;

    /**
     * 时间窗口长度（毫秒），超过该时长的元素视为过期。
     */
    private final long windowMillis;

    /**
     * 存放元素的双端队列（队首最旧、队尾最新）。
     */
    private final Deque<Entry<E>> deque = new ArrayDeque<>();

    /**
     * 构造有界滑动窗口。
     *
     * @param maxSize      元素数量上限（必须 &gt; 0）
     * @param windowMillis 时间窗口（毫秒），若 {@code <= 0} 则仅按数量边界淘汰
     */
    public SlidingWindow(int maxSize, long windowMillis) {
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize 必须大于 0");
        }
        this.maxSize = maxSize;
        this.windowMillis = windowMillis;
    }

    /**
     * 向窗口尾部追加一个元素，并执行双重边界淘汰。
     *
     * @param value 元素值
     */
    public synchronized void add(E value) {
        long now = System.currentTimeMillis();
        // 1. 时间边界：淘汰过期元素
        if (windowMillis > 0) {
            long expireBefore = now - windowMillis;
            while (!deque.isEmpty() && deque.peekFirst().timestamp < expireBefore) {
                deque.pollFirst();
            }
        }
        // 2. 追加新元素
        deque.addLast(new Entry<>(value, now));
        // 3. 数量边界：超过上限则淘汰最旧元素
        while (deque.size() > maxSize) {
            deque.pollFirst();
        }
    }

    /**
     * 获取当前窗口内元素的快照（按时间从旧到新）。
     *
     * <p>返回副本，避免外部修改内部结构。
     *
     * @return 窗口元素列表
     */
    public synchronized List<E> snapshot() {
        List<E> result = new ArrayList<>(deque.size());
        for (Entry<E> entry : deque) {
            result.add(entry.value);
        }
        return result;
    }

    /**
     * 获取当前窗口内的元素数量。
     *
     * @return 元素数量
     */
    public synchronized int size() {
        return deque.size();
    }

    /**
     * 清空窗口。
     */
    public synchronized void clear() {
        deque.clear();
    }

    /**
     * 窗口元素（值 + 时间戳）。
     */
    private static final class Entry<E> {
        final E value;
        final long timestamp;

        Entry(E value, long timestamp) {
            this.value = value;
            this.timestamp = timestamp;
        }
    }
}
