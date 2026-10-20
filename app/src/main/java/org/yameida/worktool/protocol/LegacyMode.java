package org.yameida.worktool.protocol;

/** Explicit, process-local compatibility choice. Never restored from a saved server address. */
public final class LegacyMode {
    private static volatile boolean enabled;
    private static boolean locked;
    private LegacyMode() {}
    public static boolean isEnabled() { return enabled; }
    public static synchronized boolean configure(boolean value) {
        if (locked) return value == enabled;
        enabled = value;
        return true;
    }
    /** Once any accessibility executor starts, mode changes require a fresh app process. */
    public static synchronized void lock() { locked = true; }
    public static synchronized boolean isLocked() { return locked; }
}
