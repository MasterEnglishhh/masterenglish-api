package com.masterenglish.api.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Starts several tasks at the same moment on separate threads. Use it only in tests that do not run
 * inside a transaction, so each task commits on its own and the races are real.
 */
public final class ConcurrentRequests {

    private ConcurrentRequests() {}

    public static <T> List<T> runAtOnce(int parties, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(parties);
        try {
            CyclicBarrier barrier = new CyclicBarrier(parties);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < parties; i++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    barrier.await(10, TimeUnit.SECONDS);
                                    return task.call();
                                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
