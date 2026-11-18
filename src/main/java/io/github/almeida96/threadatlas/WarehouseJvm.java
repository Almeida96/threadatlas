package io.github.almeida96.threadatlas;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/** A separate, disposable JVM used only by the demonstration and integration checks. */
public final class WarehouseJvm {
    private static final List<Thread> workers = new ArrayList<>();

    private static Thread worker(String name, Runnable body) {
        var t = Thread.ofPlatform().daemon().name("warehouse-" + name).unstarted(body);
        workers.add(t); t.start(); return t;
    }
    private static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    private static void monitorCycle() {
        Object a = new Object(), b = new Object();
        var both = new CountDownLatch(2);
        worker("stock", () -> { synchronized (a) { both.countDown(); await(both); synchronized (b) { } } });
        worker("dispatch", () -> { synchronized (b) { both.countDown(); await(both); synchronized (a) { } } });
    }
    private static void synchronizerCycle() {
        var a = new ReentrantLock(); var b = new ReentrantLock(); var both = new CountDownLatch(2);
        worker("invoice", () -> { a.lock(); both.countDown(); await(both); b.lock(); });
        worker("refund", () -> { b.lock(); both.countDown(); await(both); a.lock(); });
    }
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        Object releasedMonitor = new Object();
        worker("notification", () -> { synchronized (releasedMonitor) {
            try { releasedMonitor.wait(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }});
        var conditionLock = new ReentrantLock(); var condition = conditionLock.newCondition();
        worker("condition", () -> { conditionLock.lock(); try { condition.awaitUninterruptibly(); } finally { conditionLock.unlock(); } });
        if (mode.equals("deadlock")) { monitorCycle(); synchronizerCycle(); }
        else if (mode.equals("convoy")) {
            Object monitor = new Object(); var held = new CountDownLatch(1); var release = new CountDownLatch(1);
            worker("slow-owner", () -> { synchronized (monitor) { held.countDown(); await(release); } });
            await(held);
            for (int i = 0; i < 4; i++) worker("request-" + i, () -> { synchronized (monitor) { } });
        } else if (!mode.equals("idle")) throw new IllegalArgumentException("Unknown fixture mode");
        var bean = ManagementFactory.getThreadMXBean();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            long[] dead = bean.findDeadlockedThreads();
            boolean settled = workers.stream().allMatch(t -> Set.of(Thread.State.BLOCKED, Thread.State.WAITING).contains(t.getState()));
            if (settled && (!mode.equals("deadlock") || (dead != null && dead.length == 4))) break;
            if (System.nanoTime() > deadline) throw new IllegalStateException("Fixture did not settle");
            Thread.sleep(10);
        }
        long[] dead = bean.findDeadlockedThreads();
        List<Map<String, Object>> threads = new ArrayList<>();
        for (Thread t : workers) {
            ThreadInfo info = bean.getThreadInfo(new long[]{t.threadId()}, true, true)[0];
            threads.add(Map.of("id", Long.toString(info.getThreadId()), "name", info.getThreadName(),
                "state", info.getThreadState().name(), "ownerId", Long.toString(info.getLockOwnerId())));
        }
        Map<String,Object> proof = Map.of("mode",mode,"pid",ProcessHandle.current().pid(),"threads",threads,
            "deadlockedIds",dead == null ? List.of() : Arrays.stream(dead).mapToObj(Long::toString).sorted().toList());
        Files.writeString(Path.of(args[1]), new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(proof), StandardOpenOption.CREATE_NEW);
        System.out.println("READY");
        Thread.sleep(Long.MAX_VALUE);
    }
}
