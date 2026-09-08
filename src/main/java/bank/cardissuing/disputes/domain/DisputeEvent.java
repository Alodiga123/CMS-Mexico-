package bank.cardissuing.disputes.domain;

import bank.cardissuing.customer.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One line of a dispute's timeline: what happened, when, by whom. */
@Entity
@Table(name = "dispute_events", indexes = @Index(name = "ix_event_dispute", columnList = "dispute_id"))
@Getter
@Setter
@NoArgsConstructor
public class DisputeEvent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "dispute_id", nullable = false)
    private Dispute dispute;

    @Column(nullable = false, length = 40)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private Dispute.Status fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(length = 24)
    private Dispute.Status toStatus;

    @Column(length = 500)
    private String note;

    @Column(length = 80)
    private String performedBy;

    public DisputeEvent(Dispute dispute, String action, Dispute.Status from, Dispute.Status to, String note, String by) {
        this.dispute = dispute;
        this.action = action;
        this.fromStatus = from;
        this.toStatus = to;
        this.note = note;
        this.performedBy = by;
    }
}
