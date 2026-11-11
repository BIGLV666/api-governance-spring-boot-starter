package io.github.biglv666.apigovernance.concurrency;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 本地并发限制器测试：非阻塞、不超卖、finally 释放、异常路径与动态 permits 切换。
 *
 * @author API Governance Team
 * @since 1.0.0
 */
class LocalConcurrencyLimiterTest {

    @Test
    void acquireUpToPermitsThenFailFast() {
        LocalConcurrencyLimiter limiter = new LocalConcurrencyLimiter(100, 60_000L);

        ConcurrencyPermit first = limiter.tryAcquire("a.A#get", 2);
        ConcurrencyPermit second = limiter.tryAcquire("a.A#get", 2);

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(limiter.tryAcquire("a.A#get", 2)).isNull();
        assertThat(limiter.getActiveCount("a.A#get")).isEqualTo(2);

        first.release();
        assertThat(limiter.getActiveCount("a.A#get")).isEqualTo(1);
        assertThat(limiter.tryAcquire("a.A#get", 2)).isNotNull();
    }

    @Test
    void releaseIsIdempotent() {
        LocalConcurrencyLimiter limiter = new LocalConcurrencyLimiter(100, 60_000L);
        ConcurrencyPermit permit = limiter.tryAcquire("a.A#get", 1);

        permit.release();
        permit.release();
        permit.release();

        assertThat(limiter.getActiveCount("a.A#get")).isZero();
        assertThat(permit.isReleased()).isTrue();
        // 重复释放不得凭空增加配额
        assertThat(limiter.tryAcquire("a.A#get", 1)).isNotNull();
        assertThat(limiter.tryAcquire("a.A#get", 1)).isNull();
    }

    @Test
    void keysAreIndependent() {
        LocalConcurrencyLimiter limiter = new LocalConcurrencyLimiter(100, 60_000L);

        assertThat(limiter.tryAcquire("a.A#get", 1)).isNotNull();
        assertThat(limiter.tryAcquire("a.B#get", 1)).isNotNull();
        assertThat(limiter.getActiveCount("a.A#get")).isEqualTo(1);
        assertThat(limiter.getActiveCount("a.B#get")).isEqualTo(1);
        assertThat(limiter.getKeyCount()).isEqualTo(2);
    }

    @Test
    void changingPermitsCreatesNewBucketAndKeepsOldReleaseSafe() {
        LocalConcurrencyLimiter limiter = new LocalConcurrencyLimiter(100, 60_000L);
        ConcurrencyPermit old = limiter.tryAcquire("a.A#get", 5);

        ConcurrencyPermit fresh = limiter.tryAcquire("a.A#get", 1);

        assertThat(fresh).isNotNull();
        assertThat(limiter.tryAcquire("a.A#get", 1)).isNull();
        // 旧桶的名额仍可安全归还
        old.release();
        fresh.release();
        assertThat(limiter.getActiveCount("a.A#get")).isZero();
    }

    @Test
    void invalidArgumentsAreRejected() {
        LocalConcurrencyLimiter limiter = new LocalConcurrencyLimiter(10, 60_000L);

        assertThatThrownBy(() -> limiter.tryAcquire(" ", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter.tryAcquire("a.A#get", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter.tryAcquire("a.A#get", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void idleBucketsAreEvictedButActiveOnesAreKept() throws Exception {
        LocalConcurrencyLimiter limiter = new LocalConcurrencyLimiter(100, 1000L);

        ConcurrencyPermit active = limiter.tryAcquire("a.Active#get", 1);
        ConcurrencyPermit idle = limiter.tryAcquire("a.Idle#get", 1);
        idle.release();
        Thread.sleep(1100L);

        // 触发一次扫描：空闲桶被淘汰，仍有在途请求的桶必须保留
        ConcurrencyPermit next = limiter.tryAcquire("a.Next#get", 1);
        assertThat(next).isNotNull();
        assertThat(limiter.getKeyCount()).isEqualTo(2);
        assertThat(limiter.getActiveCount("a.Active#get")).isEqualTo(1);
        assertThat(limiter.getActiveCount("a.Idle#get")).isZero();

        active.release();
        next.release();
    }

    @Test
    void oneHundredThreadsNeverOversell() throws Exception {
        LocalConcurrencyLimiter limiter = new LocalConcurrencyLimiter(100, 60_000L);
        int threads = 100;
        int permits = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch holding = new CountDownLatch(permits);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger acquired = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<ConcurrencyPermit> permitsHeld = java.util.Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    ConcurrencyPermit permit = limiter.tryAcquire("a.Hot#get", permits);
                    if (permit == null) {
                        rejected.incrementAndGet();
                        return;
                    }
                    permitsHeld.add(permit);
                    acquired.incrementAndGet();
                    holding.countDown();
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    // 由主线程统一释放，模拟业务执行期间持有
                }
            });
        }
        start.countDown();
        assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();
        // 等待其余线程完成拒绝
        Thread.sleep(300);
        assertThat(acquired.get()).isEqualTo(permits);
        assertThat(rejected.get()).isEqualTo(threads - permits);
        assertThat(limiter.getActiveCount("a.Hot#get")).isEqualTo(permits);

        release.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        permitsHeld.forEach(ConcurrencyPermit::release);
        assertThat(limiter.getActiveCount("a.Hot#get")).isZero();
    }
}
