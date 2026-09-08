package bank.cardissuing.reports.application;

import bank.cardissuing.common.exception.BusinessException;
import org.springframework.http.HttpStatus;

import java.util.List;

/** The reports the console offers, matching the mockup's "Reportes" and "Regulatorio MX" screens. */
public final class ReportCatalog {

    public record Definition(String code, String name, String description, String tag,
                             String regulator, String frequency, boolean periodic) { }

    public static final String DAILY_ISSUANCE = "DAILY_ISSUANCE";
    public static final String PROCESSOR_RECONCILIATION = "PROCESSOR_RECONCILIATION";
    public static final String R24_BANXICO = "R24_BANXICO";
    public static final String PLD_UIF = "PLD_UIF";
    public static final String DISPUTES_ACTIVITY = "DISPUTES_ACTIVITY";
    public static final String INACTIVE_CARDS = "INACTIVE_CARDS";

    public static final List<Definition> ALL = List.of(
            new Definition(DAILY_ISSUANCE, "Corte diario de emisión",
                    "Altas, activaciones, bloqueos y cancelaciones del día, con las autorizaciones del día",
                    "Operativo", null, "Diario", true),
            new Definition(PROCESSOR_RECONCILIATION, "Conciliación con procesador",
                    "Autorizaciones vs. compensación: retenidas, compensadas, liberadas, vencidas y diferencias",
                    "Operativo", null, "Diario", true),
            new Definition(R24_BANXICO, "Serie R24 · Banxico",
                    "Información de tarjetas de débito por producto: emitidas, vigentes, bloqueadas, canceladas y operaciones",
                    "Regulatorio", "Banxico", "Mensual · día 10", true),
            new Definition(PLD_UIF, "Reporte PLD / UIF",
                    "Operaciones relevantes, acumuladas e inusuales del periodo",
                    "Regulatorio", "UIF", "Mensual · día 17", true),
            new Definition(DISPUTES_ACTIVITY, "Actividad de aclaraciones",
                    "Contracargos por código de razón, estado y antigüedad; plazos vencidos y abonos provisionales",
                    "Riesgo", "CONDUSEF", "Mensual", true),
            new Definition(INACTIVE_CARDS, "Tarjetas inactivas 90 días",
                    "Tarjetas activas sin operación aprobada en el plazo: candidatas a gestión de activación",
                    "Comercial", null, "Bajo demanda", false));

    public static Definition get(String code) {
        String c = code == null ? "" : code.trim().toUpperCase();
        return ALL.stream().filter(d -> d.code().equals(c)).findFirst()
                .orElseThrow(() -> new BusinessException("REPORT_NOT_FOUND", "Unknown report '" + code + "'", HttpStatus.NOT_FOUND));
    }

    private ReportCatalog() { }
}
