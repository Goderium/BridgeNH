package com.Goderium.BridgeNH.common.qa;

import com.Goderium.BridgeNH.util.BridgeException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bridge tickets never convert a native global result into proof of run ownership. */
final class QaRunTracker {
    private final Map<String, Ticket> tickets = new LinkedHashMap<String, Ticket>();
    private Object server;
    private Object world;
    private Object lifecycle;
    private long invalidation;
    private Ticket latest;

    void bind(Object currentServer, Object currentWorld, Object currentLifecycle, long currentInvalidation) {
        if (server != currentServer || world != currentWorld || lifecycle != currentLifecycle || invalidation != currentInvalidation) {
            tickets.clear();
            latest = null;
            server = currentServer;
            world = currentWorld;
            lifecycle = currentLifecycle;
            invalidation = currentInvalidation;
        }
    }

    Ticket started(String selector, List<String> selectedIds, Object baseline, String startStatus, Object immediateResult) {
        Ticket ticket = new Ticket(selector, selectedIds, baseline, startStatus, immediateResult);
        tickets.put(ticket.id, ticket);
        latest = ticket;
        while (tickets.size() > 8) {
            Iterator<String> iterator = tickets.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
        return ticket;
    }

    Ticket find(String id) {
        Ticket ticket = tickets.get(id);
        if (ticket == null) throw new BridgeException("stale_run", "This runId is unknown or belongs to a previous world session.");
        return ticket;
    }

    Ticket latest() { return latest; }

    static final class Ticket {
        final String id = UUID.randomUUID().toString();
        final String selector;
        final List<String> selectedIds;
        final Object baseline;
        final Object immediateResult;
        final String startStatus;
        final long startedAt = System.nanoTime();

        Ticket(String selector, List<String> selectedIds, Object baseline, String startStatus, Object immediateResult) {
            this.selector = selector;
            this.selectedIds = Collections.unmodifiableList(new ArrayList<String>(selectedIds));
            this.baseline = baseline;
            this.startStatus = startStatus;
            this.immediateResult = "COMPLETED".equals(startStatus) && immediateResult != baseline ? immediateResult : null;
        }

        boolean owned() { return immediateResult != null; }

        Object report(Object nativeLatest) {
            if (owned()) return immediateResult;
            return nativeLatest != null && nativeLatest != baseline ? nativeLatest : null;
        }

        String state(Object nativeLatest, boolean nativeBatchActive) {
            if (owned()) return "completed";
            return nativeBatchActive && report(nativeLatest) == null ? "running" : "completion_unverified";
        }
    }
}
