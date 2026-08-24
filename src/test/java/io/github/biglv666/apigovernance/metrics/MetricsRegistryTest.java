package io.github.biglv666.apigovernance.metrics;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MetricsRegistry 单元测试。
 */
class MetricsRegistryTest {

    @Test
    void getAllSlowRecordsReturnsEmptyWhenNoSlow() {
        MetricsRegistry registry = new MetricsRegistry(100, 10, 300_000);
        
        // 记录一个快速请求
        registry.recordStart("com.example.A#m1");
        registry.recordResult("com.example.A#m1", 500, true, false, "GET", "/api/a", null);
        
        Map<String, List<RequestRecord>> result = registry.getAllSlowRecords();
        assertTrue(result.isEmpty(), "没有慢方法时应该返回空 Map");
    }

    @Test
    void getAllSlowRecordsReturnsMultipleApis() {
        MetricsRegistry registry = new MetricsRegistry(100, 10, 300_000);
        
        // API A: 1 个慢请求
        registry.recordStart("com.example.A#m1");
        registry.recordResult("com.example.A#m1", 1200, true, true, "GET", "/api/a", null);
        
        // API B: 2 个慢请求
        registry.recordStart("com.example.B#m2");
        registry.recordResult("com.example.B#m2", 1500, true, true, "POST", "/api/b", null);
        registry.recordStart("com.example.B#m2");
        registry.recordResult("com.example.B#m2", 2000, false, true, "POST", "/api/b", "timeout");
        
        // API C: 0 个慢请求（快速）
        registry.recordStart("com.example.C#m3");
        registry.recordResult("com.example.C#m3", 300, true, false, "GET", "/api/c", null);
        
        Map<String, List<RequestRecord>> result = registry.getAllSlowRecords();
        
        assertEquals(2, result.size(), "应该返回 2 个有慢请求的 API");
        assertTrue(result.containsKey("com.example.A#m1"));
        assertTrue(result.containsKey("com.example.B#m2"));
        assertFalse(result.containsKey("com.example.C#m3"), "快速 API 不应该在结果中");
        
        // 验证 API A 的慢请求
        List<RequestRecord> slowA = result.get("com.example.A#m1");
        assertEquals(1, slowA.size());
        assertEquals(1200, slowA.get(0).getElapsedMs());
        assertEquals("GET", slowA.get(0).getHttpMethod());
        
        // 验证 API B 的慢请求
        List<RequestRecord> slowB = result.get("com.example.B#m2");
        assertEquals(2, slowB.size());
        // 最新的在前（时间倒序）
        assertEquals(2000, slowB.get(0).getElapsedMs());
        assertEquals(1500, slowB.get(1).getElapsedMs());
    }

    @Test
    void getAllSlowRecordsReturnsSnapshot() {
        MetricsRegistry registry = new MetricsRegistry(100, 10, 300_000);
        
        registry.recordStart("com.example.A#m1");
        registry.recordResult("com.example.A#m1", 1200, true, true, "GET", "/api/a", null);
        
        Map<String, List<RequestRecord>> result1 = registry.getAllSlowRecords();
        
        // 修改返回的 Map 不应该影响内部数据
        result1.clear();
        
        Map<String, List<RequestRecord>> result2 = registry.getAllSlowRecords();
        assertEquals(1, result2.size(), "修改快照不应该影响内部数据");
    }
}
