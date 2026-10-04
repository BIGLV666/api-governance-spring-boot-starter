package io.github.biglv666.apigovernance.ratelimit.rules;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 内存版动态规则存储测试：匹配优先级、通配边界、上限防护、幂等删除与并发可见性。
 *
 * @author API Governance Team
 * @since 0.7.0
 */
class InMemoryRateRuleStoreTest {

    private static final String GET = "com.x.UserController#get";

    @Test
    void exactRuleWinsOverPrefixRules() {
        InMemoryRateRuleStore store = new InMemoryRateRuleStore(100);
        store.put("com.x.UserController#get", 1, 10);
        store.put("com.x.UserController#*", 2, 10);
        store.put("com.x.*", 3, 10);

        Optional<DynamicRateRule> match = store.findMatch(GET);
        assertThat(match).isPresent();
        assertThat(match.get().getLimit()).isEqualTo(1);
    }

    @Test
    void longerPrefixWinsOverShorter() {
        InMemoryRateRuleStore store = new InMemoryRateRuleStore(100);
        store.put("com.x.*", 3, 10);
        store.put("com.x.UserController#*", 2, 10);

        Optional<DynamicRateRule> match = store.findMatch(GET);
        assertThat(match).isPresent();
        assertThat(match.get().getLimit()).isEqualTo(2);
    }

    @Test
    void wildcardIsPlainStartsWith() {
        InMemoryRateRuleStore store = new InMemoryRateRuleStore(100);
        store.put("com.x.User*", 7, 10);

        // 文档声明语义：startsWith，无单词边界 —— UserV2Controller 也命中
        assertThat(store.findMatch("com.x.UserV2Controller#get"))
                .map(DynamicRateRule::getLimit).contains(7);
        // 不同包段不命中：com.xy 不属于 com.x
        assertThat(store.findMatch("com.xy.OrderController#get")).isEmpty();
        assertThat(store.findMatch("com.y.UserController#get")).isEmpty();
    }

    @Test
    void noRulesMeansEmptyMatch() {
        InMemoryRateRuleStore store = new InMemoryRateRuleStore(100);
        assertThat(store.findMatch(GET)).isEmpty();
        assertThat(store.all()).isEmpty();
    }

    @Test
    void samePatternUpsertsInsteadOfGrowing() {
        InMemoryRateRuleStore store = new InMemoryRateRuleStore(100);
        store.put(GET, 5, 10);
        store.put(GET, 9, 60);

        assertThat(store.all()).hasSize(1);
        assertThat(store.findMatch(GET)).map(DynamicRateRule::getLimit).contains(9);
        assertThat(store.findMatch(GET)).map(DynamicRateRule::getWindow).contains(60);
    }

    @Test
    void maxRulesRejectsNewPatternButAllowsReplace() {
        InMemoryRateRuleStore store = new InMemoryRateRuleStore(2);
        store.put("a.A#get", 1, 1);
        store.put("a.B#get", 1, 1);

        assertThatThrownBy(() -> store.put("a.C#get", 1, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("上限");
        // 覆盖已有 pattern 不占新名额
        store.put("a.B#get", 2, 1);
        assertThat(store.findMatch("a.B#get")).map(DynamicRateRule::getLimit).contains(2);
    }

    @Test
    void removeIsIdempotentAndKeepsOthers() {
        InMemoryRateRuleStore store = new InMemoryRateRuleStore(100);
        store.put("a.A#get", 1, 1);
        store.put("a.B#get", 1, 1);

        store.remove("a.A#get");
        store.remove("a.A#get");
        store.remove("not-exist");

        assertThat(store.findMatch("a.A#get")).isEmpty();
        assertThat(store.findMatch("a.B#get")).isPresent();
        assertThat(store.all()).hasSize(1);
    }

    @Test
    void invalidRulesAreRejected() {
        InMemoryRateRuleStore store = new InMemoryRateRuleStore(100);
        assertThatThrownBy(() -> store.put(" ", 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.put("a.*#get", 1, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("尾部");
        assertThatThrownBy(() -> store.put("*", 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.put("a#get", -2, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.put("a#get", 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.all()).isEmpty();
    }

    @Test
    void limitZeroAndMinusOneAreLegal() {
        InMemoryRateRuleStore store = new InMemoryRateRuleStore(100);
        store.put(GET, -1, 1);
        assertThat(store.findMatch(GET)).map(DynamicRateRule::getLimit).contains(-1);
        store.put(GET, 0, 1);
        assertThat(store.findMatch(GET)).map(DynamicRateRule::getLimit).contains(0);
    }

    @Test
    void concurrentPutLosesNothing() throws Exception {
        InMemoryRateRuleStore store = new InMemoryRateRuleStore(1000);
        int threads = 16;
        int perThread = 25;
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean failed = new AtomicBoolean();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        store.put("a.Api" + threadId + "_" + i + "#get", 1, 1);
                    }
                } catch (Exception e) {
                    failed.set(true);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(failed.get()).as("并发 put 不应有异常").isFalse();
        assertThat(store.all()).hasSize(threads * perThread);
        // 快照读到的规则全部可命中
        for (Map.Entry<String, DynamicRateRule> entry : store.all().entrySet()) {
            String apiKey = entry.getKey().endsWith("*")
                    ? entry.getKey().substring(0, entry.getKey().length() - 1) + "get"
                    : entry.getKey();
            assertThat(store.findMatch(apiKey)).contains(entry.getValue());
        }
    }
}
