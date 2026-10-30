package com.ims.delivery;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Store locations from dark-store-service. Stores don't move, so each is fetched once and cached. */
@Component
class StoreClient {

    record StoreLocation(UUID id, double lat, double lng) {
    }

    private final RestClient http;
    private final Map<UUID, StoreLocation> cache = new ConcurrentHashMap<>();

    StoreClient(@Value("${ims.store-url}") String storeUrl) {
        this.http = RestClient.create(storeUrl);
    }

    StoreLocation location(UUID storeId) {
        return cache.computeIfAbsent(storeId,
                id -> http.get().uri("/api/v1/stores/{id}", id).retrieve().body(StoreLocation.class));
    }
}
