package bank.cardissuing.fraud.guild.domain;

import bank.cardissuing.customer.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * The last answer the guild's online verification list (SVL) gave about a card or a
 * merchant. Cached so the authorizer asks the guild at most once per window and never
 * waits on it twice for the same subject.
 */
@Entity
@Table(name = "guild_verifications", indexes = {
        @Index(name = "ix_guild_verif_subject", columnList = "subject_type, subject", unique = true)
})
@Getter
@Setter
@NoArgsConstructor
public class GuildVerification extends BaseEntity {

    public enum Subject { CARD, MERCHANT }

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 10)
    private Subject subjectType;

    /** CARD: the card id; MERCHANT: the merchant id. */
    @Column(nullable = false, length = 60)
    private String subject;

    private boolean listed;

    @Column(length = 60)
    private String folio;

    @Column(length = 300)
    private String reason;

    /** True when the guild could not be reached and the answer is the fail-open default. */
    private boolean degraded;

    private LocalDateTime checkedAt;
    private LocalDateTime validUntil;

    public GuildVerification(Subject subjectType, String subject) {
        this.subjectType = subjectType;
        this.subject = subject;
    }

    public boolean fresh(LocalDateTime now) { return validUntil != null && now.isBefore(validUntil); }
}
