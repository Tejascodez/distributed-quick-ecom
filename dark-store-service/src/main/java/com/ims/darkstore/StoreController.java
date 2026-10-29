package com.ims.darkstore;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import com.uber.h3core.H3Core;
import com.ims.common.security.Caller;
import com.ims.common.security.Roles;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/stores")
class StoreController {

    record StoreView(UUID id, String name, String address, double lat, double lng, String h3Cell,
                     int serviceableRadiusMeters, String status) {

        static StoreView of(DarkStore s) {
            return new StoreView(s.getId(), s.getName(), s.getAddress(), s.getLocation().getY(),
                    s.getLocation().getX(), Long.toHexString(s.getH3Cell()), s.getServiceableRadiusMeters(),
                    s.getStatus().name());
        }
    }

    record CreateStoreRequest(@NotBlank String name, @NotBlank String address, double lat, double lng,
                              @Positive int serviceableRadiusMeters) {
    }

    private static final GeometryFactory WGS84 = new GeometryFactory(new PrecisionModel(), 4326);

    private final DarkStoreRepository stores;
    private final ProductRepository products;
    private final H3Core h3;
    private final int h3Resolution;

    StoreController(DarkStoreRepository stores, ProductRepository products, H3Core h3,
                    @Value("${ims.h3-resolution}") int h3Resolution) {
        this.stores = stores;
        this.products = products;
        this.h3 = h3;
        this.h3Resolution = h3Resolution;
    }

    /** Maps a customer's coordinates to the dark store that will fulfil their cart. */
    @GetMapping("/serviceable")
    StoreView serviceable(@RequestParam double lat, @RequestParam double lng) {
        return stores.findServiceable(lat, lng).map(StoreView::of)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Location not serviceable"));
    }

    record DeliveryCheck(UUID storeId, boolean delivers) {
    }

    /** Used by order-service at checkout: can this store deliver to the customer's address? */
    @GetMapping("/{id}/delivers-to")
    DeliveryCheck deliversTo(@PathVariable UUID id, @RequestParam double lat, @RequestParam double lng) {
        return new DeliveryCheck(id, stores.deliversTo(id, lat, lng));
    }

    @GetMapping("/{id}")
    StoreView get(@PathVariable UUID id) {
        return stores.findById(id).map(StoreView::of)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    StoreView create(Caller caller, @Valid @RequestBody CreateStoreRequest req) {
        caller.requireAnyRole(Roles.ADMIN);
        var point = WGS84.createPoint(new Coordinate(req.lng(), req.lat()));
        long cell = h3.latLngToCell(req.lat(), req.lng(), h3Resolution);
        return StoreView.of(stores.save(new DarkStore(req.name(), req.address(), point, cell,
                req.serviceableRadiusMeters())));
    }

    /** Price lookup used by order-service to snapshot authoritative prices at checkout. */
    @GetMapping("/products")
    List<Product> products(@RequestParam List<UUID> ids) {
        return products.findAllById(ids);
    }
}
