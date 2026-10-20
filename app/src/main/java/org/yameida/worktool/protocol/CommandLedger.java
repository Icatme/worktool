package org.yameida.worktool.protocol;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-process delivery identity: envelope ID + list position, never a payload hash.
 * Active work has no TTL. This is deliberately not cross-process exactly-once delivery.
 */
public final class CommandLedger {
    public enum Admission { ACCEPTED, DUPLICATE, FULL }
    private enum State { QUEUED, RUNNING, FINISHED }
    private static final class Entry {
        State state = State.QUEUED;
        long expiresAt;
        boolean control;
    }
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final int capacity;
    private final long retentionMillis;

    public CommandLedger(int capacity, long retentionMillis) {
        if (capacity < 1 || retentionMillis < 1) throw new IllegalArgumentException();
        this.capacity = capacity;
        this.retentionMillis = retentionMillis;
    }

    public static String identity(String envelopeId, int position) {
        if (envelopeId == null || envelopeId.trim().isEmpty() || position < 0)
            throw new IllegalArgumentException("A stable envelope ID and position are required");
        return envelopeId.length() + ":" + envelopeId + ":" + position;
    }

    public synchronized Admission reserve(String key, long now) {
        entries.entrySet().removeIf(e ->
                e.getValue().state == State.FINISHED && now >= e.getValue().expiresAt);
        if (entries.containsKey(key)) return Admission.DUPLICATE;
        if (entries.size() >= capacity) return Admission.FULL;
        entries.put(key, new Entry());
        return Admission.ACCEPTED;
    }

    public synchronized boolean start(String key) {
        Entry entry = entries.get(key);
        if (entry == null || entry.state != State.QUEUED) return false;
        entry.state = State.RUNNING;
        return true;
    }

    /** Queue cancellation must remain available under backpressure. Called serially by intake. */
    public synchronized Admission reserveControl(String key, long now) {
        Admission admission = reserve(key, now);
        if (admission == Admission.ACCEPTED) {
            entries.get(key).control = true;
            return admission;
        }
        if (admission == Admission.DUPLICATE) return admission;
        // Reserve a separate bounded control budget. Never evict completed side-effect commands:
        // doing so would allow an ordinary redelivery to execute again inside its retention window.
        int controls = 0;
        for (Entry entry : entries.values()) if (entry.control) controls++;
        if (controls >= 128) return Admission.FULL;
        Entry entry = new Entry();
        entry.control = true;
        entries.put(key, entry);
        return Admission.ACCEPTED;
    }

    public synchronized void finish(String key, long now) {
        Entry entry = entries.get(key);
        if (entry != null) {
            entry.state = State.FINISHED;
            entry.expiresAt = now + retentionMillis;
        }
    }

    public synchronized void releaseQueued() {
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().state == State.QUEUED) iterator.remove();
        }
    }
}
