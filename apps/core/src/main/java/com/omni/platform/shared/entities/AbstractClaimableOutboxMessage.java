package com.omni.platform.shared.entities;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;

@MappedSuperclass
@Getter
@Setter
public abstract class AbstractClaimableOutboxMessage extends AuditableEntity {

    @Column(nullable = false)
    private Integer attempts = 0;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "claimed_by")
    private String claimedBy;

    @Column(name = "claim_until")
    private Instant claimUntil;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;
}
