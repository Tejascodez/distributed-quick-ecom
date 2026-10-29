package com.ims.darkstore;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DarkStoreRepository extends JpaRepository<DarkStore, UUID> {

    /** Nearest ACTIVE store whose delivery radius covers the point; uses the geography GiST index. */
    @Query(value = """
            SELECT * FROM dark_stores s
            WHERE s.status = 'ACTIVE'
              AND ST_DWithin(s.location::geography,
                             ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                             s.serviceable_radius_meters)
            ORDER BY s.location::geography <-> ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography
            LIMIT 1
            """, nativeQuery = true)
    Optional<DarkStore> findServiceable(double lat, double lng);

    /** Whether this specific ACTIVE store's delivery radius covers the point (store areas can overlap). */
    @Query(value = """
            SELECT EXISTS (
                SELECT 1 FROM dark_stores s
                WHERE s.id = :storeId AND s.status = 'ACTIVE'
                  AND ST_DWithin(s.location::geography,
                                 ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography,
                                 s.serviceable_radius_meters))
            """, nativeQuery = true)
    boolean deliversTo(UUID storeId, double lat, double lng);
}
