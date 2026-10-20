package org.yameida.worktool.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/** Main-thread event correlation for window IDs observed directly from our floating views. */
public final class LocalOverlayGuard {
    private final Map<Integer, Long> windows = new LinkedHashMap<>();
    public void record(int windowId, long now) {
        if (windowId < 0) return;
        windows.put(windowId, now + 1000);
        while (windows.size() > 8) windows.remove(windows.keySet().iterator().next());
    }
    public boolean matches(int windowId, long now) {
        Long deadline = windows.get(windowId);
        return deadline != null && now < deadline;
    }
}
