package bank.cardissuing.disputes;

import bank.cardissuing.audit.application.AuditService;
import bank.cardissuing.card.domain.*;
import bank.cardissuing.card.infrastructure.CardRepository;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.customer.domain.Customer;
import bank.cardissuing.disputes.application.DisputeService;
import bank.cardissuing.disputes.application.DisputeService.OpenRequest;
import bank.cardissuing.disputes.domain.Dispute;
import bank.cardissuing.disputes.domain.Dispute.Outcome;
import bank.cardissuing.disputes.domain.Dispute.Status;
import bank.cardissuing.disputes.domain.DisputeEvidence;
import bank.cardissuing.disputes.domain.DisputeReason;
import bank.cardissuing.disputes.infrastructure.DisputeEventRepository;
import bank.cardissuing.disputes.infrastructure.DisputeEvidenceRepository;
import bank.cardissuing.disputes.infrastructure.DisputeReasonRepository;
import bank.cardissuing.disputes.infrastructure.DisputeRepository;
import bank.cardissuing.funds.application.FundsRouter;
import bank.cardissuing.funds.domain.AuthorizationHold;
import bank.cardissuing.funds.domain.FundsPort;
import bank.cardissuing.funds.infrastructure.AuthorizationHoldRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DisputeServiceTest {

    @Mock DisputeRepository disputes;
    @Mock DisputeReasonRepository reasons;
    @Mock DisputeEvidenceRepository evidences;
    @Mock DisputeEventRepository events;
    @Mock CardRepository cards;
    @Mock AuthorizationHoldRepository holds;
    @Mock FundsRouter router;
    @Mock FundsPort port;
    @Mock AuditService audit;

    DisputeService service;
    Card card;
    AuthorizationHold captured;
    DisputeReason reason;
    List<DisputeEvidence> evidenceStore = new ArrayList<>();
    AtomicLong ids = new AtomicLong(1);

    @BeforeEach
    void setUp() {
        service = new DisputeService(disputes, reasons, evidences, events, cards, holds, router, audit);
        card = new Card(new Customer(), "9001", CardStatus.ACTIVE, LocalDate.now().plusYears(2));
        card.setId(7L);
        card.setProduct(new CardProduct("P", "P", CardType.PREPAID, PaymentType.PREPAID, CardNetwork.VISA, "4", "MXN", null, true));
        captured = new AuthorizationHold(card, "AUTH-1", new BigDecimal("120"), "Tienda", "M1", "PURCHASE", LocalDateTime.now().plusDays(7));
        captured.setId(1L);
        captured.capture(new BigDecimal("120"));
        captured.setCreatedAt(LocalDateTime.now().minusDays(10));
        reason = new DisputeReason("13.1", "No recibido", "VISA", 120, 30, 45, false);

        when(cards.findById(7L)).thenReturn(Optional.of(card));
        when(holds.findByApprovalCode("AUTH-1")).thenReturn(Optional.of(captured));
        when(reasons.findByCode("13.1")).thenReturn(Optional.of(reason));
        when(disputes.existsByApprovalCodeAndStatusIn(anyString(), any())).thenReturn(false);
        when(disputes.save(any())).thenAnswer(i -> { Dispute d = i.getArgument(0); if (d.getId() == null) d.setId(ids.getAndIncrement()); return d; });
        when(disputes.findById(anyLong())).thenAnswer(i -> Optional.empty());
        when(router.forCard(card)).thenReturn(port);
        when(port.credit(eq(card), any(), anyString())).thenReturn("CR-1");
        when(port.debit(eq(card), any(), anyString())).thenReturn("DB-1");
        when(evidences.save(any())).thenAnswer(i -> { DisputeEvidence e = i.getArgument(0); e.setId(ids.getAndIncrement()); evidenceStore.add(e); return e; });
        when(evidences.findByDisputeOrderByCreatedAtAsc(any())).thenAnswer(i -> new ArrayList<>(evidenceStore));
    }

    private Dispute opened(boolean provisional) {
        Dispute d = service.open(new OpenRequest(7L, "AUTH-1", null, "13.1", "no llegó", provisional, "mesa"));
        when(disputes.findById(d.getId())).thenReturn(Optional.of(d));
        return d;
    }

    @Test
    void open_setsDeadlinesFromTheReason_andCreditsWhenProvisional() {
        Dispute d = opened(true);

        assertEquals(Status.OPENED, d.getStatus());
        assertEquals(new BigDecimal("120"), d.getAmount(), "defaults to the captured amount");
        assertEquals(captured.getCreatedAt().toLocalDate().plusDays(120), d.getChargebackDeadline());
        assertEquals(LocalDate.now().plusDays(45), d.getResolveBy());
        assertFalse(d.isDeadlineBreached());
        verify(port).credit(card, new BigDecimal("120"), "PROVISIONAL AUTH-1");
        assertEquals("CR-1", d.getCreditRef());
        verify(audit).log("OPEN_DISPUTE", "Dispute", d.getId().toString(), "mesa");
    }

    @Test
    void open_withoutProvisionalCredit_movesNoMoney() {
        opened(false);
        verify(port, never()).credit(any(), any(), any());
    }

    @Test
    void open_rejectsUnsettledAuthorization_andDuplicates_andOverAmount() {
        AuthorizationHold held = new AuthorizationHold(card, "AUTH-2", new BigDecimal("50"), "T", null, "PURCHASE", LocalDateTime.now().plusDays(7));
        when(holds.findByApprovalCode("AUTH-2")).thenReturn(Optional.of(held));
        assertEquals("DISPUTE_NOT_SETTLED", assertThrows(BusinessException.class,
                () -> service.open(new OpenRequest(7L, "AUTH-2", null, "13.1", null, false, null))).getErrorCode());

        when(disputes.existsByApprovalCodeAndStatusIn(eq("AUTH-1"), any())).thenReturn(true);
        assertEquals("DISPUTE_ALREADY_OPEN", assertThrows(BusinessException.class,
                () -> service.open(new OpenRequest(7L, "AUTH-1", null, "13.1", null, false, null))).getErrorCode());
        when(disputes.existsByApprovalCodeAndStatusIn(eq("AUTH-1"), any())).thenReturn(false);

        assertEquals("DISPUTE_INVALID_AMOUNT", assertThrows(BusinessException.class,
                () -> service.open(new OpenRequest(7L, "AUTH-1", new BigDecimal("120.01"), "13.1", null, false, null))).getErrorCode());
    }

    @Test
    void openedAfterTheSchemeWindow_isFlaggedFromDayOne() {
        captured.setCreatedAt(LocalDateTime.now().minusDays(200));
        assertTrue(opened(false).isDeadlineBreached());
    }

    @Test
    void merchantWins_afterProvisionalCredit_takesTheMoneyBack() {
        Dispute d = opened(true);
        service.sendChargeback(d.getId(), "ACQ-77", "mesa");
        service.represent(d.getId(), "el comercio mandó la guía", "mesa");
        Dispute out = service.resolve(d.getId(), Outcome.MERCHANT, "entrega probada", "mesa");

        assertEquals(Status.RESOLVED_MERCHANT, out.getStatus());
        verify(port).debit(card, new BigDecimal("120"), "REVERSE PROVISIONAL AUTH-1");
        assertEquals("DB-1", out.getCreditReversalRef());
        assertEquals("ACQ-77", out.getAcquirerCaseRef());
        assertEquals(LocalDate.now().plusDays(30), out.getRepresentmentDeadline());
    }

    @Test
    void customerWins_withoutProvisionalCredit_paysNow() {
        Dispute d = opened(false);
        service.resolve(d.getId(), Outcome.CUSTOMER, "sin respuesta del comercio", "mesa");
        verify(port).credit(card, new BigDecimal("120"), "REFUND AUTH-1");
        verify(port, never()).debit(any(), any(), any());
    }

    @Test
    void customerWins_afterProvisionalCredit_leavesTheMoneyWhereItIs() {
        Dispute d = opened(true);
        service.resolve(d.getId(), Outcome.CUSTOMER, null, null);
        verify(port, times(1)).credit(any(), any(), any()); // only the provisional one
        verify(port, never()).debit(any(), any(), any());
    }

    @Test
    void withdraw_afterProvisionalCredit_takesItBack_andResolvedCannotBeWithdrawn() {
        Dispute d = opened(true);
        Dispute out = service.withdraw(d.getId(), "el cliente encontró el paquete", "mesa");
        assertEquals(Status.WITHDRAWN, out.getStatus());
        verify(port).debit(card, new BigDecimal("120"), "REVERSE PROVISIONAL AUTH-1");

        assertEquals("DISPUTE_INVALID_STATE", assertThrows(BusinessException.class,
                () -> service.withdraw(d.getId(), null, null)).getErrorCode());
    }

    @Test
    void evidence_isSealedWithSha256_andRefusedOnceClosed() {
        Dispute d = opened(false);
        byte[] bytes = "guia de envio 123".getBytes(StandardCharsets.UTF_8);
        DisputeEvidence e = service.addEvidence(d.getId(), "guia.txt", "text/plain", bytes, "guía", "mesa");

        assertEquals(DisputeService.sha256(bytes), e.getSha256());
        assertEquals(64, e.getSha256().length());
        assertEquals(bytes.length, e.getSize());

        service.resolve(d.getId(), Outcome.CUSTOMER, null, null);
        assertEquals("DISPUTE_CLOSED", assertThrows(BusinessException.class,
                () -> service.addEvidence(d.getId(), "x", null, bytes, null, null)).getErrorCode());
    }

    @Test
    void chargeback_needsEvidence_whenTheReasonSaysSo() {
        reason.setRequiresEvidence(true);
        Dispute d = opened(false);
        assertEquals("DISPUTE_EVIDENCE_REQUIRED", assertThrows(BusinessException.class,
                () -> service.sendChargeback(d.getId(), null, null)).getErrorCode());
        service.addEvidence(d.getId(), "f.txt", "text/plain", "x".getBytes(), null, null);
        assertEquals(Status.CHARGEBACK_SENT, service.sendChargeback(d.getId(), null, null).getStatus());
    }

    @Test
    void due_and_flagBreached_useTheCurrentStepsDeadline() {
        Dispute soon = opened(false);
        soon.setChargebackDeadline(LocalDate.now().plusDays(2));
        Dispute late = opened(false);
        late.setChargebackDeadline(LocalDate.now().minusDays(1));
        Dispute far = opened(false);
        far.setChargebackDeadline(LocalDate.now().plusDays(60));
        when(disputes.findByStatusInOrderByCreatedAtAsc(any())).thenReturn(List.of(far, soon, late));

        List<Dispute> due = service.due(3);
        assertEquals(List.of(late, soon), due, "soonest first, far one excluded");

        assertEquals(1, service.flagBreached());
        assertTrue(late.isDeadlineBreached());
        assertFalse(soon.isDeadlineBreached());
        assertEquals(0, service.flagBreached(), "already flagged ones are not counted again");
    }

    @Test
    void dossier_bundlesEverything() {
        Dispute d = opened(true);
        service.addEvidence(d.getId(), "foto.jpg", "image/jpeg", new byte[]{1, 2, 3}, "producto roto", "mesa");
        when(events.findByDisputeOrderByCreatedAtAsc(d)).thenReturn(List.of());

        var file = service.dossier(d.getId());
        assertEquals("OPENED", file.get("status"));
        assertEquals("13.1", ((java.util.Map<?, ?>) file.get("reason")).get("code"));
        assertEquals("AUTH-1", ((java.util.Map<?, ?>) file.get("authorization")).get("approvalCode"));
        assertEquals(1, ((List<?>) file.get("evidence")).size());
        assertEquals(true, ((java.util.Map<?, ?>) file.get("money")).get("provisionalCredit"));
    }
}
