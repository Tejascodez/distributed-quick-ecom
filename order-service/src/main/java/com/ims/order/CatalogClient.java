package com.ims.order;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Fetches authoritative prices from dark-store-service; client-sent prices are never trusted. */
@Component
class CatalogClient {

    record ProductPrice(UUID id, String name, BigDecimal basePrice) {
    }

    record DeliveryCheck(UUID storeId, boolean delivers) {
    }

    private final RestClient http;

    CatalogClient(@Value("${ims.catalog-url}") String catalogUrl) {
        this.http = RestClient.create(catalogUrl);
    }

    List<ProductPrice> prices(Collection<UUID> productIds) {
        return http.get()
                .uri(b -> b.path("/api/v1/stores/products").queryParam("ids", productIds.toArray()).build())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
    }

    /** Whether the store's delivery radius covers this location. */
    boolean deliversTo(UUID storeId, double lat, double lng) {
        DeliveryCheck check = http.get()
                .uri(b -> b.path("/api/v1/stores/{id}/delivers-to").queryParam("lat", lat).queryParam("lng", lng)
                        .build(storeId))
                .retrieve().body(DeliveryCheck.class);
        return check != null && check.delivers();
    }
}
