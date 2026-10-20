package org.yameida.worktool.capabilities;

import okhttp3.WebSocketListener;
import org.junit.Test;
import org.yameida.worktool.protocol.LegacyMode;
import org.yameida.worktool.utils.WebSocketManager;
import static org.junit.Assert.*;

public class OfflineModeTest {
    @Test public void processStartsOfflineAndModeLocksBeforeExecutorsCanMix() {
        assertFalse(LegacyMode.isEnabled());
        // Invalid URL and Android Log stubs would throw if these constructors tried to connect.
        WebSocketManager inert = new WebSocketManager();
        WebSocketManager guarded = new WebSocketManager("not-a-url", new WebSocketListener() {});
        assertFalse(inert.confirm("command"));
        assertFalse(guarded.confirm("command"));
        inert.send("must remain local");
        guarded.send("must remain local");
        inert.close(1000, "test");
        guarded.close(1000, "test");
        assertTrue(WebSocketManager.webSocketManager.isEmpty());
        assertTrue(LegacyMode.configure(true));
        assertTrue(LegacyMode.isEnabled());
        LegacyMode.lock();
        assertFalse(LegacyMode.configure(false));
        assertTrue(LegacyMode.isEnabled());
    }
}
