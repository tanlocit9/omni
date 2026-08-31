package com.omni.platform.modules.notifications.services;

import java.net.SocketTimeoutException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriBuilder;

@Service
public class ManualLatestSignalNotificationService {

    private final RestClient analyzerRestClient;

    public ManualLatestSignalNotificationService(
            @Qualifier("analyzerRestClient") RestClient analyzerRestClient) {
        this.analyzerRestClient = analyzerRestClient;
    }

    public LatestSignalNotificationResult sendLatest(String symbolKey) {
        String normalized = normalize(symbolKey);
        try {
            return analyzerRestClient.post()
                    .uri(builder -> latestUri(builder, normalized))
                    .retrieve()
                    .onStatus(status -> status.value() == 404, (request, response) -> {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No signal history found");
                    })
                    .onStatus(status -> status.value() == 400 || status.value() == 422, (request, response) -> {
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Malformed symbolKey");
                    })
                    .onStatus(status -> status.value() == 503, (request, response) -> {
                        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Analyzer publisher unavailable");
                    })
                    .onStatus(status -> status.is5xxServerError(), (request, response) -> {
                        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Analyzer request failed");
                    })
                    .body(LatestSignalNotificationResult.class);
        } catch (ResponseStatusException exc) {
            throw exc;
        } catch (ResourceAccessException exc) {
            HttpStatus status = hasTimeoutCause(exc)
                    ? HttpStatus.GATEWAY_TIMEOUT
                    : HttpStatus.SERVICE_UNAVAILABLE;
            throw new ResponseStatusException(status, "Analyzer is unavailable", exc);
        }
    }

    private static java.net.URI latestUri(UriBuilder builder, String symbolKey) {
        builder.path("/v1/signals/notifications/latest");
        return symbolKey == null
                ? builder.build()
                : builder.queryParam("symbolKey", symbolKey).build();
    }

    private static boolean hasTimeoutCause(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

    public record LatestSignalNotificationResult(
            boolean accepted,
            String status,
            String symbolKey,
            String newSignal,
            String signalDate,
            String generatedAt) {
    }
}
