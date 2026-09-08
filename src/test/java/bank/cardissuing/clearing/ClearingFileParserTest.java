package bank.cardissuing.clearing;

import bank.cardissuing.clearing.application.ClearingFileParser;
import bank.cardissuing.clearing.application.ClearingFileParser.Header;
import bank.cardissuing.clearing.application.ClearingFileParser.Line;
import bank.cardissuing.clearing.application.ClearingFileParser.Parsed;
import bank.cardissuing.clearing.domain.ClearingBatch;
import bank.cardissuing.clearing.domain.ClearingRecord;
import bank.cardissuing.clearing.domain.Network;
import bank.cardissuing.clearing.domain.SettlementCycle;
import bank.cardissuing.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ClearingFileParserTest {

    private static Line rec(int no, ClearingRecord.Type t, String amount, String fee) {
        return new Line(no, t, "4532129876543210", "000000000123", "000123", "ABC123", new BigDecimal(amount), "MXN", new BigDecimal(fee), "5411", "MERCH1", "Tienda | Centro", LocalDate.of(2026, 9, 8), t == ClearingRecord.Type.CHARGEBACK ? "13.1" : null);
    }

    @Test
    void writeThenParse_roundTrips_andChecksTheTrailer() {
        Header h = new Header(Network.VISA, LocalDate.of(2026, 9, 8), "VISA-20260908-1");
        List<Line> lines = List.of(rec(1, ClearingRecord.Type.PRESENTMENT, "30.00", "0.35"), rec(2, ClearingRecord.Type.REVERSAL, "30.00", "0"), rec(3, ClearingRecord.Type.FEE, "38.70", "0"), rec(4, ClearingRecord.Type.CHARGEBACK, "80.00", "0"));
        String file = ClearingFileParser.write(h, lines);
        assertTrue(file.startsWith("HDR|CMS-CLR|1.0|VISA|2026-09-08|VISA-20260908-1\n"));
        assertTrue(file.endsWith("TRL|4|140.00\n"), "fees are outside the trailer total: " + file);
        assertTrue(file.contains("|Tienda   Centro|"), "the delimiter never appears inside a value");
        Parsed p = ClearingFileParser.parse(file);
        assertEquals(Network.VISA, p.header().network());
        assertEquals(4, p.lines().size());
        assertEquals(new BigDecimal("30.00"), p.lines().get(0).amount());
        assertEquals(new BigDecimal("0.35"), p.lines().get(0).interchangeFee());
        assertEquals("13.1", p.lines().get(3).reasonCode());
        assertEquals(ClearingRecord.Type.FEE, p.lines().get(2).type());
    }

    @Test
    void badFiles_areRefusedWithAReason() {
        assertEquals("CLEARING_FILE_INVALID", assertThrows(BusinessException.class, () -> ClearingFileParser.parse("")).getErrorCode());
        String noTrailer = "HDR|CMS-CLR|1.0|VISA|2026-09-08|X\nREC|PRESENTMENT|4532|1|1|A|10.00|MXN|0|5411|M|N|2026-09-08|\n";
        assertTrue(assertThrows(BusinessException.class, () -> ClearingFileParser.parse(noTrailer)).getMessage().contains("no trailer"));
        String wrongCount = noTrailer + "TRL|2|10.00\n";
        assertTrue(assertThrows(BusinessException.class, () -> ClearingFileParser.parse(wrongCount)).getMessage().contains("trailer says 2"));
        String wrongTotal = noTrailer + "TRL|1|11.00\n";
        assertTrue(assertThrows(BusinessException.class, () -> ClearingFileParser.parse(wrongTotal)).getMessage().contains("differs"));
        String badType = "HDR|CMS-CLR|1.0|VISA|2026-09-08|X\nREC|GIFT|4532|1|1|A|10.00|MXN|0|5411|M|N|2026-09-08|\nTRL|1|10.00\n";
        assertTrue(assertThrows(BusinessException.class, () -> ClearingFileParser.parse(badType)).getMessage().contains("unknown record type"));
        String badNet = "HDR|CMS-CLR|1.0|AMEX|2026-09-08|X\nTRL|0|0\n";
        assertTrue(assertThrows(BusinessException.class, () -> ClearingFileParser.parse(badNet)).getMessage().contains("unknown network"));
    }

    @Test
    void settlementCycle_addsBatches_andNetsThePosition() {
        SettlementCycle c = new SettlementCycle();
        c.setNetwork(Network.MASTERCARD); c.setCycleDate(LocalDate.of(2026, 9, 8));
        ClearingBatch a = new ClearingBatch();
        a.setPresentmentsCount(3); a.setPresentmentsAmount(new BigDecimal("135.00")); a.setReversalsAmount(new BigDecimal("60.00"));
        a.setChargebacksAmount(new BigDecimal("80.00")); a.setInterchangeAmount(new BigDecimal("1.55")); a.setFeesAmount(new BigDecimal("38.70")); a.setExceptionCount(2);
        ClearingBatch b = new ClearingBatch();
        b.setPresentmentsCount(1); b.setPresentmentsAmount(new BigDecimal("20.00"));
        c.add(a); c.add(b);
        assertEquals(2, c.getBatchCount());
        assertEquals(4, c.getPresentmentsCount());
        assertEquals(new BigDecimal("155.00"), c.getPresentmentsAmount());
        assertEquals(new BigDecimal("52.15"), c.getNetPosition(), "155 - 60 - 80 - 1.55 + 38.70: the issuer pays");
        assertEquals(2, c.getExceptionCount());
    }
}
