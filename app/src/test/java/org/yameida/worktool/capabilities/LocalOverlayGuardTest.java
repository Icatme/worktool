package org.yameida.worktool.capabilities;

import org.junit.Test;
import org.yameida.worktool.protocol.LocalOverlayGuard;
import static org.junit.Assert.*;

public class LocalOverlayGuardTest {
    @Test public void onlyExactRecentLocalWindowsAreExempt() {
        LocalOverlayGuard guard = new LocalOverlayGuard();
        guard.record(11, 100);
        guard.record(12, 100);
        guard.record(-1, 100);
        assertTrue(guard.matches(11, 101));
        assertTrue(guard.matches(12, 1099));
        assertFalse(guard.matches(99, 101));
        assertFalse(guard.matches(-1, 101));
        assertFalse(guard.matches(12, 1100));
        for (int i = 20; i < 30; i++) guard.record(i, 200);
        assertFalse(guard.matches(11, 201));
    }
}
