package com.omni.platform.modules.scheduler.dependencies;

/**
 * Exception thrown when a dataset manifest cannot be read from object storage.
 * 
 * <p>This includes I/O errors, missing manifests, malformed JSON, or S3/MinIO connectivity issues.
 * The calling code can distinguish between missing manifests (not found) and actual errors
 * by catching this exception vs. receiving an empty Optional.
 */
public class ManifestReadException extends RuntimeException {
    
    public ManifestReadException(String message) {
        super(message);
    }
    
    public ManifestReadException(String message, Throwable cause) {
        super(message, cause);
    }
    
    /**
     * Create an exception for a missing manifest file.
     */
    public static ManifestReadException notFound(String dataset, String partition) {
        return new ManifestReadException(
            String.format("Manifest not found for dataset=%s partition=%s", dataset, partition)
        );
    }
    
    /**
     * Create an exception for JSON parsing or persisted-contract failures.
     */
    public static ManifestReadException invalidJson(String path, Throwable cause) {
        return new ManifestReadException(
            String.format("Failed to parse manifest JSON at path=%s", path),
            cause
        );
    }

    public static ManifestReadException invalidContract(String path, String reason) {
        return new ManifestReadException(
            String.format("Invalid manifest contract at path=%s: %s", path, reason)
        );
    }

    public static ManifestReadException unsupportedVersion(String path, int version) {
        return new ManifestReadException(
            String.format("Unsupported manifest version=%d at path=%s", version, path)
        );
    }

    public static ManifestReadException unsupportedSchemaVersion(
            String path,
            int schemaVersion
    ) {
        return new ManifestReadException(
            String.format(
                "Unsupported manifest schemaVersion=%d at path=%s",
                schemaVersion,
                path
            )
        );
    }
    
    /**
     * Create an exception for S3/MinIO I/O errors.
     */
    public static ManifestReadException ioError(String path, Throwable cause) {
        return new ManifestReadException(
            String.format("I/O error reading manifest at path=%s", path),
            cause
        );
    }
}
