package com.medha.urlshortenerservice.repository;

import com.medha.urlshortenerservice.domain.UrlMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UrlMappingRepository extends JpaRepository<UrlMapping, Long> {

    Optional<UrlMapping> findByShortCode(String shortCode);

    boolean existsByShortCode(String shortCode);

    /**
     * Reconciles a batch of pending Redis click counts into the durable
     * MySQL row. Applied as a single atomic UPDATE (rather than
     * read-modify-write) so concurrent flush cycles can never lose clicks.
     */
    @Modifying
    @Query("UPDATE UrlMapping u SET u.clickCount = u.clickCount + :increment WHERE u.shortCode = :shortCode")
    int incrementClickCount(@Param("shortCode") String shortCode, @Param("increment") long increment);

    long deleteByShortCode(String shortCode);
}
