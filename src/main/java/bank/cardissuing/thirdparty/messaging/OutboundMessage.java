package bank.cardissuing.thirdparty.messaging;

import bank.cardissuing.customer.domain.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Our copy of every message handed to the messaging provider: who it went to, what it
 * said (with the one-time code masked), what the provider answered and how many times
 * we tried. The outbox the retry job works from.
 */
@Entity
@Table(name = "outbound_messages", indexes = {
        @Index(name = "ix_outbound_msg_status", columnList = "status, created_at"),
        @Index(name = "ix_outbound_msg_card", columnList = "card_id")
})
@Getter
@Setter
@NoArgsConstructor
public class OutboundMessage extends BaseEntity {

    public enum Status { QUEUED, SENT, DELIVERED, FAILED }

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessagingProvider.Channel channel;

    @Column(nullable = false, length = 160)
    private String recipient;

    @Column(name = "card_id")
    private Long cardId;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(nullable = false, length = 40)
    private String template;

    @Column(columnDefinition = "text")
    private String text;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.QUEUED;

    @Column(name = "provider_mode", length = 20)
    private String providerMode;

    @Column(name = "provider_ref", length = 120)
    private String providerRef;

    private int attempts;

    @Column(name = "last_error", length = 480)
    private String lastError;

    @Column(name = "business_ref", length = 120)
    private String businessRef;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;
}
