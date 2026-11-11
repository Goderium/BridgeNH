package com.Goderium.BridgeNH.client.lifecycle;

import com.Goderium.BridgeNH.common.bridge.GameQueue;
import com.Goderium.BridgeNH.util.BridgeException;
import com.Goderium.BridgeNH.util.JsonData;

import com.google.gson.JsonObject;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** One asynchronous lifecycle job. HTTP acknowledgement never touches Minecraft. */
public class LifecycleTasks {
    private Job current;
    private final LongSupplier nanos;

    public LifecycleTasks() { this(System::nanoTime); }

    @com.google.common.annotations.VisibleForTesting
    public LifecycleTasks(LongSupplier nanos) { this.nanos = nanos; }

    public synchronized JsonObject schedule(String operation, String sessionId, GameQueue.Control control,
            BooleanSupplier guard, Runnable action, BooleanSupplier completion) {
        if (current != null && current.active()) throw new BridgeException("busy", "A lifecycle operation is already pending; observe its ticket before another request.");
        current = new Job(operation, sessionId, control, guard, action, completion, nanos.getAsLong());
        JsonObject result = current.inspect();
        result.addProperty("status", "scheduled");
        result.addProperty("completion", "Runs after the scheduled response has been written and closed; verify snapshot.lifecycle and worldLoaded. Do not repeat automatically.");
        return result;
    }

    public synchronized void responseFinished(GameQueue.Control control, boolean delivered) {
        if (current == null || current.control != control || !"awaiting_response".equals(current.status)) return;
        if (delivered) current.status = "scheduled";
        else current.fail("cancelled", "The scheduled response was not delivered; lifecycle action was cancelled before execution.", "cancelled");
    }

    /** Called only on the client thread; actions may block during ordinary game startup/save. */
    public void tick() {
        Job job;
        synchronized (this) {
            job = current;
            if (job == null || !job.active()) return;
            if ("awaiting_response".equals(job.status)) {
                if (nanos.getAsLong() - job.createdNanos > 15000000000L) job.fail("cancelled", "Lifecycle acknowledgement expired before execution.", "cancelled");
                return;
            }
            if (!"scheduled".equals(job.status) && !"awaiting_world".equals(job.status)) return;
        }
        try {
            if ("scheduled".equals(job.status)) {
                if (!job.guard.getAsBoolean()) throw new BridgeException("stale_world", "Client world or server changed before lifecycle execution.");
                synchronized (this) { job.status = "running"; job.startedAtMillis = System.currentTimeMillis(); }
                job.action.run();
                synchronized (this) {
                    if (job.startupCanceled) { job.cancelStartup(); return; }
                    job.status = "awaiting_world"; job.waitStartedNanos = nanos.getAsLong();
                }
            }
            if (job.completion.getAsBoolean()) {
                synchronized (this) { job.status = "completed"; job.finishedAtMillis = System.currentTimeMillis(); }
            } else if (nanos.getAsLong() - job.waitStartedNanos > 60000000000L) {
                throw new BridgeException("timeout", "Lifecycle action started but its completion was not observed within 60 seconds. Inspect client state; do not repeat automatically.");
            }
        } catch (Throwable failure) {
            synchronized (this) {
                if (job.startupCanceled) { job.cancelStartup(); return; }
                job.fail(failure instanceof BridgeException ? ((BridgeException) failure).code : "lifecycle_failed",
                        failure instanceof BridgeException ? failure.getMessage() : "Lifecycle operation failed: " + failure.getClass().getSimpleName(),
                        "stale_world".equals(failure instanceof BridgeException ? ((BridgeException) failure).code : "") && job.startedAtMillis == 0 ? "cancelled" : "failed");
            }
        }
    }

    public synchronized JsonObject inspect() {
        if (current != null) return current.inspect();
        JsonObject result = new JsonObject();
        result.addProperty("status", "idle");
        return result;
    }

    public synchronized boolean active() { return current != null && current.active(); }

    /** A nested native query may finish while launchIntegratedServer is still on the stack. */
    public synchronized void startupCanceled() {
        if (current == null || !current.active() || !"client.load_world".equals(current.operation)) return;
        current.startupCanceled = true;
        if (!"running".equals(current.status)) current.cancelStartup();
    }

    private static class Job {
        final String ticketId = UUID.randomUUID().toString();
        final String operation;
        final String sessionId;
        final GameQueue.Control control;
        final BooleanSupplier guard;
        final Runnable action;
        final BooleanSupplier completion;
        final long createdNanos;
        final long scheduledAtMillis = System.currentTimeMillis();
        volatile String status = "awaiting_response";
        long startedAtMillis;
        long finishedAtMillis;
        long waitStartedNanos;
        String errorCode;
        String errorMessage;
        boolean startupCanceled;

        Job(String operation, String sessionId, GameQueue.Control control, BooleanSupplier guard, Runnable action, BooleanSupplier completion, long createdNanos) {
            this.operation = operation;
            this.sessionId = sessionId;
            this.control = control;
            this.guard = guard;
            this.action = action;
            this.completion = completion;
            this.createdNanos = createdNanos;
        }

        boolean active() { return !"completed".equals(status) && !"failed".equals(status) && !"cancelled".equals(status); }

        void fail(String code, String message, String state) {
            status = state;
            errorCode = code;
            errorMessage = JsonData.bounded(message, 512);
            finishedAtMillis = System.currentTimeMillis();
        }

        void cancelStartup() {
            fail("startup_cancelled", "The native startup confirmation was canceled. Restart the client before another world load.", "cancelled");
        }

        JsonObject inspect() {
            JsonObject result = new JsonObject();
            result.addProperty("ticketId", ticketId);
            result.addProperty("operation", operation);
            result.addProperty("sessionId", sessionId);
            result.addProperty("status", status);
            result.addProperty("scheduledAtMillis", scheduledAtMillis);
            if (startedAtMillis != 0) result.addProperty("startedAtMillis", startedAtMillis);
            if (finishedAtMillis != 0) result.addProperty("finishedAtMillis", finishedAtMillis);
            if (errorCode != null) {
                JsonObject error = new JsonObject();
                error.addProperty("code", errorCode);
                error.addProperty("message", errorMessage);
                result.add("error", error);
            }
            return result;
        }
    }
}
