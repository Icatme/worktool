package org.yameida.worktool.protocol;

/** Main-thread, single-completion guard while a local floating control attaches. */
public final class LocalBindingRequest {
    private final long generation;
    private final long deadline;
    private boolean consumed;
    public LocalBindingRequest(long generation, long requestedAt) {
        this.generation = generation;
        this.deadline = requestedAt + 500;
    }
    public boolean consume(long currentGeneration, long now) {
        if (consumed) return false;
        consumed = true;
        return generation == currentGeneration && now < deadline;
    }
}
