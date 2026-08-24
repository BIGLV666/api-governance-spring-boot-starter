package io.github.biglv666.apigovernance.metrics;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 有界滑动窗口单元测试：验证「数量边界」与「时间边界」双重保证内存不膨胀。
 *
 * @author API Governance Team
 * @since 1.0
 */
class SlidingWindowTest {

    @Test
    void boundedByCount() {
        SlidingWindow<String> window = new SlidingWindow<>(3, 0);
        for (int i = 0; i < 10; i++) {
            window.add("v" + i);
        }
        assertEquals(3, window.size());
        assertEquals(List.of("v7", "v8", "v9"), window.snapshot());
    }

    @Test
    void evictsExpiredByTime() throws InterruptedException {
        SlidingWindow<String> window = new SlidingWindow<>(100, 50);
        window.add("a");
        Thread.sleep(80);
        window.add("b");
        assertEquals(1, window.size());
        assertEquals(List.of("b"), window.snapshot());
    }

    @Test
    void clearEmptiesWindow() {
        SlidingWindow<String> window = new SlidingWindow<>(10, 0);
        window.add("a");
        window.add("b");
        window.clear();
        assertEquals(0, window.size());
    }
}
