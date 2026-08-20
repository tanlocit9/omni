package com.omni.platform.modules.scheduler.dependencies;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.omni.platform.modules.scheduler.dependencies.models.DatasetManifest;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Optional;

/**
 * Cached decorator for ManifestReader with TTL-based expiration.
 * 
 * <p>Wraps any ManifestReader implementation and adds Caffeine-based caching
 * to reduce I/O pressure on MinIO. Cache entries expire after the configured TTL.
 * 
 * <p>Cache key: DatasetRef (dataset + partition)
 * Cache value: Optional<DatasetManifest> (cached even when absent to avoid repeated lookups)
 * 
 * <p>Default TTL: 60 seconds
 * Default max size: 500 entries
 * 
 * <p>Thread-safety: Caffeine Cache is thread-safe. This class is safe for concurrent use.
 * 
 * <p>Use case: Multiple scheduler threads checking the same dependencies within a short window
 * benefit from cached reads without hitting MinIO repeatedly.
 */
@Slf4j
public class CachedManifestReader implements ManifestReader {
    
    private final ManifestReader delegate;
    private final Cache<DatasetRef, Optional<DatasetManifest>> cache;
    
    /**
     * Create a cached reader with default TTL (60s) and max size (500).
     */
    public CachedManifestReader(ManifestReader delegate) {
        this(delegate, Duration.ofSeconds(60), 500);
    }
    
    /**
     * Create a cached reader with custom TTL and max size.
     * 
     * @param delegate underlying reader to cache
     * @param ttl time-to-live for cache entries
     * @param maxSize maximum number of cached manifests
     */
    public CachedManifestReader(ManifestReader delegate, Duration ttl, int maxSize) {
        this.delegate = delegate;
        this.cache = Caffeine.newBuilder()
            .expireAfterWrite(ttl)
            .maximumSize(maxSize)
            .recordStats()
            .build();
        
        log.info("CachedManifestReader initialized with ttl={}, maxSize={}", ttl, maxSize);
    }
    
    @Override
    public Optional<DatasetManifest> readManifest(DatasetRef datasetRef) throws ManifestReadException {
        try {
            Optional<DatasetManifest> cached = cache.get(datasetRef, key -> {
                log.debug("Cache miss for dataset={} partition={}, fetching from delegate",
                    key.getDataset(), key.getPartition());
                return delegate.readManifest(key);
            });
            
            log.debug("Cache hit for dataset={} partition={}, manifest present={}",
                datasetRef.getDataset(), datasetRef.getPartition(), cached.isPresent());
            
            return cached;
            
        } catch (RuntimeException e) {
            // Caffeine wraps checked exceptions in RuntimeException
            if (e.getCause() instanceof ManifestReadException) {
                throw (ManifestReadException) e.getCause();
            }
            throw e;
        }
    }
    
    @Override
    public boolean manifestExists(DatasetRef datasetRef) throws ManifestReadException {
        // Use readManifest to benefit from caching
        return readManifest(datasetRef).isPresent();
    }
    
    /**
     * Invalidate a specific manifest in the cache.
     * 
     * <p>Use this when you know a manifest has been updated externally and want
     * to force a fresh read on the next access.
     */
    public void invalidate(DatasetRef datasetRef) {
        cache.invalidate(datasetRef);
        log.debug("Invalidated cache for dataset={} partition={}",
            datasetRef.getDataset(), datasetRef.getPartition());
    }
    
    /**
     * Clear all cached manifests.
     */
    public void invalidateAll() {
        cache.invalidateAll();
        log.info("Invalidated all cached manifests");
    }
    
    /**
     * Get cache statistics for monitoring.
     * 
     * @return Caffeine cache stats (hit rate, miss rate, eviction count, etc.)
     */
    public com.github.benmanes.caffeine.cache.stats.CacheStats getStats() {
        return cache.stats();
    }
    
    /**
     * Log cache statistics at INFO level.
     */
    public void logStats() {
        com.github.benmanes.caffeine.cache.stats.CacheStats stats = cache.stats();
        log.info("ManifestReader cache stats: hitRate={}, missRate={}, evictionCount={}, size={}", 
            String.format("%.2f%%", stats.hitRate() * 100),
            String.format("%.2f%%", stats.missRate() * 100),
            stats.evictionCount(),
            cache.estimatedSize());
    }
}
