package org.yameida.worktool.capabilities;

import org.junit.Test;
import org.yameida.worktool.protocol.LocalBindingRequest;
import static org.junit.Assert.*;

public class LocalBindingRequestTest {
    @Test public void delayedCompletionCannotReuseNavigationOrAnOldGesture() {
        LocalBindingRequest valid = new LocalBindingRequest(4, 100);
        assertTrue(valid.consume(4, 599));
        assertFalse(valid.consume(4, 599));
        assertFalse(new LocalBindingRequest(4, 100).consume(5, 101));
        assertFalse(new LocalBindingRequest(4, 100).consume(4, 600));
    }
}
