package io.github.wochen5770.talkweave.model;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.wochen5770.talkweave.runtime.RemoteFailure;
import java.io.*;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;

/** Parse bounded raw usage before the library can coerce missing/overflowing counts into integers. */
final class CompatibleUsageCapture {
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    volatile TokenUsage usage = TokenUsage.unknown();

    ClientHttpResponse intercept(ClientHttpResponse response) throws IOException {
        if (!response.getStatusCode().is2xxSuccessful()) return response;
        try {
            byte[] bytes = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) throw new IOException("Response size limit exceeded");
            JsonNode parsed = JSON.readTree(bytes);
            if (!(parsed instanceof ObjectNode object)) throw new IOException("Invalid compatible response");
            JsonNode reported = object.remove("usage");
            if (reported != null && !reported.isNull()) {
                if (reported.isObject()) {
                    @SuppressWarnings("unchecked") Map<String, ?> fields = JSON.convertValue(reported, Map.class);
                    usage = TokenUsage.fromCompatibleUsage(fields);
                } else usage = new TokenUsage(null, null, null, TokenUsage.Status.INVALID);
            }
            // Library usage metadata is intentionally not used; it cannot represent unknown accurately.
            byte[] sanitized = JSON.writeValueAsBytes(object);
            return new ClientHttpResponse() {
                @Override public HttpStatusCode getStatusCode() throws IOException { return response.getStatusCode(); }
                @Override public String getStatusText() throws IOException { return response.getStatusText(); }
                @Override public void close() { response.close(); }
                @Override public InputStream getBody() { return new ByteArrayInputStream(sanitized); }
                @Override public HttpHeaders getHeaders() {
                    var headers = new HttpHeaders(); headers.putAll(response.getHeaders()); headers.setContentLength(sanitized.length); return headers;
                }
            };
        } catch (IOException | RuntimeException failure) {
            response.close();
            throw new RemoteFailure(RemoteFailure.Source.MODEL, RemoteFailure.Kind.INVALID_RESPONSE);
        }
    }
}
