package com.ims.order;

import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** Resolves a customer's delivery address from user-auth-service. */
@Component
class AddressClient {

    record AddressLocation(UUID id, UUID userId, double lat, double lng) {
    }

    private final RestClient http;

    AddressClient(@Value("${ims.auth-url}") String authUrl) {
        this.http = RestClient.create(authUrl);
    }

    /** Empty when the address doesn't exist or belongs to someone else. */
    Optional<AddressLocation> find(UUID userId, UUID addressId) {
        try {
            return Optional.ofNullable(http.get().uri("/internal/users/{userId}/addresses/{id}", userId, addressId)
                    .retrieve().body(AddressLocation.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
    }
}
