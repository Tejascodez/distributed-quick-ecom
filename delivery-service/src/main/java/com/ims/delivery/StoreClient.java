package com.ims.delivery;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class StoreClient {

    record StoreLocation(UUID id, double lat, double lng) {
    }

    private final RestClient http;

    StoreClient(@Value("${ims.store-url}") String storeUrl) {
        this.http = RestClient.create(storeUrl);
    }

    StoreLocation location(UUID storeId) {
        return http.get().uri("/api/v1/stores/{id}", storeId).retrieve().body(StoreLocation.class);
    }
}
