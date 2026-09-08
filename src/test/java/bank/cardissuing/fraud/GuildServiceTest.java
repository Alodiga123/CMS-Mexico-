package bank.cardissuing.fraud;

import bank.cardissuing.audit.application.AuditService;
import bank.cardissuing.card.domain.Card;
import bank.cardissuing.card.domain.CardProduct;
import bank.cardissuing.card.domain.CardStatus;
import bank.cardissuing.card.infrastructure.CardRepository;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.fraud.domain.BlockedEntity;
import bank.cardissuing.fraud.guild.application.GuildClient;
import bank.cardissuing.fraud.guild.application.GuildService;
import bank.cardissuing.fraud.guild.application.GuildSettings;
import bank.cardissuing.fraud.guild.domain.GuildAlert;
import bank.cardissuing.fraud.guild.domain.GuildAlert.Status;
import bank.cardissuing.fraud.guild.domain.GuildAlert.Type;
import bank.cardissuing.fraud.guild.domain.GuildVerification;
import bank.cardissuing.fraud.guild.infrastructure.GuildAlertRepository;
import bank.cardissuing.fraud.guild.infrastructure.GuildVerificationRepository;
import bank.cardissuing.fraud.guild.infrastructure.HttpGuildClient;
import bank.cardissuing.fraud.guild.infrastructure.SimulatedGuildClient;
import bank.cardissuing.fraud.infrastructure.BlockedEntityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GuildServiceTest {

    @Mock GuildAlertRepository alerts;
    @Mock GuildVerificationRepository verifications;
    @Mock BlockedEntityRepository blocklist;
    @Mock CardRepository cards;
    @Mock AuditService audit;
    SimulatedGuildClient sim = new SimulatedGuildClient();
    GuildSettings settings = new GuildSettings();
    GuildService service;

    List<GuildAlert> storedAlerts = new ArrayList<>();
    List<GuildVerification> storedVerifs = new ArrayList<>();
    List<BlockedEntity> storedBlocks = new ArrayList<>();
    Card card;

    @BeforeEach
    void setUp() {
        service = new GuildService(sim, settings, alerts, verifications, blocklist, cards, audit);
        when(alerts.save(any())).thenAnswer(inv -> { GuildAlert a = inv.getArgument(0); if (a.getId() == null) { a.setId((long) storedAlerts.size() + 1); storedAlerts.add(a); } return a; });
        when(alerts.findById(any())).thenAnswer(inv -> storedAlerts.stream().filter(a -> a.getId().equals(inv.getArgument(0))).findFirst());
        when(alerts.findByGuildFolio(anyString())).thenAnswer(inv -> storedAlerts.stream().filter(a -> inv.getArgument(0).equals(a.getGuildFolio())).findFirst());
        when(alerts.findByStatusInOrderByCreatedAtAsc(any())).thenAnswer(inv -> { Collection<Status> st = inv.getArgument(0); return storedAlerts.stream().filter(a -> st.contains(a.getStatus())).toList(); });
        when(alerts.findByStatusInAndRespondByBefore(any(), any())).thenAnswer(inv -> { Collection<Status> st = inv.getArgument(0); LocalDate b = inv.getArgument(1);
            return storedAlerts.stream().filter(a -> st.contains(a.getStatus()) && a.getRespondBy() != null && a.getRespondBy().isBefore(b)).toList(); });
        when(alerts.findTopByDirectionOrderByReceivedAtDesc(any())).thenReturn(Optional.empty());
        when(verifications.save(any())).thenAnswer(inv -> { GuildVerification v = inv.getArgument(0); if (!storedVerifs.contains(v)) storedVerifs.add(v); return v; });
        when(verifications.findBySubjectTypeAndSubject(any(), anyString())).thenAnswer(inv -> storedVerifs.stream().filter(v -> v.getSubjectType() == inv.getArgument(0) && v.getSubject().equals(inv.getArgument(1))).findFirst());
        when(blocklist.save(any())).thenAnswer(inv -> { BlockedEntity b = inv.getArgument(0); if (!storedBlocks.contains(b)) storedBlocks.add(b); return b; });
        when(blocklist.findByTypeAndValue(any(), anyString())).thenAnswer(inv -> storedBlocks.stream().filter(b -> b.getType() == inv.getArgument(0) && b.getValue().equals(inv.getArgument(1))).findFirst());
        CardProduct p = new CardProduct(); p.setBin("453212");
        card = new Card(); card.setId(7L); card.setLast4("9001"); card.setProduct(p); card.setStatus(CardStatus.ACTIVE);
        when(cards.findById(7L)).thenReturn(Optional.of(card));
        when(cards.findByLast4("9001")).thenReturn(List.of(card));
        when(cards.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void enumeration_goesToTheOutbox_once_andIsSentWithAFolio() {
        GuildAlert a = service.reportEnumeration("M-1", "Tienda", 40, "card testing");
        assertEquals(Status.PENDING_SEND, a.getStatus());
        assertEquals(LocalDate.now().plusDays(settings.getEnumerationResponseDays()), a.getRespondBy());
        when(alerts.existsByDirectionAndTypeAndMerchantIdAndStatusInAndCreatedAtAfter(any(), any(), anyString(), any(), any())).thenReturn(true);
        assertNull(service.reportEnumeration("M-1", "Tienda", 40, "again"));
        assertEquals(1, service.flushOutbox());
        assertEquals(Status.SENT, a.getStatus());
        assertTrue(a.getGuildFolio().startsWith("SNA-"));
        assertEquals(1, sim.sent().size());
        assertEquals("ENUMERATION", sim.sent().get(0).type());
        verify(audit).log("GUILD_ALERT_SENT", "GuildAlert", "1", "SYSTEM");
    }

    @Test
    void outage_marksFailed_keepsRetrying_untilTheCap() {
        settings.setOutboxMaxAttempts(2);
        GuildAlert a = service.raise(Type.CONFIRMED_FRAUD, 7L, null, null, "fraude confirmado", new BigDecimal("120"), "analista");
        assertEquals("453212", a.getBin()); assertEquals("9001", a.getLast4());
        sim.setDown(true);
        assertEquals(0, service.flushOutbox());
        assertEquals(Status.FAILED, a.getStatus()); assertEquals(1, a.getAttempts()); assertNotNull(a.getLastError());
        assertEquals(0, service.flushOutbox());
        assertEquals(2, a.getAttempts());
        assertEquals(0, service.flushOutbox());   // cap reached: not retried
        assertEquals(2, a.getAttempts());
        sim.setDown(false);
        assertEquals(Status.SENT, service.sendNow(a.getId()).getStatus());  // an operator can still push it
    }

    @Test
    void raise_validatesWhatEachTypeNeeds() {
        assertEquals("GUILD_CARD_REQUIRED", assertThrows(BusinessException.class, () -> service.raise(Type.CONFIRMED_FRAUD, null, null, null, "x", null, null)).getErrorCode());
        assertEquals("GUILD_MERCHANT_REQUIRED", assertThrows(BusinessException.class, () -> service.raise(Type.SUSPICIOUS_MERCHANT, null, null, null, "x", null, null)).getErrorCode());
        assertEquals("GUILD_BAD_TYPE", assertThrows(BusinessException.class, () -> service.raise(Type.CHARGEBACK_PREVENTION, 7L, null, null, "x", null, null)).getErrorCode());
    }

    @Test
    void svl_hitBlocklistsTheCard_andIsCachedForTheWindow() {
        assertFalse(service.cardListed(card));
        sim.listCard("453212", "9001", "compromised at other issuer");
        assertFalse(service.cardListed(card), "cached answer still holds");
        storedVerifs.get(0).setValidUntil(LocalDateTime.now().minusMinutes(1)); // window over
        assertTrue(service.cardListed(card));
        assertEquals(1, storedBlocks.size());
        assertEquals(BlockedEntity.Type.CARD, storedBlocks.get(0).getType());
        assertEquals(BlockedEntity.Source.EXTERNAL, storedBlocks.get(0).getSource());
        assertTrue(storedBlocks.get(0).getReason().startsWith("GUILD SVL SVL-"));
        assertEquals(1, storedAlerts.size());
        assertEquals(Type.COMPROMISED_CARD, storedAlerts.get(0).getType());
        assertEquals("CARD_BLOCKLISTED", storedAlerts.get(0).getAutoAction());
    }

    @Test
    void svl_outage_failsOpenByDefault_andClosedWhenAsked() {
        sim.setDown(true);
        GuildVerification v = service.verifyCard(card);
        assertFalse(v.isListed()); assertTrue(v.isDegraded());
        assertTrue(v.getValidUntil().isBefore(LocalDateTime.now().plusMinutes(6)), "degraded answers are retried soon");
        settings.setFailOpen(false);
        storedVerifs.clear();
        assertTrue(service.verifyCard(card).isListed());
        assertTrue(storedBlocks.isEmpty(), "a degraded answer never writes the blocklist");
    }

    @Test
    void inbound_compromisedCard_blocksTheCard_andSetsTheDeadline() {
        sim.pushInbound("COMPROMISED_CARD", "453212", "9001", null, null, "seen in a dump");
        sim.pushInbound("SUSPICIOUS_MERCHANT", null, null, "M-BAD", "Tienda X", "many disputes");
        sim.pushInbound("OTHER", null, null, null, null, "notice");
        assertEquals(3, service.pollInbound());
        assertEquals(0, service.pollInbound(), "same folios are not stored twice");
        GuildAlert c = storedAlerts.get(0);
        assertEquals(Status.IN_REVIEW, c.getStatus());
        assertEquals("CARD_BLOCKED", c.getAutoAction());
        assertEquals(7L, c.getCardId());
        assertEquals(CardStatus.BLOCKED, card.getStatus());
        assertEquals(GuildService.class.getDeclaredMethods().length > 0 ? addBusinessDays(LocalDate.now(), 10) : null, c.getRespondBy());
        assertEquals("MERCHANT_BLOCKLISTED", storedAlerts.get(1).getAutoAction());
        assertTrue(storedBlocks.stream().anyMatch(b -> b.getType() == BlockedEntity.Type.MERCHANT_ID && b.getValue().equals("M-BAD")));
        assertEquals("NONE", storedAlerts.get(2).getAutoAction());
    }

    @Test
    void close_andExpire_followTheGuildRules() {
        sim.pushInbound("OTHER", null, null, null, null, "notice");
        service.pollInbound();
        GuildAlert a = storedAlerts.get(0);
        a.setRespondBy(LocalDate.now().minusDays(1));
        assertEquals(1, service.expireOverdue());
        assertEquals(Status.EXPIRED, a.getStatus());
        assertTrue(a.isAssumedLoss());
        assertEquals("GUILD_ALERT_CLOSED", assertThrows(BusinessException.class, () -> service.close(a.getId(), "late", "x")).getErrorCode());
        GuildAlert o = service.raise(Type.SUSPICIOUS_MERCHANT, null, "M-2", "T", "x", null, "ana");
        assertEquals("GUILD_ALERT_NOT_SENT", assertThrows(BusinessException.class, () -> service.close(o.getId(), "done", "ana")).getErrorCode());
        service.flushOutbox();
        assertEquals(Status.CLOSED, service.close(o.getId(), "merchant confirmed", "ana").getStatus());
    }

    @Test
    void businessDays_skipWeekends() {
        LocalDate friday = LocalDate.of(2026, 9, 4);
        assertEquals(DayOfWeek.FRIDAY, friday.getDayOfWeek());
        assertEquals(LocalDate.of(2026, 9, 18), addBusinessDays(friday, 10));
        assertEquals(LocalDate.of(2026, 9, 7), addBusinessDays(friday, 1));
    }

    @Test
    void httpSignature_isDeterministic_andCoversEveryPart() throws Exception {
        Method m = HttpGuildClient.class.getDeclaredMethod("sign", String.class, String.class, String.class, String.class, String.class);
        m.setAccessible(true);
        String a = (String) m.invoke(null, "secret", "1700000000", "POST", "/v1/alerts", "{\"a\":1}");
        assertEquals(64, a.length());
        assertEquals(a, m.invoke(null, "secret", "1700000000", "POST", "/v1/alerts", "{\"a\":1}"));
        assertNotEquals(a, m.invoke(null, "secret", "1700000001", "POST", "/v1/alerts", "{\"a\":1}"));
        assertNotEquals(a, m.invoke(null, "secret", "1700000000", "POST", "/v1/alerts", "{\"a\":2}"));
        assertNotEquals(a, m.invoke(null, "other", "1700000000", "POST", "/v1/alerts", "{\"a\":1}"));
    }

    private static LocalDate addBusinessDays(LocalDate from, int days) {
        try {
            Method m = GuildService.class.getDeclaredMethod("addBusinessDays", LocalDate.class, int.class);
            m.setAccessible(true);
            return (LocalDate) m.invoke(null, from, days);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
