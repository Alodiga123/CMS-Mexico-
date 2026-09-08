package bank.cardissuing.plastics.application;

import bank.cardissuing.audit.application.AuditService;
import bank.cardissuing.card.domain.Card;
import bank.cardissuing.card.domain.CardStatus;
import bank.cardissuing.card.infrastructure.CardRepository;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.common.exception.ResourceNotFoundException;
import bank.cardissuing.hsm.infrastructure.HsmService;
import bank.cardissuing.plastics.domain.Plastic;
import bank.cardissuing.plastics.domain.Plastic.Reason;
import bank.cardissuing.plastics.domain.Plastic.Status;
import bank.cardissuing.plastics.domain.PlasticBatch;
import bank.cardissuing.plastics.infrastructure.PlasticBatchRepository;
import bank.cardissuing.plastics.infrastructure.PlasticRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Plastics: from "this card needs a physical" to "the customer activated it".
 * Batching builds the personalization file with the HSM's PVV/CVV2 per plastic,
 * seals and encrypts it for the bureau; the rest is tracking each plastic through
 * production, shipping, delivery and activation, plus replacement and renewal.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlasticService {

    private final PlasticRepository plastics;
    private final PlasticBatchRepository batches;
    private final CardRepository cards;
    private final HsmService hsm;
    private final PersoFileBuilder files;
    private final PlasticSettings settings;
    private final AuditService audit;

    /** The messaging provider; optional so the service also runs without it (unit tests). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @lombok.Setter
    private bank.cardissuing.thirdparty.messaging.MessagingService messaging;

    private void notify(Runnable r) {
        if (messaging == null) return;
        try { r.run(); } catch (RuntimeException e) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Cardholder not notified: {}", e.getMessage()); }
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @lombok.Setter
    private bank.cardissuing.hsm.application.CardCryptoService cardCrypto;

    // ------------------------------------------------------------ request

    @Transactional
    public Plastic request(Card card, Reason reason, String deliveryAddress, boolean pinMailer, String by) {
        if (plastics.existsByCardAndStatusNotIn(card, Plastic.CLOSED)) {
            throw new BusinessException("PLASTIC_ALREADY_OPEN",
                    "Card " + card.getId() + " already has a plastic in progress", HttpStatus.CONFLICT);
        }
        int sequence = plastics.countByCard(card) + 1;
        LocalDate expiry = reason == Reason.RENEWAL && card.getExpiryDate() != null
                ? card.getExpiryDate().plusYears(settings.getRenewalValidityYears())
                : card.getExpiryDate();
        String name = card.getEmbossedName() != null ? card.getEmbossedName()
                : card.getCustomer() != null && card.getCustomer().getFullName() != null ? card.getCustomer().getFullName().toUpperCase() : "";
        Plastic p = plastics.save(new Plastic(card, sequence, reason, name, expiry, settings.getDefaultChipProfile(),
                pinMailer, deliveryAddress, by(by)));
        audit.log("REQUEST_PLASTIC", "Plastic", p.getId().toString(), by(by));
        log.info("Plastic #{} requested for card {} ({})", sequence, card.getId(), reason);
        return p;
    }

    @Transactional
    public Plastic requestForCard(Long cardId, Reason reason, String deliveryAddress, boolean pinMailer, String by) {
        return request(loadCard(cardId), reason == null ? Reason.NEW : reason, deliveryAddress, pinMailer, by);
    }

    // ------------------------------------------------------------ batch

    /** Everything REQUESTED goes into one file for the bureau. */
    @Transactional
    public PlasticBatch buildBatch(String manufacturer, String by) {
        List<Plastic> queue = plastics.findByStatusOrderByCreatedAtAsc(Status.REQUESTED);
        if (queue.isEmpty()) {
            throw new BusinessException("NO_PLASTICS_TO_BATCH", "There are no requested plastics to batch", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        String bureau = manufacturer != null && !manufacturer.isBlank() ? manufacturer : settings.getManufacturer();
        PlasticBatch batch = batches.save(new PlasticBatch(nextBatchNumber(), bureau, by(by)));

        List<PersoFileBuilder.Record> records = new ArrayList<>(queue.size());
        int recordNo = 0;
        for (Plastic p : queue) {
            Card card = p.getCard();
            String bin = card.getProduct() != null ? card.getProduct().getBin() : "";
            String pvv = null, cvv2 = null;
            if (cardCrypto != null && card.getPanEncrypted() != null) {
                String pan = cardCrypto.panOf(card).orElse(null);
                if (card.getPvv() == null) {
                    bank.cardissuing.hsm.application.CardCryptoService.Provisioned prov = cardCrypto.provisionPin(pan);
                    card.setPvv(prov.pvv()); card.setPvki(prov.pvki()); card.setCryptoProvisionedAt(java.time.LocalDateTime.now());
                    cards.save(card);
                    cardCrypto.rememberTestPin(card.getId(), prov.pin());
                }
                pvv = card.getPvv();
                cvv2 = cardCrypto.cvvs(pan, p.getExpiry() != null ? p.getExpiry() : card.getExpiryDate(), settings.getServiceCode()).cvv2();
            } else try {
                HsmService.HsmCardCryptoResult crypto = hsm.generateCardCryptograms(bin, card.getLast4(),
                        card.getProduct() != null ? card.getProduct().getPinBlockFormat() : "ISO-0",
                        card.getProduct() != null ? card.getProduct().getPvkIndex() : "PVK-01");
                if (crypto != null) { pvv = crypto.getPvv(); cvv2 = crypto.getCvv2(); }
            } catch (RuntimeException e) {
                throw new BusinessException("HSM_UNAVAILABLE",
                        "Cannot personalize without the HSM: " + e.getMessage(), HttpStatus.SERVICE_UNAVAILABLE);
            }
            records.add(new PersoFileBuilder.Record(++recordNo, p.getId(), p.getSequence(), card.getId(), bin, card.getLast4(),
                    p.getEmbossedName(), p.getExpiry(), settings.getServiceCode(), p.getChipProfile(), pvv, cvv2, p.isPinMailer(),
                    card.getProduct() != null ? card.getProduct().getProductName() : ""));
            p.toBatch(batch);
            plastics.save(p);
        }

        byte[] plain = PersoFileBuilder.bytes(files.build(batch.getBatchNumber(), bureau, records));
        PersoFileBuilder.Encrypted enc = files.encrypt(plain);
        batch.setRecordCount(records.size());
        batch.setSha256Plain(PersoFileBuilder.sha256(plain));
        batch.setEncryptedFile(enc.cipherText());
        batch.setIv(enc.ivBase64());
        batch = batches.save(batch);
        audit.log("BUILD_PLASTIC_BATCH", "PlasticBatch", batch.getId().toString(), by(by));
        log.info("Plastic batch {} built: {} records for {}, sha256 {}", batch.getBatchNumber(), records.size(), bureau, batch.getSha256Plain());
        return batch;
    }

    @Transactional
    public PlasticBatch send(Long batchId, String by) {
        PlasticBatch b = batch(batchId);
        b.send();
        for (Plastic p : plastics.findByBatchOrderBySequenceAsc(b)) { p.sent(); plastics.save(p); }
        audit.log("SEND_PLASTIC_BATCH", "PlasticBatch", b.getId().toString(), by(by));
        return batches.save(b);
    }

    /** The bureau reports back by plastic id. The ones it could not make go back to the queue. */
    @Transactional
    public PlasticBatch produced(Long batchId, Collection<Long> failedPlasticIds, String note, String by) {
        PlasticBatch b = batch(batchId);
        int failed = 0;
        for (Plastic p : plastics.findByBatchOrderBySequenceAsc(b)) {
            if (failedPlasticIds != null && failedPlasticIds.contains(p.getId())) {
                p.productionFailed(note != null ? note : "production failed in batch " + b.getBatchNumber());
                failed++;
            } else {
                p.produced();
            }
            plastics.save(p);
        }
        b.produced(failed);
        audit.log("PLASTIC_BATCH_PRODUCED", "PlasticBatch", b.getId().toString(), by(by));
        return batches.save(b);
    }

    // ------------------------------------------------------------ tracking

    @Transactional
    public Plastic ship(Long id, String carrier, String trackingNumber, String by) {
        Plastic p = get(id); p.ship(carrier, trackingNumber);
        audit.log("SHIP_PLASTIC", "Plastic", id.toString(), by(by));
        notify(() -> messaging.cardShipped(p.getCard(), carrier, trackingNumber));
        return plastics.save(p);
    }

    @Transactional
    public Plastic deliver(Long id, String by) {
        Plastic p = get(id); p.deliver();
        audit.log("DELIVER_PLASTIC", "Plastic", id.toString(), by(by));
        notify(() -> messaging.cardDelivered(p.getCard()));
        return plastics.save(p);
    }

    /** The customer activates the plastic; the card follows, and a renewal carries its new expiry. */
    @Transactional
    public Plastic activate(Long id, String by) {
        Plastic p = get(id);
        p.activate();
        Card card = p.getCard();
        if (card.getStatus() == CardStatus.CREATED) card.activate();
        if (p.getReason() == Reason.RENEWAL && p.getExpiry() != null) card.setExpiryDate(p.getExpiry());
        cards.save(card);
        audit.log("ACTIVATE_PLASTIC", "Plastic", id.toString(), by(by));
        return plastics.save(p);
    }

    @Transactional
    public Plastic returned(Long id, String note, String by) {
        Plastic p = get(id); p.returned(note);
        audit.log("RETURN_PLASTIC", "Plastic", id.toString(), by(by));
        return plastics.save(p);
    }

    @Transactional
    public Plastic destroy(Long id, String note, String by) {
        Plastic p = get(id); p.destroy(note);
        audit.log("DESTROY_PLASTIC", "Plastic", id.toString(), by(by));
        return plastics.save(p);
    }

    /** Lost, stolen or damaged: the old plastic dies, a new one is queued; lost/stolen also blocks the card. */
    @Transactional
    public Plastic replace(Long id, Reason reason, String deliveryAddress, boolean pinMailer, String by) {
        if (reason == null || !reason.name().startsWith("REPLACEMENT_")) {
            throw new BusinessException("PLASTIC_INVALID_REASON", "reason must be REPLACEMENT_LOST, REPLACEMENT_STOLEN or REPLACEMENT_DAMAGED", HttpStatus.BAD_REQUEST);
        }
        Plastic old = get(id);
        Card card = old.getCard();
        if (old.getStatus() != Status.DESTROYED) { old.destroy("replaced: " + reason); plastics.save(old); }
        if (reason == Reason.REPLACEMENT_LOST || reason == Reason.REPLACEMENT_STOLEN) {
            try { card.block(); cards.save(card); audit.log("BLOCK_CARD_PLASTIC_" + reason.name().substring(12), "Card", card.getId().toString(), by(by)); }
            catch (IllegalStateException e) { log.info("Card {} not blocked on replacement: {}", card.getId(), e.getMessage()); }
        }
        return request(card, reason, deliveryAddress, pinMailer, by);
    }

    /** Cards expiring within the window that have no plastic in progress get a RENEWAL queued. */
    @Transactional
    public List<Plastic> renewals(Integer withinDays, String by) {
        int window = withinDays != null ? withinDays : settings.getRenewalWindowDays();
        List<Plastic> out = new ArrayList<>();
        for (Card card : cards.findByStatusAndExpiryDateBefore(CardStatus.ACTIVE, LocalDate.now().plusDays(window + 1L))) {
            if (plastics.existsByCardAndStatusNotIn(card, Plastic.CLOSED)) continue;
            out.add(request(card, Reason.RENEWAL, null, false, by));
        }
        log.info("Renewals: {} plastics queued for cards expiring within {} days", out.size(), window);
        return out;
    }

    // ------------------------------------------------------------ queries

    public Plastic get(Long id) {
        return plastics.findById(id).orElseThrow(() -> new ResourceNotFoundException("Plastic", "id", id));
    }

    public List<Plastic> byCard(Long cardId) { return plastics.findByCardOrderBySequenceDesc(loadCard(cardId)); }

    public List<Plastic> byStatus(Status status) {
        return status == null ? plastics.findAllByOrderByCreatedAtDesc() : plastics.findByStatusInOrderByCreatedAtDesc(List.of(status));
    }

    public PlasticBatch batch(Long id) {
        return batches.findById(id).orElseThrow(() -> new ResourceNotFoundException("PlasticBatch", "id", id));
    }

    public List<PlasticBatch> batches() { return batches.findAllByOrderByCreatedAtDesc(); }

    public List<Plastic> inBatch(Long batchId) { return plastics.findByBatchOrderBySequenceAsc(batch(batchId)); }

    /** Dev/test only: what the bureau will read after decrypting. */
    public String preview(Long batchId) {
        if (!settings.isExposePlaintext()) {
            throw new BusinessException("PLAINTEXT_NOT_EXPOSED", "plastics.expose-plaintext is off", HttpStatus.FORBIDDEN);
        }
        PlasticBatch b = batch(batchId);
        return new String(files.decrypt(b.getEncryptedFile(), b.getIv()), java.nio.charset.StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------ helpers

    private String nextBatchNumber() {
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        long today = batches.countByCreatedAtAfter(LocalDate.now().atStartOfDay());
        return String.format("EMB-%s-%03d", day, today + 1);
    }

    private Card loadCard(Long cardId) {
        return cards.findById(cardId).orElseThrow(() -> new ResourceNotFoundException("Card", "id", cardId));
    }

    private static String by(String by) { return bank.cardissuing.common.security.CmsPrincipal.auditName(by); }
}
