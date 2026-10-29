package com.ims.auth;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.ims.common.security.Caller;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class AddressController {

    record AddressRequest(@NotBlank @Size(max = 30) String label, @NotBlank @Size(max = 200) String line1,
                          @Size(max = 200) String line2, @NotBlank @Size(max = 80) String city,
                          @Pattern(regexp = "\\d{6}") String pincode,
                          @DecimalMin("-90") @DecimalMax("90") double lat,
                          @DecimalMin("-180") @DecimalMax("180") double lng) {
    }

    record AddressView(UUID id, String label, String line1, String line2, String city, String pincode,
                       double lat, double lng, boolean isDefault) {

        static AddressView of(Address a) {
            return new AddressView(a.getId(), a.getLabel(), a.getLine1(), a.getLine2(), a.getCity(),
                    a.getPincode(), a.getLat(), a.getLng(), a.isDefault());
        }
    }

    /** What other services need to fulfil an order: where to deliver. */
    record AddressLocation(UUID id, UUID userId, double lat, double lng) {
    }

    private final AddressRepository addresses;

    AddressController(AddressRepository addresses) {
        this.addresses = addresses;
    }

    @GetMapping("/api/v1/users/me/addresses")
    List<AddressView> list(Caller caller) {
        return addresses.findByUserIdOrderByCreatedAtDesc(caller.userId()).stream().map(AddressView::of).toList();
    }

    @PostMapping("/api/v1/users/me/addresses")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    AddressView create(Caller caller, @Valid @RequestBody AddressRequest req) {
        Address address = new Address(caller.userId());
        apply(address, req);
        address.setDefault(addresses.countByUserId(caller.userId()) == 0); // first address becomes default
        return AddressView.of(addresses.save(address));
    }

    @PutMapping("/api/v1/users/me/addresses/{id}")
    @Transactional
    AddressView update(Caller caller, @PathVariable UUID id, @Valid @RequestBody AddressRequest req) {
        Address address = owned(caller, id);
        apply(address, req);
        return AddressView.of(address);
    }

    @PostMapping("/api/v1/users/me/addresses/{id}/default")
    @Transactional
    AddressView makeDefault(Caller caller, @PathVariable UUID id) {
        owned(caller, id);
        addresses.clearDefault(caller.userId());
        Address address = owned(caller, id); // reload: clearDefault cleared the persistence context
        address.setDefault(true);
        return AddressView.of(address);
    }

    @DeleteMapping("/api/v1/users/me/addresses/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(Caller caller, @PathVariable UUID id) {
        Address address = owned(caller, id);
        addresses.delete(address);
        addresses.flush();
        if (address.isDefault()) {
            addresses.findByUserIdOrderByCreatedAtDesc(caller.userId()).stream().findFirst()
                    .ifPresent(next -> next.setDefault(true));
        }
    }

    /** Service-to-service lookup (order-service at checkout). Not routed by Envoy, so not public. */
    @GetMapping("/internal/users/{userId}/addresses/{id}")
    AddressLocation location(@PathVariable UUID userId, @PathVariable UUID id) {
        return addresses.findByIdAndUserId(id, userId)
                .map(a -> new AddressLocation(a.getId(), a.getUserId(), a.getLat(), a.getLng()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private Address owned(Caller caller, UUID id) {
        return addresses.findByIdAndUserId(id, caller.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Address not found"));
    }

    private static void apply(Address address, AddressRequest req) {
        address.update(req.label(), req.line1(), req.line2(), req.city(), req.pincode(), req.lat(), req.lng());
    }
}
