package com.misanthropy.thread_priorities;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

final class Scanner {
    static final Logger LOGGER = LogManager.getLogger("Thread Priorities");

    static volatile int driver = override("driver", Win.HIGHEST);
    static volatile int background = override("background", 1);
    static volatile int pool = override("pool", Win.OFF);
    static volatile int render = override("render", Thread.MAX_PRIORITY);
    static volatile boolean log = Boolean.getBoolean("thread_priorities.log");
    static volatile boolean critical = critical(true);
    static volatile boolean active = true;
    private static int poolMoved;

    private Scanner() {
    }

    static boolean critical(boolean fallback) {
        String value = System.getProperty("thread_priorities.critical");
        int cpus = Runtime.getRuntime().availableProcessors();
        return (value == null ? fallback : Boolean.parseBoolean(value)) && cpus > 4 && cpus <= 8;
    }

    static int override(String key, int fallback) {
        Integer value = Integer.getInteger("thread_priorities." + key);
        return value == null ? fallback : value;
    }

    static void start() {
        boolean windows = Win.init();
        Thread thread = new Thread(() -> run(windows), "Thread Priorities");
        thread.setDaemon(true);
        thread.setPriority(3);
        thread.start();
    }

    private static void run(boolean windows) {
        long start = System.nanoTime();
        long lastLog = start;
        boolean first = true;
        while (true) {
            try {
                if (windows) {
                    Win.scan(driver, background, critical && active);
                }
                if (pool != Win.OFF) {
                    sharedPool(pool);
                }
                long now = System.nanoTime();
                if (now - lastLog > 60_000_000_000L && (first || log)) {
                    first = false;
                    lastLog = now;
                    LOGGER.info("graphics driver {}: {} threads raised, background threads moved {}, shared pool threads lowered {}, input and sound threads raised {}", Win.driverName, Win.driverMoved, Win.backgroundMoved, poolMoved, Win.inputMoved);
                }
                Thread.sleep(now - start < 180_000_000_000L ? 2000 : 5000);
            } catch (InterruptedException e) {
                return;
            } catch (Throwable t) {
                LOGGER.warn("stopped", t);
                return;
            }
        }
    }

    private static void sharedPool(int priority) {
        ThreadGroup root = Thread.currentThread().getThreadGroup();
        while (root.getParent() != null) {
            root = root.getParent();
        }
        Thread[] threads = new Thread[root.activeCount() + 64];
        int count = root.enumerate(threads, true);
        for (int i = 0; i < count; i++) {
            Thread thread = threads[i];
            if (thread.getPriority() == Thread.NORM_PRIORITY && thread.getName().startsWith("ForkJoinPool.commonPool-worker")) {
                thread.setPriority(priority);
                poolMoved++;
            }
        }
    }
}
