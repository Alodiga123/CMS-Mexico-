package bank.cardissuing.thirdparty.messaging;

import bank.cardissuing.card.domain.Card;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.thirdparty.messaging.MessagingProvider.Channel;
import bank.cardissuing.thirdparty.messaging.OutboundMessage.Status;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Every message the CMS sends a cardholder goes through here: it is written to the
 * outbox first, handed to the provider, and retried by the job if the provider was
 * down. The business callers (fraud, plastics, disputes) never fail because a text
 * could not be sent; they log and move on.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MessagingService {

    private final OutboundMessageRepository messages;
    private final MessagingProvider provider;
    private final MessagingSettings settings;

    // ------------------------------------------------------------ business templates

    /** The one-time code of a step-up. The stored copy keeps the code masked. */
    public OutboundMessage otp(Card card, String otp, int ttlMin) {
        String text = "Tu codigo para confirmar la compra es " + otp + ". Vence en " + ttlMin + " min. Si no fuiste tu, llama al banco.";
        String stored = settings.isMaskOtp() ? text.replace(otp, "******") : text;
        return send(settings.getDefaultChannel(), phoneOf(card), "OTP", text, stored, card, "STEPUP");
    }

    public OutboundMessage cardShipped(Card card, String carrier, String tracking) {
        String text = "Tu tarjeta terminada en " + card.getLast4() + " va en camino con " + carrier + " (guia " + tracking + ").";
        return send(settings.getDefaultChannel(), phoneOf(card), "CARD_SHIPPED", text, text, card, "PLASTIC");
    }

    public OutboundMessage cardDelivered(Card card) {
        String text = "Tu tarjeta terminada en " + card.getLast4() + " fue entregada. Activala desde la app o llamando al banco.";
        return send(settings.getDefaultChannel(), phoneOf(card), "CARD_DELIVERED", text, text, card, "PLASTIC");
    }

    public OutboundMessage disputeResolved(Card card, Long disputeId, String outcome) {
        String text = "Tu aclaracion " + disputeId + " de la tarjeta terminada en " + card.getLast4() + " se resolvio: " + outcome + ".";
        return send(settings.getDefaultChannel(), phoneOf(card), "DISPUTE_RESOLVED", text, text, card, "DISPUTE-" + disputeId);
    }

    /** A free message from the console (a test of the provider, an ad hoc notice). */
    public OutboundMessage adHoc(Channel channel, String to, String text, String ref) {
        if (to == null || to.isBlank()) throw new BusinessException("MESSAGE_RECIPIENT_REQUIRED", "to is required", HttpStatus.BAD_REQUEST);
        if (text == null || text.isBlank()) throw new BusinessException("MESSAGE_TEXT_REQUIRED", "text is required", HttpStatus.BAD_REQUEST);
        return send(channel == null ? settings.getDefaultChannel() : channel, to.trim(), "AD_HOC", text, text, null, ref);
    }

    // ------------------------------------------------------------ outbox

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OutboundMessage send(Channel channel, String to, String template, String text, String storedText, Card card, String ref) {
        OutboundMessage m = new OutboundMessage();
        m.setChannel(channel);
        m.setRecipient(to == null ? "" : to);
        m.setTemplate(template);
        m.setText(storedText);
        m.setBusinessRef(ref);
        m.setProviderMode(provider.mode());
        if (card != null) { m.setCardId(card.getId()); if (card.getCustomer() != null) m.setCustomerId(card.getCustomer().getId()); }
        if (to == null || to.isBlank()) {
            m.setStatus(Status.FAILED);
            m.setLastError("no phone number on file");
            return messages.save(m);
        }
        m = messages.save(m);
        return attempt(m, text);
    }

    private OutboundMessage attempt(OutboundMessage m, String text) {
        m.setAttempts(m.getAttempts() + 1);
        try {
            MessagingProvider.Delivery d = provider.send(new MessagingProvider.Message(m.getChannel(), m.getRecipient(), text, m.getBusinessRef()));
            m.setProviderRef(d.providerRef());
            m.setStatus("DELIVERED".equalsIgnoreCase(d.status()) ? Status.DELIVERED : Status.SENT);
            m.setSentAt(LocalDateTime.now());
            m.setLastError(null);
        } catch (RuntimeException e) {
            String err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            m.setLastError(err.length() > 480 ? err.substring(0, 480) : err);
            m.setStatus(m.getAttempts() >= settings.getMaxAttempts() ? Status.FAILED : Status.QUEUED);
            log.warn("Message {} ({}) to {} not sent (attempt {}): {}", m.getId(), m.getTemplate(), SimulatedMessagingProvider.mask(m.getRecipient()), m.getAttempts(), err);
        }
        return messages.save(m);
    }

    /** A retry from the console: a FAILED or QUEUED message goes out again with its stored text (the code, if masked, is gone; a step-up gets a new challenge instead). */
    @Transactional
    public OutboundMessage retry(Long id) {
        OutboundMessage m = get(id);
        if (m.getStatus() == Status.DELIVERED || m.getStatus() == Status.SENT) throw new BusinessException("MESSAGE_ALREADY_SENT", "Message " + id + " was already " + m.getStatus(), HttpStatus.CONFLICT);
        if (m.getRecipient() == null || m.getRecipient().isBlank()) throw new BusinessException("MESSAGE_RECIPIENT_REQUIRED", "Message " + id + " has no recipient", HttpStatus.CONFLICT);
        if ("OTP".equals(m.getTemplate()) && settings.isMaskOtp()) throw new BusinessException("MESSAGE_OTP_NOT_RETRYABLE", "A masked one-time code cannot be resent; the cardholder needs a new challenge", HttpStatus.CONFLICT);
        m.setStatus(Status.QUEUED);
        return attempt(m, m.getText());
    }

    /** The job: whatever the provider refused goes out again until it gives up. */
    @Scheduled(fixedDelayString = "${thirdparty.messaging.retry-interval-ms:30000}", initialDelayString = "${thirdparty.messaging.retry-initial-delay-ms:60000}")
    @Transactional
    public void flush() {
        List<OutboundMessage> queued = messages.findTop100ByStatusOrderByCreatedAtAsc(Status.QUEUED);
        if (queued.isEmpty()) return;
        if (!provider.health().up()) { log.info("Messaging provider down; {} messages wait", queued.size()); return; }
        int sent = 0;
        for (OutboundMessage m : queued) {
            if ("OTP".equals(m.getTemplate()) && settings.isMaskOtp()) { m.setStatus(Status.FAILED); m.setLastError("one-time code expired before the provider came back"); messages.save(m); continue; }
            if (attempt(m, m.getText()).getStatus() != Status.QUEUED) sent++;
        }
        log.info("Messaging outbox flushed: {} of {} sent", sent, queued.size());
    }

    // ------------------------------------------------------------ queries

    public OutboundMessage get(Long id) {
        return messages.findById(id).orElseThrow(() -> new BusinessException("MESSAGE_NOT_FOUND", "Message " + id + " not found", HttpStatus.NOT_FOUND));
    }

    public Page<OutboundMessage> page(Status status, Long cardId, Pageable p) {
        if (cardId != null) return messages.findByCardIdOrderByCreatedAtDesc(cardId, p);
        if (status != null) return messages.findByStatusOrderByCreatedAtDesc(status, p);
        return messages.findAllByOrderByCreatedAtDesc(p);
    }

    public List<OutboundMessage> byRef(String ref) { return messages.findByBusinessRefOrderByCreatedAtDesc(ref); }

    public long queued() { return messages.countByStatus(Status.QUEUED); }

    public MessagingProvider provider() { return provider; }

    private static String phoneOf(Card card) {
        return card.getCustomer() != null ? card.getCustomer().getPhoneNumber() : null;
    }
}
