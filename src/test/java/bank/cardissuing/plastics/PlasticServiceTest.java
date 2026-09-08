package bank.cardissuing.plastics;

import bank.cardissuing.audit.application.AuditService;
import bank.cardissuing.card.domain.*;
import bank.cardissuing.card.infrastructure.CardRepository;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.customer.domain.Customer;
import bank.cardissuing.hsm.infrastructure.HsmService;
import bank.cardissuing.plastics.application.PersoFileBuilder;
import bank.cardissuing.plastics.application.PlasticService;
import bank.cardissuing.plastics.application.PlasticSettings;
import bank.cardissuing.plastics.domain.Plastic;
import bank.cardissuing.plastics.domain.Plastic.Reason;
import bank.cardissuing.plastics.domain.Plastic.Status;
import bank.cardissuing.plastics.domain.PlasticBatch;
import bank.cardissuing.plastics.infrastructure.PlasticBatchRepository;
import bank.cardissuing.plastics.infrastructure.PlasticRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlasticServiceTest {

    @Mock PlasticRepository plastics;
    @Mock PlasticBatchRepository batches;
    @Mock CardRepository cards;
    @Mock HsmService hsm;
    @Mock AuditService audit;
    @Mock HsmService.HsmCardCryptoResult crypto;

    PlasticSettings settings = new PlasticSettings();
    PersoFileBuilder files = new PersoFileBuilder(settings);
    PlasticService service;
    Card card;
    List<Plastic> store = new ArrayList<>();
    AtomicLong ids = new AtomicLong(1);
    PlasticBatch lastBatch;

    @BeforeEach
    void setUp() {
        service = new PlasticService(plastics, batches, cards, hsm, files, settings, audit);
        card = new Card(new Customer("Ana Pérez", "555"), "4321", CardStatus.ACTIVE, LocalDate.of(2028, 6, 30));
        card.setId(7L);
        card.setEmbossedName("ANA PEREZ");
        card.setProduct(new CardProduct("P", "Prepago MX", CardType.PREPAID, PaymentType.PREPAID, CardNetwork.VISA, "453212", "MXN", null, true));
        when(cards.findById(7L)).thenReturn(Optional.of(card));
        when(cards.save(any())).thenAnswer(i -> i.getArgument(0));
        when(plastics.save(any())).thenAnswer(i -> { Plastic p = i.getArgument(0); if (p.getId() == null) { p.setId(ids.getAndIncrement()); store.add(p); } return p; });
        when(plastics.findById(anyLong())).thenAnswer(i -> store.stream().filter(p -> p.getId().equals(i.getArgument(0))).findFirst());
        when(plastics.countByCard(any())).thenAnswer(i -> (int) store.stream().filter(p -> p.getCard() == i.getArgument(0)).count());
        when(plastics.existsByCardAndStatusNotIn(any(), any())).thenAnswer(i -> store.stream().anyMatch(p -> p.getCard() == i.getArgument(0) && p.getStatus().open()));
        when(plastics.findByStatusOrderByCreatedAtAsc(Status.REQUESTED)).thenAnswer(i -> store.stream().filter(p -> p.getStatus() == Status.REQUESTED).toList());
        when(plastics.findByBatchOrderBySequenceAsc(any())).thenAnswer(i -> store.stream().filter(p -> p.getBatch() == i.getArgument(0)).toList());
        when(batches.save(any())).thenAnswer(i -> { PlasticBatch b = i.getArgument(0); if (b.getId() == null) b.setId(ids.getAndIncrement()); return b; });
        when(batches.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(lastBatch));
        when(batches.countByCreatedAtAfter(any())).thenReturn(0L);
        when(crypto.getPvv()).thenReturn("1234");
        when(crypto.getCvv2()).thenReturn("567");
        when(hsm.generateCardCryptograms(any(), any(), any(), any())).thenReturn(crypto);
    }

    private PlasticBatch build() { lastBatch = service.buildBatch(null, "ops"); return lastBatch; }

    private Card otherCard(long id, String last4, CardStatus status) {
        Card c = new Card(new Customer("Luis Gómez", "555"), last4, status, LocalDate.of(2027, 1, 31));
        c.setId(id); c.setProduct(card.getProduct()); c.setEmbossedName("LUIS GOMEZ");
        return c;
    }

    @Test
    void request_numbersPlastics_andRefusesASecondOpenOne() {
        Plastic p = service.request(card, Reason.NEW, "Calle 1", true, "mesa");
        assertEquals(1, p.getSequence());
        assertEquals(Status.REQUESTED, p.getStatus());
        assertEquals(LocalDate.of(2028, 6, 30), p.getExpiry());
        assertEquals("ANA PEREZ", p.getEmbossedName());
        assertEquals("LACPI-MX-01", p.getChipProfile());
        assertEquals("PLASTIC_ALREADY_OPEN", assertThrows(BusinessException.class,
                () -> service.request(card, Reason.NEW, null, false, null)).getErrorCode());
    }

    @Test
    void renewalPlastic_carriesTheNewExpiry() {
        Plastic p = service.request(card, Reason.RENEWAL, null, false, null);
        assertEquals(LocalDate.of(2031, 6, 30), p.getExpiry());
    }

    @Test
    void buildBatch_writesSealedEncryptedFile_withHsmValues() {
        Plastic p1 = service.request(card, Reason.NEW, null, true, null);
        Plastic p2 = service.request(otherCard(8L, "9999", CardStatus.CREATED), Reason.NEW, null, false, null);

        PlasticBatch b = build();

        assertEquals(2, b.getRecordCount());
        assertTrue(b.getBatchNumber().startsWith("EMB-"));
        assertEquals(64, b.getSha256Plain().length());
        String plain = new String(files.decrypt(b.getEncryptedFile(), b.getIv()), StandardCharsets.UTF_8);
        assertEquals(PersoFileBuilder.sha256(plain.getBytes(StandardCharsets.UTF_8)), b.getSha256Plain(), "seal matches what decrypts");
        assertTrue(plain.contains("REC|1|" + p1.getId() + "|1|7|453212|4321|453212******4321|ANA PEREZ|06/28|201|LACPI-MX-01|1234|567|Y|Prepago MX"), plain);
        assertTrue(plain.contains("REC|2|" + p2.getId() + "|1|8|453212|9999|"), plain);
        assertTrue(plain.endsWith("TRL|2\n"));
        assertTrue(store.stream().allMatch(p -> p.getStatus() == Status.IN_BATCH && p.getBatch() == b));
    }

    @Test
    void buildBatch_withNothingQueued_isRefused() {
        assertEquals("NO_PLASTICS_TO_BATCH", assertThrows(BusinessException.class, () -> service.buildBatch(null, null)).getErrorCode());
    }

    @Test
    void buildBatch_withoutTheHsm_fails() {
        service.request(card, Reason.NEW, null, false, null);
        when(hsm.generateCardCryptograms(any(), any(), any(), any())).thenThrow(new RuntimeException("connection refused"));
        assertEquals("HSM_UNAVAILABLE", assertThrows(BusinessException.class, () -> service.buildBatch(null, null)).getErrorCode());
    }

    @Test
    void sendThenProduced_failedPlasticsGoBackToTheQueue() {
        Plastic p1 = service.request(card, Reason.NEW, null, false, null);
        Plastic p2 = service.request(otherCard(8L, "1111", CardStatus.ACTIVE), Reason.NEW, null, false, null);
        PlasticBatch b = build();

        service.send(b.getId(), "ops");
        assertEquals(PlasticBatch.Status.SENT, b.getStatus());
        assertEquals(Status.SENT_TO_MANUFACTURER, p1.getStatus());

        service.produced(b.getId(), List.of(p2.getId()), "chip defect", "bureau");
        assertEquals(PlasticBatch.Status.PRODUCED, b.getStatus());
        assertEquals(1, b.getFailedCount());
        assertEquals(Status.PRODUCED, p1.getStatus());
        assertEquals(Status.REQUESTED, p2.getStatus());
        assertNull(p2.getBatch());
        assertEquals("chip defect", p2.getNote());
    }

    @Test
    void shipDeliverActivate_andWrongOrderIsRejected() {
        Plastic p = service.request(card, Reason.NEW, null, false, null);
        card.setStatus(CardStatus.CREATED);
        build(); service.send(lastBatch.getId(), null); service.produced(lastBatch.getId(), List.of(), null, null);

        assertEquals("PLASTIC_INVALID_STATE", assertThrows(BusinessException.class, () -> service.activate(p.getId(), null)).getErrorCode());
        service.ship(p.getId(), "DHL", "MX123", "ops");
        assertEquals(Status.SHIPPED, p.getStatus());
        assertEquals("MX123", p.getTrackingNumber());
        service.deliver(p.getId(), null);
        service.activate(p.getId(), "customer");
        assertEquals(Status.ACTIVATED, p.getStatus());
        assertEquals(CardStatus.ACTIVE, card.getStatus(), "activating the plastic activates a CREATED card");
    }

    @Test
    void activatingARenewal_movesTheCardExpiry() {
        Plastic p = service.request(card, Reason.RENEWAL, null, false, null);
        build(); service.send(lastBatch.getId(), null); service.produced(lastBatch.getId(), List.of(), null, null);
        service.ship(p.getId(), "DHL", "X", null); service.deliver(p.getId(), null); service.activate(p.getId(), null);
        assertEquals(LocalDate.of(2031, 6, 30), card.getExpiryDate());
    }

    @Test
    void replaceLost_destroysOld_blocksCard_andQueuesNumberTwo() {
        Plastic old = service.request(card, Reason.NEW, null, false, null);
        Plastic fresh = service.replace(old.getId(), Reason.REPLACEMENT_LOST, "Calle 2", true, "mesa");
        assertEquals(Status.DESTROYED, old.getStatus());
        assertEquals(CardStatus.BLOCKED, card.getStatus());
        assertEquals(2, fresh.getSequence());
        assertEquals(Reason.REPLACEMENT_LOST, fresh.getReason());
        assertEquals(Status.REQUESTED, fresh.getStatus());
        verify(audit).log(eq("BLOCK_CARD_PLASTIC_LOST"), eq("Card"), eq("7"), eq("mesa"));
    }

    @Test
    void replaceDamaged_doesNotBlock_andNonReplacementReasonIsRejected() {
        Plastic old = service.request(card, Reason.NEW, null, false, null);
        assertEquals("PLASTIC_INVALID_REASON", assertThrows(BusinessException.class,
                () -> service.replace(old.getId(), Reason.NEW, null, false, null)).getErrorCode());
        service.replace(old.getId(), Reason.REPLACEMENT_DAMAGED, null, false, null);
        assertEquals(CardStatus.ACTIVE, card.getStatus());
    }

    @Test
    void renewals_queueOnlyCardsWithoutAPlasticInProgress() {
        Card busy = otherCard(9L, "2222", CardStatus.ACTIVE);
        busy.setExpiryDate(LocalDate.now().plusDays(20));
        store.add(new Plastic(busy, 1, Reason.NEW, "X", busy.getExpiryDate(), "LACPI-MX-01", false, null, "x")); // open plastic
        card.setExpiryDate(LocalDate.now().plusDays(30));
        when(cards.findByStatusAndExpiryDateBefore(eq(CardStatus.ACTIVE), any())).thenReturn(List.of(card, busy));

        List<Plastic> out = service.renewals(60, "job");
        assertEquals(1, out.size());
        assertSame(card, out.get(0).getCard());
        assertEquals(Reason.RENEWAL, out.get(0).getReason());
    }
}
