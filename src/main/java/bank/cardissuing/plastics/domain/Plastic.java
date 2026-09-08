package bank.cardissuing.plastics.domain;

import bank.cardissuing.card.domain.Card;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.customer.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.EnumSet;

/**
 * The physical card: one card can have several plastics over its life (the first
 * one, a replacement, a renewal), each numbered. A plastic is requested, batched for
 * the manufacturer, produced, shipped, delivered and finally activated; or it comes
 * back, or it is destroyed when replaced.
 */
@Entity
@Table(name = "plastics", indexes = {
        @Index(name = "ix_plastic_card_status", columnList = "card_id,status"),
        @Index(name = "ix_plastic_batch", columnList = "batch_id")
})
@Getter
@Setter
@NoArgsConstructor
public class Plastic extends BaseEntity {

    public enum Status {
        REQUESTED, IN_BATCH, SENT_TO_MANUFACTURER, PRODUCED, SHIPPED, DELIVERED, ACTIVATED, RETURNED, DESTROYED;
        public boolean open() { return !CLOSED.contains(this); }
    }
    public static final EnumSet<Status> CLOSED = EnumSet.of(Status.ACTIVATED, Status.RETURNED, Status.DESTROYED);

    public enum Reason { NEW, REPLACEMENT_LOST, REPLACEMENT_STOLEN, REPLACEMENT_DAMAGED, RENEWAL }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "card_id", nullable = false)
    private Card card;

    /** 1 for the first plastic of a card, 2 for the next... */
    private int sequence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Status status = Status.REQUESTED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private Reason reason;

    @Column(length = 26)
    private String embossedName;

    private LocalDate expiry;

    @Column(length = 40)
    private String chipProfile;

    private boolean pinMailer;

    @Column(length = 500)
    private String deliveryAddress;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id")
    private PlasticBatch batch;

    @Column(length = 40)
    private String carrier;

    @Column(length = 80)
    private String trackingNumber;

    @Column(length = 500)
    private String note;

    @Column(length = 80)
    private String requestedBy;

    private LocalDateTime batchedAt;
    private LocalDateTime sentAt;
    private LocalDateTime producedAt;
    private LocalDateTime shippedAt;
    private LocalDateTime deliveredAt;
    private LocalDateTime activatedAt;
    private LocalDateTime closedAt;

    public Plastic(Card card, int sequence, Reason reason, String embossedName, LocalDate expiry,
                   String chipProfile, boolean pinMailer, String deliveryAddress, String requestedBy) {
        this.card = card;
        this.sequence = sequence;
        this.reason = reason;
        this.embossedName = embossedName;
        this.expiry = expiry;
        this.chipProfile = chipProfile;
        this.pinMailer = pinMailer;
        this.deliveryAddress = deliveryAddress;
        this.requestedBy = requestedBy;
    }

    public void toBatch(PlasticBatch batch) {
        require(Status.REQUESTED, "batch");
        this.batch = batch;
        this.status = Status.IN_BATCH;
        this.batchedAt = LocalDateTime.now();
    }

    public void sent() {
        require(Status.IN_BATCH, "send");
        this.status = Status.SENT_TO_MANUFACTURER;
        this.sentAt = LocalDateTime.now();
    }

    public void produced() {
        require(Status.SENT_TO_MANUFACTURER, "mark produced");
        this.status = Status.PRODUCED;
        this.producedAt = LocalDateTime.now();
    }

    /** The bureau could not make it: back to the queue for the next batch. */
    public void productionFailed(String why) {
        require(Status.SENT_TO_MANUFACTURER, "fail production of");
        this.status = Status.REQUESTED;
        this.batch = null;
        this.batchedAt = null;
        this.sentAt = null;
        this.note = why;
    }

    public void ship(String carrier, String trackingNumber) {
        require(Status.PRODUCED, "ship");
        this.status = Status.SHIPPED;
        this.carrier = carrier;
        this.trackingNumber = trackingNumber;
        this.shippedAt = LocalDateTime.now();
    }

    public void deliver() {
        require(Status.SHIPPED, "deliver");
        this.status = Status.DELIVERED;
        this.deliveredAt = LocalDateTime.now();
    }

    public void activate() {
        require(Status.DELIVERED, "activate");
        this.status = Status.ACTIVATED;
        this.activatedAt = LocalDateTime.now();
        this.closedAt = this.activatedAt;
    }

    public void returned(String why) {
        if (status != Status.SHIPPED && status != Status.DELIVERED) throw conflict("return");
        this.status = Status.RETURNED;
        this.note = why;
        this.closedAt = LocalDateTime.now();
    }

    public void destroy(String why) {
        if (status == Status.DESTROYED) throw conflict("destroy");
        this.status = Status.DESTROYED;
        this.note = why;
        this.closedAt = LocalDateTime.now();
    }

    private void require(Status expected, String action) {
        if (status != expected) throw conflict(action);
    }

    private BusinessException conflict(String action) {
        return new BusinessException("PLASTIC_INVALID_STATE",
                "Cannot " + action + " a plastic in status " + status, HttpStatus.CONFLICT);
    }
}
