package bank.cardissuing.reports;

import bank.cardissuing.reports.application.ReportCsv;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ReportCsvTest {

    @Test
    void writesHeaderRowsAndCrlf() {
        String csv = ReportCsv.write(List.of("a", "b"), List.of(Arrays.asList(1L, "x"), Arrays.asList(2L, null)));
        assertEquals("a,b\r\n1,x\r\n2,\r\n", csv);
    }

    @Test
    void quotesCommasQuotesAndLineBreaks() {
        String csv = ReportCsv.write(List.of("v"), List.of(List.of("Perez, Ana"), List.of("say \"hi\""), List.of("l1\nl2")));
        assertEquals("v\r\n\"Perez, Ana\"\r\n\"say \"\"hi\"\"\"\r\n\"l1\nl2\"\r\n", csv);
    }

    @Test
    void formatsNumbersAndDatesPlainly() {
        List<Object> row = Arrays.asList(new BigDecimal("1234.50"), new BigDecimal("100.00"), new BigDecimal("1E+3"),
                LocalDate.of(2026, 9, 7), LocalDateTime.of(2026, 9, 7, 13, 5, 9), java.sql.Timestamp.valueOf("2026-09-07 01:02:03"));
        assertEquals("c\r\n1234.50,100,1000,2026-09-07,2026-09-07 13:05:09,2026-09-07 01:02:03\r\n",
                ReportCsv.write(List.of("c"), List.of(row)));
    }

    @Test
    void sealIsStableAndDetectsAnyChange() {
        String a = ReportCsv.sha256("a,b\r\n1,2\r\n");
        assertEquals(64, a.length());
        assertEquals(a, ReportCsv.sha256("a,b\r\n1,2\r\n"));
        assertNotEquals(a, ReportCsv.sha256("a,b\r\n1,3\r\n"));
    }
}
