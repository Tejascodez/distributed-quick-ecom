package com.ims.notification;

import java.time.Duration;
import java.util.UUID;

import com.ims.common.security.Caller;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** order-service, asked on the subscriber's behalf. */
@Component
class OrderClient {

    private final RestClient http;

    OrderClient(@Value("${ims.order-url}") String orderUrl) {
        // A subscribe waits on this, so a slow order-service must fail the subscribe rather than hang it.
        var requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(Duration.ofSeconds(2));
        requests.setReadTimeout(Duration.ofSeconds(2));
        this.http = RestClient.builder().baseUrl(orderUrl).requestFactory(requests).build();
    }

    /** Whether order-service lets the caller read the order; it does the ownership check itself. */
    boolean canRead(UUID orderId, Caller caller) {
        return http.get().uri("/api/v1/orders/{id}", orderId)
                .header(Caller.USER_ID_HEADER, caller.userId().toString())
                .header(Caller.ROLE_HEADER, caller.role())
                .exchange((request, response) -> response.getStatusCode().is2xxSuccessful());
    }
}
