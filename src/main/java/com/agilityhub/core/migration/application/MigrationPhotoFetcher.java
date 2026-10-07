package com.agilityhub.core.migration.application;

import com.agilityhub.core.shared.domain.*;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Read-only GET, bounded bytes and total time, with no redirects and no source URL in an error or log. */
@Component
public class MigrationPhotoFetcher {
    public record Photo(String type, byte[] bytes) { }
    private final Environment environment;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    public MigrationPhotoFetcher(Environment environment) { this.environment = environment; }
    public Photo fetch(String source, int maxBytes) {
        CompletableFuture<HttpResponse<byte[]>> request = null;
        try {
            var uri = URI.create(source);
            if (!List.of("https", "http").contains(uri.getScheme()) || uri.getUserInfo() != null || uri.getHost() == null) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
            for (var address : InetAddress.getAllByName(uri.getHost())) {
                if ((address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress())
                        && !(environment.matchesProfiles("test") && address.isLoopbackAddress())) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
            }
            request = client.sendAsync(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build(), info -> new LimitedBody(maxBytes));
            var response = request.get(20, TimeUnit.SECONDS);
            if (response.statusCode() != 200) { throw new ApiException(ErrorCode.NOT_FOUND); }
            String type = response.headers().firstValue("Content-Type").orElse("").split(";", 2)[0].strip().toLowerCase(java.util.Locale.ROOT);
            if (!type.startsWith("image/")) { throw new ApiException(ErrorCode.FILE_TYPE_NOT_ALLOWED); }
            return new Photo(type, response.body());
        } catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new ApiException(ErrorCode.INTERNAL_ERROR); }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof ApiException api) { throw api; }
            throw new ApiException(ErrorCode.NOT_FOUND);
        } catch (TimeoutException | java.io.IOException | IllegalArgumentException failure) { throw new ApiException(ErrorCode.NOT_FOUND); }
        finally { if (request != null && !request.isDone()) { request.cancel(true); } }
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit; private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>(); private Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if ((long) bytes.size() + buffer.remaining() > limit) { subscription.cancel(); result.completeExceptionally(new ApiException(ErrorCode.FILE_TOO_LARGE)); return; }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
