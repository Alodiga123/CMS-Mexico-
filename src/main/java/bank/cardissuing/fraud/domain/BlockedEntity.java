package bank.cardissuing.fraud.domain;

import bank.cardissuing.customer.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A blocklist entry. Internal ones come from analysts; external ones from feeds
 * (scheme, industry) loaded through the same API. A hit is a decline, no score.
 */
@Entity
@Table(name = "fraud_blocklist", uniqueConstraints = @UniqueConstraint(name = "uk_block_type_value", columnNames = {"type", "value"}))
@Getter
@Setter
@NoArgsConstructor
public class BlockedEntity extends BaseEntity {

    public enum Type { MERCHANT_ID, COUNTRY, CARD }
    public enum Source { INTERNAL, EXTERNAL }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Type type;

    @Column(nullable = false, length = 80)
    private String value;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Source source = Source.INTERNAL;

    @Column(length = 300)
    private String reason;

    @Column(length = 80)
    private String addedBy;

    private boolean active = true;

    public BlockedEntity(Type type, String value, Source source, String reason, String addedBy) {
        this.type = type;
        this.value = value;
        this.source = source;
        this.reason = reason;
        this.addedBy = addedBy;
    }
}
