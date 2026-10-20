package org.yameida.worktool.network;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Android-free HTTP execution boundary, exercised against localhost-only fixtures. */
public final class HttpEngine {
    public static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private final OkHttpClient client;
    public static final class Result {
        public final int status;
        public final String body;
        public final IOException error;
        Result(int status, String body, IOException error) {
            this.status = status;
            this.body = body;
            this.error = error;
        }
    }
    public HttpEngine(OkHttpClient client) { this.client = client; }

    public void enqueue(Request request, Consumer<Result> completion) {
        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException exception) {
                completion.accept(new Result(0, "", exception));
            }
            @Override public void onResponse(Call call, Response response) {
                Result result;
                try (Response closed = response) {
                    ResponseBody body = closed.body();
                    result = new Result(closed.code(),
                            body == null ? "" : new String(readBounded(body, MAX_RESPONSE_BYTES), StandardCharsets.UTF_8),
                            null);
                } catch (IOException exception) {
                    result = new Result(0, "", exception);
                }
                completion.accept(result);
            }
        });
    }

    public byte[] download(Request request, int maxBytes) throws IOException {
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null)
                throw new IOException("Download was not accepted");
            return readBounded(response.body(), maxBytes);
        }
    }

    private static byte[] readBounded(ResponseBody body, int maxBytes) throws IOException {
        if (body.contentLength() > maxBytes) throw new IOException("Response exceeds size limit");
        try (InputStream input = body.byteStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > maxBytes) throw new IOException("Response exceeds size limit");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
}
