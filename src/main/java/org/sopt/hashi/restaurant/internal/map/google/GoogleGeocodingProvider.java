package org.sopt.hashi.restaurant.internal.map.google;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;
import org.sopt.hashi.restaurant.internal.map.GeocodingProvider;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.Failure;
import org.sopt.hashi.restaurant.internal.map.GeocodingResult.FailureKind;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

final class GoogleGeocodingProvider implements GeocodingProvider {

    static final String ENDPOINT = "https://geocode.googleapis.com/v4/geocode/address";
    static final String FIELD_MASK = "results.location,results.granularity,results.postalAddress.regionCode,"
            + "results.postalAddress.administrativeArea,results.addressComponents.longText,"
            + "results.addressComponents.shortText,results.addressComponents.types,results.types";
    private static final int MAX_ADDRESS_LENGTH = 255;
    static final int MAX_CONCURRENT_CALLS = 4;

    private final GoogleGeocodingProperties properties;
    private final GoogleGeocodingResponseParser parser = new GoogleGeocodingResponseParser();
    private final UnaryOperator<ClientHttpRequestFactory> requestFactoryDecorator;
    private final DnsResolver dnsResolver;
    private final Semaphore callSlots = new Semaphore(MAX_CONCURRENT_CALLS);

    GoogleGeocodingProvider(GoogleGeocodingProperties properties) {
        this(properties, UnaryOperator.identity());
    }

    // Test seam redirects transport to loopback/mocks; the production endpoint has no configurable override.
    GoogleGeocodingProvider(GoogleGeocodingProperties properties,
                           UnaryOperator<ClientHttpRequestFactory> requestFactoryDecorator) {
        this(properties, requestFactoryDecorator, SystemDefaultDnsResolver.INSTANCE);
    }

    GoogleGeocodingProvider(GoogleGeocodingProperties properties,
                           UnaryOperator<ClientHttpRequestFactory> requestFactoryDecorator,
                           DnsResolver dnsResolver) {
        properties.validateEnabled();
        this.properties = properties;
        this.requestFactoryDecorator = requestFactoryDecorator;
        this.dnsResolver = dnsResolver;
    }

    @Override
    public GeocodingResult geocode(String address) {
        if (!properties.enabled()) {
            return new Failure(FailureKind.DISABLED, null);
        }
        boolean isInvalid = address == null || address.isBlank() || address.length() > MAX_ADDRESS_LENGTH;
        if (isInvalid) {
            return new Failure(FailureKind.INVALID_REQUEST, null);
        }
        CallCancellation cancellation = new CallCancellation();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<GeocodingResult> future = executor.submit(() -> {
            if (!callSlots.tryAcquire()) {
                return new Failure(FailureKind.CAPACITY_EXCEEDED, null);
            }
            try {
                return execute(address, cancellation);
            } finally {
                // DNS may ignore interruption. Keep its slot until the underlying task actually finishes.
                callSlots.release();
            }
        });
        try {
            return future.get(properties.responseTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ignored) {
            return new Failure(FailureKind.TIMEOUT, null);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return new Failure(FailureKind.CANCELLED, null);
        } catch (ExecutionException ignored) {
            // Never attach a library exception: it can contain the URI, key or body.
            return new Failure(FailureKind.INVALID_RESPONSE, null);
        } finally {
            cancellation.cancel();
            future.cancel(true);
            // ExecutorService.close() would wait for a DNS resolver that ignores interruption.
            executor.shutdownNow();
        }
    }

    private GeocodingResult execute(String address, CallCancellation cancellation) {
        CloseableHttpClient client = null;
        try {
            client = createHttpClient();
            cancellation.register(client);
            CloseableHttpClient callClient = client;
            HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(client) {
                @Override
                protected ClassicHttpRequest createHttpUriRequest(HttpMethod method, URI uri) {
                    HttpUriRequestBase request = new HttpUriRequestBase(method.name(), uri);
                    cancellation.register(request);
                    return request;
                }
            };
            RestClient restClient = RestClient.builder()
                    .requestFactory(requestFactoryDecorator.apply(factory)).build();
            URI uri = UriComponentsBuilder.fromUriString(ENDPOINT)
                    .queryParam("address.addressLines", "{address}")
                    .queryParam("languageCode", "ja").queryParam("regionCode", "JP")
                    .encode().buildAndExpand(address).toUri();
            return restClient.get().uri(uri).accept(MediaType.APPLICATION_JSON)
                    .header("X-Goog-Api-Key", properties.apiKey())
                    .header("X-Goog-FieldMask", FIELD_MASK)
                    .exchange((request, response) -> {
                        try {
                            return readResponse(response);
                        } finally {
                            // Spring's Apache response close drains unread bytes. Abort first so oversized/error
                            // bodies are not downloaded during cleanup. This client belongs to only this call.
                            callClient.close(CloseMode.IMMEDIATE);
                            response.close();
                        }
                    }, false);
        } catch (ResourceAccessException failure) {
            return new Failure(isTimeout(failure)
                    ? FailureKind.TIMEOUT : FailureKind.CONNECTION_ERROR, null);
        } catch (RuntimeException ignored) {
            // Never attach a library exception: it can contain the URI, key or body.
            return new Failure(FailureKind.INVALID_RESPONSE, null);
        } finally {
            if (client != null) {
                client.close(CloseMode.IMMEDIATE);
            }
        }
    }

    private CloseableHttpClient createHttpClient() {
        ConnectionConfig connection = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(properties.connectTimeout().toMillis()))
                .setSocketTimeout(Timeout.ofMilliseconds(properties.responseTimeout().toMillis())).build();
        RequestConfig request = RequestConfig.custom().setAuthenticationEnabled(false)
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(properties.connectTimeout().toMillis()))
                .setResponseTimeout(Timeout.ofMilliseconds(properties.responseTimeout().toMillis())).build();
        // No system proxy/credentials, cookies, transparent compression, redirects or transport retries.
        return HttpClients.custom().disableAutomaticRetries().disableRedirectHandling()
                .disableCookieManagement().disableAuthCaching().disableContentCompression()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(dnsResolver).setDefaultConnectionConfig(connection).build())
                .setDefaultRequestConfig(request).build();
    }

    private GeocodingResult readResponse(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        if (status != 200) {
            // Classification needs no error message/details; avoid reading/storing arbitrary error bodies.
            return new Failure(classifyStatus(status), status);
        }
        MediaType contentType = response.getHeaders().getContentType();
        String encoding = response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING);
        boolean isJson = contentType != null && MediaType.APPLICATION_JSON.isCompatibleWith(contentType);
        boolean isEncoded = encoding != null && !encoding.equalsIgnoreCase("identity");
        if (!isJson || isEncoded) {
            return new Failure(FailureKind.INVALID_RESPONSE, status);
        }
        if (response.getHeaders().getContentLength() > properties.maxResponseBytes()) {
            return new Failure(FailureKind.RESPONSE_TOO_LARGE, status);
        }
        byte[] body = response.getBody().readNBytes(properties.maxResponseBytes() + 1);
        if (body.length > properties.maxResponseBytes()) {
            return new Failure(FailureKind.RESPONSE_TOO_LARGE, status);
        }
        return parser.parse(body);
    }

    private FailureKind classifyStatus(int status) {
        if (status >= 300 && status < 400) {
            return FailureKind.REDIRECT_REJECTED;
        }
        if (status >= 500 && status <= 599) {
            return FailureKind.TRANSIENT_ERROR;
        }
        return switch (status) {
            case 400, 422 -> FailureKind.INVALID_REQUEST;
            case 401, 403 -> FailureKind.ACCESS_DENIED;
            case 404 -> FailureKind.CONFIGURATION_ERROR;
            case 408 -> FailureKind.TIMEOUT;
            case 429 -> FailureKind.QUOTA_EXCEEDED;
            default -> FailureKind.INVALID_RESPONSE;
        };
    }

    private boolean isTimeout(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 10; depth++, cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static final class CallCancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<CloseableHttpClient> client = new AtomicReference<>();
        private final AtomicReference<HttpUriRequestBase> request = new AtomicReference<>();

        void register(CloseableHttpClient value) {
            client.set(value);
            if (cancelled.get()) {
                value.close(CloseMode.IMMEDIATE);
            }
        }

        void register(HttpUriRequestBase value) {
            request.set(value);
            if (cancelled.get()) {
                value.cancel();
            }
        }

        void cancel() {
            cancelled.set(true);
            HttpUriRequestBase currentRequest = request.get();
            if (currentRequest != null) {
                currentRequest.cancel();
            }
            CloseableHttpClient currentClient = client.get();
            if (currentClient != null) {
                currentClient.close(CloseMode.IMMEDIATE);
            }
        }
    }
}
