package org.sopt.hashi.restaurant.internal.map.places;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
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
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
final class GooglePlacesProvider implements PlacesProvider {

    static final String SEARCH_ENDPOINT = "https://places.googleapis.com/v1/places:searchText";
    static final String DETAILS_ENDPOINT = "https://places.googleapis.com/v1/places";
    static final String SEARCH_FIELD_MASK = "places.id,places.displayName,places.formattedAddress,places.location,"
            + "places.addressComponents,places.types,places.businessStatus,places.attributions,places.googleMapsUri";
    static final String DETAILS_FIELD_MASK = "id,displayName,formattedAddress,location,addressComponents,types,"
            + "businessStatus,attributions,googleMapsUri";
    static final int MAX_CONCURRENT_CALLS = 4;

    private static final int MAX_QUERY_LENGTH = 255;
    private static final int MAX_PLACE_ID_LENGTH = 255;
    private static final int MAX_ERROR_STATUS_BYTES = 8192;
    private static final Pattern PLACE_ID = Pattern.compile("[A-Za-z0-9._~-]{1," + MAX_PLACE_ID_LENGTH + "}");
    private static final ObjectMapper REQUEST_MAPPER = JsonMapper.builder().build();

    private final GooglePlacesProperties properties;
    private final GooglePlacesResponseParser parser = new GooglePlacesResponseParser();
    private final UnaryOperator<ClientHttpRequestFactory> requestFactoryDecorator;
    private final DnsResolver dnsResolver;
    private final Semaphore callSlots = new Semaphore(MAX_CONCURRENT_CALLS);

    GooglePlacesProvider(GooglePlacesProperties properties) {
        this(properties, UnaryOperator.identity());
    }

    // Test seam redirects transport to loopback/mocks; production endpoints cannot be overridden by configuration.
    GooglePlacesProvider(GooglePlacesProperties properties,
                         UnaryOperator<ClientHttpRequestFactory> requestFactoryDecorator) {
        this(properties, requestFactoryDecorator, SystemDefaultDnsResolver.INSTANCE);
    }

    GooglePlacesProvider(GooglePlacesProperties properties,
                         UnaryOperator<ClientHttpRequestFactory> requestFactoryDecorator,
                         DnsResolver dnsResolver) {
        properties.validateEnabled();
        this.properties = properties;
        this.requestFactoryDecorator = requestFactoryDecorator;
        this.dnsResolver = dnsResolver;
    }

    @Override
    public PlacesSearchResult search(String query) {
        if (!properties.enabled()) {
            return searchFailure(FailureKind.DISABLED, null);
        }
        String normalized = query == null ? "" : query.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_QUERY_LENGTH) {
            return searchFailure(FailureKind.INVALID_REQUEST, null);
        }
        byte[] body;
        try {
            body = REQUEST_MAPPER.writeValueAsBytes(new SearchRequest(normalized, "en", "JP", 5, false));
        } catch (JsonProcessingException ignored) {
            return searchFailure(FailureKind.INVALID_RESPONSE, null);
        }
        return invoke(HttpMethod.POST, URI.create(SEARCH_ENDPOINT), body, SEARCH_FIELD_MASK,
                parser::parseSearch, GooglePlacesProvider::searchFailure, null);
    }

    @Override
    public PlaceDetailsResult details(String placeId) {
        if (!properties.enabled()) {
            return detailsFailure(FailureKind.DISABLED, null);
        }
        if (placeId == null || !PLACE_ID.matcher(placeId).matches()) {
            return detailsFailure(FailureKind.INVALID_REQUEST, null);
        }
        URI uri = UriComponentsBuilder.fromUriString(DETAILS_ENDPOINT)
                .pathSegment(placeId).build().encode().toUri();
        return invoke(HttpMethod.GET, uri, null, DETAILS_FIELD_MASK, parser::parseDetails,
                GooglePlacesProvider::detailsFailure, PlaceDetailsResult.NoResults::new);
    }

    private <T> T invoke(HttpMethod method, URI uri, byte[] body, String fieldMask,
                         Function<byte[], T> successParser, BiFunction<FailureKind, Integer, T> failureFactory,
                         Supplier<T> notFoundFactory) {
        CallCancellation cancellation = new CallCancellation();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<T> future = executor.submit(() -> {
            if (!callSlots.tryAcquire()) {
                return failureFactory.apply(FailureKind.CAPACITY_EXCEEDED, null);
            }
            try {
                return execute(method, uri, body, fieldMask, successParser, failureFactory,
                        notFoundFactory, cancellation);
            } finally {
                // A resolver may ignore interruption. Keep its slot until the underlying call actually exits.
                callSlots.release();
            }
        });
        try {
            return future.get(properties.responseTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ignored) {
            return failureFactory.apply(FailureKind.TIMEOUT, null);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return failureFactory.apply(FailureKind.CANCELLED, null);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            log.warn("Google Places failure phase=await exceptionType={}",
                    (cause == null ? failure : cause).getClass().getName());
            return failureFactory.apply(FailureKind.INVALID_RESPONSE, null);
        } finally {
            cancellation.cancel();
            future.cancel(true);
            executor.shutdownNow();
        }
    }

    private <T> T execute(HttpMethod method, URI uri, byte[] body, String fieldMask,
                          Function<byte[], T> successParser, BiFunction<FailureKind, Integer, T> failureFactory,
                          Supplier<T> notFoundFactory, CallCancellation cancellation) {
        CloseableHttpClient client = null;
        try {
            client = createHttpClient();
            cancellation.register(client);
            CloseableHttpClient callClient = client;
            HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(client) {
                @Override
                protected ClassicHttpRequest createHttpUriRequest(HttpMethod requestMethod, URI requestUri) {
                    HttpUriRequestBase request = new HttpUriRequestBase(requestMethod.name(), requestUri);
                    cancellation.register(request);
                    return request;
                }
            };
            RestClient restClient = RestClient.builder()
                    .requestFactory(requestFactoryDecorator.apply(factory)).build();
            RestClient.RequestBodySpec request = restClient.method(method).uri(uri)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("X-Goog-Api-Key", properties.apiKey())
                    .header("X-Goog-FieldMask", fieldMask);
            if (body != null) {
                request.contentType(MediaType.APPLICATION_JSON).body(body);
            }
            return request.exchange((sent, response) -> {
                try {
                    return readResponse(response, successParser, failureFactory, notFoundFactory);
                } finally {
                    callClient.close(CloseMode.IMMEDIATE);
                    response.close();
                }
            }, false);
        } catch (ResourceAccessException failure) {
            return failureFactory.apply(isTimeout(failure)
                    ? FailureKind.TIMEOUT : FailureKind.CONNECTION_ERROR, null);
        } catch (RuntimeException failure) {
            log.warn("Google Places failure phase=execute exceptionType={}", failure.getClass().getName());
            return failureFactory.apply(FailureKind.INVALID_RESPONSE, null);
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
        return HttpClients.custom().disableAutomaticRetries().disableRedirectHandling()
                .disableCookieManagement().disableAuthCaching().disableContentCompression()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDnsResolver(dnsResolver).setDefaultConnectionConfig(connection).build())
                .setDefaultRequestConfig(request).build();
    }

    private <T> T readResponse(ClientHttpResponse response, Function<byte[], T> successParser,
                               BiFunction<FailureKind, Integer, T> failureFactory,
                               Supplier<T> notFoundFactory) throws IOException {
        int status = response.getStatusCode().value();
        if (status != 200) {
            if (status == 404 && notFoundFactory != null) {
                return notFoundFactory.get();
            }
            if (status == 403) {
                return failureFactory.apply(classifyForbidden(response), status);
            }
            return failureFactory.apply(classifyStatus(status), status);
        }
        MediaType contentType = response.getHeaders().getContentType();
        String encoding = response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING);
        boolean isJson = contentType != null && MediaType.APPLICATION_JSON.isCompatibleWith(contentType);
        boolean isEncoded = encoding != null && !encoding.equalsIgnoreCase("identity");
        if (!isJson || isEncoded) {
            return failureFactory.apply(FailureKind.INVALID_RESPONSE, status);
        }
        if (response.getHeaders().getContentLength() > properties.maxResponseBytes()) {
            return failureFactory.apply(FailureKind.RESPONSE_TOO_LARGE, status);
        }
        byte[] responseBody = response.getBody().readNBytes(properties.maxResponseBytes() + 1);
        if (responseBody.length > properties.maxResponseBytes()) {
            return failureFactory.apply(FailureKind.RESPONSE_TOO_LARGE, status);
        }
        return successParser.apply(responseBody);
    }

    private FailureKind classifyForbidden(ClientHttpResponse response) {
        MediaType contentType = response.getHeaders().getContentType();
        String encoding = response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING);
        if (contentType == null || !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)
                || (encoding != null && !encoding.equalsIgnoreCase("identity"))
                || response.getHeaders().getContentLength() > MAX_ERROR_STATUS_BYTES) {
            return FailureKind.ACCESS_DENIED;
        }
        try {
            byte[] body = response.getBody().readNBytes(MAX_ERROR_STATUS_BYTES + 1);
            return body.length > MAX_ERROR_STATUS_BYTES ? FailureKind.ACCESS_DENIED : parser.classifyForbidden(body);
        } catch (IOException ignored) {
            return FailureKind.ACCESS_DENIED;
        }
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

    private static PlacesSearchResult searchFailure(FailureKind kind, Integer status) {
        return new PlacesSearchResult.Failure(kind, status);
    }

    private static PlaceDetailsResult detailsFailure(FailureKind kind, Integer status) {
        return new PlaceDetailsResult.Failure(kind, status);
    }

    private record SearchRequest(
            String textQuery,
            String languageCode,
            String regionCode,
            int pageSize,
            boolean includePureServiceAreaBusinesses
    ) {
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
