package com.Goderium.BridgeNH.common.bridge;

import com.Goderium.BridgeNH.util.BridgeException;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/** The HTTP threads enqueue work; only the owning game thread may drain it. */
public class GameQueue {
    private final ArrayBlockingQueue<Ticket<?>> pending;

    public GameQueue(int capacity) {
        pending = new ArrayBlockingQueue<Ticket<?>>(capacity);
    }

    public <T> Ticket<T> submit(Callable<T> action, long timeoutMillis) {
        return submit(action, timeoutMillis, new Control());
    }

    public <T> Ticket<T> submit(Callable<T> action, long timeoutMillis, Control control) {
        Ticket<T> ticket = new Ticket<T>(action, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis), control);
        synchronized (control) {
            control.onCancel(() -> {
                ticket.recordCancelledBefore();
                ticket.result.completeExceptionally(new BridgeException("cancelled", "Request was cancelled before execution."));
                pending.remove(ticket);
            });
            if (!control.isCancelled()) {
                if (!pending.offer(ticket)) throw new BridgeException("busy", "The game-thread request queue is full.");
                ticket.enqueued = true;
                control.submittedTickets++;
            }
        }
        return ticket;
    }

    public void drain(int limit) {
        long budget = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(4);
        for (int i = 0; i < limit && System.nanoTime() < budget; i++) {
            Ticket<?> ticket = pending.poll();
            if (ticket == null) return;
            ticket.execute();
        }
    }

    /** One extra owning-thread opportunity; never advances game ticks or retries a ticket. */
    public void drainIf(BooleanSupplier ready, Runnable prepare) {
        if (pending.isEmpty() || !ready.getAsBoolean()) return;
        prepare.run();
        if (ready.getAsBoolean()) drain(1);
    }

    public <T> T await(Ticket<T> ticket, long timeoutMillis) throws ExecutionException, InterruptedException {
        long awaited = System.nanoTime();
        try {
            return ticket.result.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            boolean pureRead = ticket.control.hasReadCancellation();
            boolean cancelled = ticket.cancelBeforeExecution();
            pending.remove(ticket);
            throw new BridgeException("timeout", pureRead
                    ? "The bounded pure read expired and was cancelled; no world action was submitted."
                    : cancelled
                    ? "Request expired before execution and was cancelled."
                    : "Request timed out after starting; its outcome may be unknown. Do not repeat an action automatically.");
        } catch (InterruptedException e) {
            ticket.cancelBeforeExecution();
            pending.remove(ticket);
            throw e;
        } finally {
            ticket.control.recordQueueAwait(System.nanoTime() - awaited);
        }
    }

    public static class Ticket<T> {
        private final Callable<T> action;
        private final long deadlineNanos;
        private final CompletableFuture<T> result = new CompletableFuture<T>();
        private final Control control;
        private final long submittedNanos = System.nanoTime();
        private boolean enqueued, claimed, cancelledBefore;

        Ticket(Callable<T> action, long deadlineNanos, Control control) {
            this.action = action;
            this.deadlineNanos = deadlineNanos;
            this.control = control;
            control.onCancel(() -> {
                recordCancelledBefore();
                result.completeExceptionally(new BridgeException("cancelled", "Request was cancelled before execution."));
            });
        }

        private void recordCancelledBefore() {
            synchronized (control) {
                if (enqueued && !claimed && !cancelledBefore) { cancelledBefore = true; control.cancelledBeforeTickets++; }
            }
        }

        public boolean cancelBeforeExecution() {
            return control.cancelBeforeExecution();
        }

        private void execute() {
            long started;
            synchronized (control) {
                if (!control.claim(deadlineNanos)) return;
                claimed = true; started = System.nanoTime();
                control.startedTickets++; control.queueNanos = Control.add(control.queueNanos, started - submittedNanos);
            }
            T value = null; Throwable failure = null;
            try {
                control.checkExecution();
                value = action.call();
            } catch (Throwable error) {
                failure = error;
            } finally {
                // Publish measured callback timing before completing the future observed by HTTP await.
                control.recordFinished(System.nanoTime() - started);
            }
            if (failure == null) result.complete(value); else result.completeExceptionally(failure);
        }
    }

    /** Shared with the authenticated cancel endpoint, including before submit. */
    public static class Control {
        private volatile Runnable executionGuard;
        @com.google.common.annotations.VisibleForTesting
        public void guardExecution(Runnable guard) { executionGuard = guard; }
        void checkExecution() { Runnable guard = executionGuard; if (guard != null) guard.run(); }
        private boolean started;
        private boolean cancelled;
        private Runnable cancellation;
        private Runnable readCancellation;
        private long submittedTickets, startedTickets, finishedTickets, cancelledBeforeTickets;
        private long queueNanos, callbackNanos, queueAwaitCalls, queueAwaitNanos, nativeAwaitCalls, nativeAwaitNanos;

        private static long add(long current, long elapsed) {
            long nonnegative = Math.max(0, elapsed);
            return current > Long.MAX_VALUE - nonnegative ? Long.MAX_VALUE : current + nonnegative;
        }
        private synchronized void recordFinished(long elapsed) { finishedTickets++; callbackNanos = add(callbackNanos, elapsed); }
        private synchronized void recordQueueAwait(long elapsed) { queueAwaitCalls++; queueAwaitNanos = add(queueAwaitNanos, elapsed); }
        synchronized void recordNativeAwait(long elapsed) { nativeAwaitCalls++; nativeAwaitNanos = add(nativeAwaitNanos, elapsed); }
        public synchronized long[] timingSnapshot() {
            return new long[] { submittedTickets, startedTickets, finishedTickets, cancelledBeforeTickets,
                queueNanos, callbackNanos, queueAwaitCalls, queueAwaitNanos, nativeAwaitCalls, nativeAwaitNanos };
        }

        public synchronized boolean cancelBeforeExecution() {
            if (started && readCancellation == null) return false;
            cancelled = true;
            if (readCancellation != null) readCancellation.run();
            if (cancellation != null) cancellation.run();
            return true;
        }

        public synchronized boolean isCancelled() { return cancelled; }

        /** Only a registered pure read may be cancelled after starting. Existing action semantics are unchanged. */
        public synchronized void onReadCancellation(Runnable listener) {
            readCancellation = listener;
            if (cancelled) listener.run();
        }

        public synchronized void clearReadCancellation(Runnable listener) {
            if (readCancellation == listener) readCancellation = null;
        }
        private synchronized boolean hasReadCancellation() { return readCancellation != null; }

        private synchronized void onCancel(Runnable listener) {
            cancellation = listener;
            if (cancelled) listener.run();
        }

        private synchronized boolean claim(long deadline) {
            if (cancelled) return false;
            if (System.nanoTime() >= deadline) {
                cancelBeforeExecution();
                return false;
            }
            started = true;
            return true;
        }
    }
}
