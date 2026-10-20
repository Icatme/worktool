import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.yameida.worktool.network.HttpEngine;
import org.yameida.worktool.protocol.UploadOutcome;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class HttpEngineTest {
    private MockWebServer server;
    private OkHttpClient client;
    private HttpEngine engine;
    @Before public void start() throws IOException {
        server = new MockWebServer();
        server.start(java.net.InetAddress.getLoopbackAddress(), 0);
        client = new OkHttpClient.Builder().retryOnConnectionFailure(false)
                .callTimeout(2, TimeUnit.SECONDS).build();
        engine = new HttpEngine(client);
    }
    @After public void stop() throws IOException {
        server.shutdown();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }
    private HttpEngine.Result request() throws Exception {
        CompletableFuture<HttpEngine.Result> future = new CompletableFuture<>();
        engine.enqueue(new Request.Builder().url(server.url("/fixture")).build(), future::complete);
        return future.get(4, TimeUnit.SECONDS);
    }
    @Test public void successPreservesStatusAndBusinessBody() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"code\":200}"));
        HttpEngine.Result result = request();
        assertTrue(UploadOutcome.accepted(result.status, 200));
        assertEquals("{\"code\":200}", result.body);
        assertNull(result.error);
    }
    @Test public void httpFailureCannotBecomeSuccess() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("{\"code\":200}"));
        assertFalse(UploadOutcome.accepted(request().status, 200));
    }
    @Test public void delayedResponseDoesNotCompleteEarly() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"code\":200}").setBodyDelay(300, TimeUnit.MILLISECONDS));
        CompletableFuture<HttpEngine.Result> future = new CompletableFuture<>();
        engine.enqueue(new Request.Builder().url(server.url("/delay")).build(), future::complete);
        assertNotNull(server.takeRequest(1, TimeUnit.SECONDS));
        assertFalse(future.isDone());
        assertEquals(200, future.get(3, TimeUnit.SECONDS).status);
    }
    @Test public void disconnectCompletesOnceWithFailure() throws Exception {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        AtomicInteger completions = new AtomicInteger();
        CompletableFuture<HttpEngine.Result> future = new CompletableFuture<>();
        engine.enqueue(new Request.Builder().url(server.url("/disconnect")).build(), result -> {
            completions.incrementAndGet();
            future.complete(result);
        });
        assertNotNull(future.get(3, TimeUnit.SECONDS).error);
        assertEquals(1, completions.get());
    }
    @Test public void oversizedChunkedResponseIsRejected() throws Exception {
        char[] oversized = new char[HttpEngine.MAX_RESPONSE_BYTES + 1];
        java.util.Arrays.fill(oversized, 'x');
        server.enqueue(new MockResponse().setChunkedBody(new String(oversized), 8192));
        HttpEngine.Result result = request();
        assertEquals(0, result.status);
        assertNotNull(result.error);
    }
    @Test public void failedDownloadIsNotShared() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("not a file"));
        try {
            engine.download(new Request.Builder().url(server.url("/missing")).build(), 1024);
            fail("Expected download rejection");
        } catch (IOException expected) { /* expected */ }
    }
}
