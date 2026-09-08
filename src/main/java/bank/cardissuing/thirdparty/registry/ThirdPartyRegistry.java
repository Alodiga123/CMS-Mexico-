package bank.cardissuing.thirdparty.registry;

import bank.cardissuing.audit.application.AuditService;
import bank.cardissuing.clearing.domain.ClearingBatch;
import bank.cardissuing.clearing.domain.Network;
import bank.cardissuing.clearing.infrastructure.ClearingBatchRepository;
import bank.cardissuing.common.exception.BusinessException;
import bank.cardissuing.common.security.CmsPrincipal;
import bank.cardissuing.common.security.IamClient;
import bank.cardissuing.fraud.guild.application.GuildClient;
import bank.cardissuing.funds.core.CoreBankingClient;
import bank.cardissuing.funds.core.CoreOutageSwitch;
import bank.cardissuing.hsm.host.PayShieldHostClient;
import bank.cardissuing.thirdparty.messaging.MessagingProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every third party the card business stands on, in one place: what it does for us,
 * how it is connected (online, by file, by hand, not yet), which variables switch it
 * from simulated to real, whether it answers right now, and where its contract stands.
 * The catalog is code; the contracts are data the operations team keeps.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ThirdPartyRegistry {

    public enum Integration { ONLINE, FILE, MANUAL, NONE }

    public record Definition(String key, String name, String role, Integration integration, String defaultVendor,
                             List<String> envVars, List<String> checklist, String docs) { }

    public record Health(boolean up, String detail, LocalDateTime checkedAt) { }

    private static final String DEV_PERSO_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    public static final List<Definition> CATALOG = List.of(
            new Definition("CORE_BANKING", "Core bancario (Mifos X / Fineract)", "Saldos, retenciones y movimientos de las tarjetas de débito con saldo en el core y de la línea de crédito", Integration.ONLINE,
                    "Alodiga Core (Mifos X)", List.of("CORE_MODE", "FINERACT_URL", "FINERACT_TENANT", "FINERACT_USER", "FINERACT_PASSWORD"),
                    List.of("Usuario de servicio con permisos mínimos (savings read/write, clients read)", "Tenant y oficina de producción", "Producto de ahorro y tipo de pago para tarjetas", "Prueba de retención / captura / liberación", "Prueba de indisponibilidad: stand-in y liquidación posterior", "Ventana de mantenimiento y contacto de guardia"),
                    "seguridad-iam.md"),
            new Definition("HSM", "HSM de pagos (payShield 10K)", "PIN (PVV), CVV/CVV2, ARQC/ARPC y las llaves de zona con la red", Integration.ONLINE,
                    "Thales payShield 10K (simulador PayShieldSim en desarrollo)", List.of("HSM_HOST", "HSM_PORT", "HSM_KEY_PVK", "HSM_KEY_CVK_A", "HSM_KEY_CVK_B", "HSM_KEY_IMK", "HSM_KEY_ZPK"),
                    List.of("Equipo (o servicio hospedado) con LMK propio y ceremonia de llaves", "Generación de PVK, CVK, IMK bajo LMK", "Intercambio de ZPK/ZMK con la red y los adquirentes", "Comandos habilitados: BA, DG, EA, CW, CY, KQ, NC", "Alta disponibilidad (dos equipos) y respaldo del LMK", "Certificación PCI PIN"),
                    "iso8583-criptografia-standin.md"),
            new Definition("IAM", "Gestión de identidad (IAM corporativo)", "Quién entra a la consola y con qué permisos; introspección del token", Integration.ONLINE,
                    "AIM (IAM corporativo)", List.of("IAM_BASE_URL", "IAM_PROJECT_CODE", "SECURITY_ENABLED"),
                    List.of("Proyecto EMISION_CMS y sus permisos registrados", "Roles CMS_MESA_CONTROL, CMS_ANALISTA_FRAUDE, CMS_AUDITOR, CMS_ADMIN", "Rotación de la clave de API de sistemas", "Bitácora de accesos conservada 12 meses"),
                    "seguridad-iam.md"),
            new Definition("GUILD", "Antifraude del gremio (SVL / SNA / SPC)", "Listas de tarjetas y comercios comprometidos, alertas en ambos sentidos, aviso temprano de aclaraciones", Integration.ONLINE,
                    "Gremio bancario (ACT-094)", List.of("GUILD_MODE", "GUILD_PARTICIPANT_ID", "GUILD_BASE_URL", "GUILD_API_KEY", "GUILD_HMAC_SECRET", "GUILD_SSL_BUNDLE"),
                    List.of("Convenio de participación y clave de participante", "Certificado mTLS y secreto HMAC", "Pruebas SVL (consulta), SNA (alerta enviada y recibida), SPC (aviso de aclaración)", "Plazos: cierre de alertas 10 días hábiles, respuesta a enumeración 5"),
                    "guild-antifraude.md"),
            new Definition("MESSAGING", "Mensajería al tarjetahabiente (SMS / WhatsApp)", "Códigos de un solo uso del step-up, avisos de envío y entrega del plástico, resultado de aclaraciones", Integration.ONLINE,
                    "Proveedor de SMS/WhatsApp (Twilio, Infobip, agregador local)", List.of("THIRDPARTY_MESSAGING_MODE", "THIRDPARTY_MESSAGING_URL", "THIRDPARTY_MESSAGING_API_KEY", "THIRDPARTY_MESSAGING_SENDER"),
                    List.of("Remitente registrado (short code o WhatsApp Business verificado)", "Plantillas aprobadas: OTP, tarjeta enviada, tarjeta entregada, aclaración resuelta", "Entrega en menos de 10 s para OTP (SLA)", "Reporte de entrega (DLR) y reintentos", "Datos personales: contrato de encargado de tratamiento"),
                    "contratos-terceros.md"),
            new Definition("PERSO_BUREAU", "Bureau de personalización (plásticos)", "Recibe el archivo de emboce cifrado, produce y entrega los plásticos y el PIN mailer", Integration.FILE,
                    "IDEMIA / Thales / bureau local", List.of("PLASTICS_MANUFACTURER", "PLASTICS_MANUFACTURER_KEY", "PLASTICS_CHIP_PROFILE"),
                    List.of("Llave AES del archivo de emboce intercambiada en ceremonia", "Perfil de chip aprobado por la red (EMV) y datos de personalización", "Canal SFTP con llaves y ventana de corte", "Prueba de lote: archivo, acuse, producción, embarque", "Certificación PCI Card Production"),
                    "contratos-terceros.md"),
            new Definition("COURIER", "Mensajería de plásticos", "Lleva el plástico al domicilio y devuelve la guía y la entrega", Integration.MANUAL,
                    "Estafeta / DHL / mensajería local", List.of(),
                    List.of("Guía capturada al despachar y confirmación de entrega", "Devoluciones (RETURNED) y destrucción de plásticos no entregados", "Seguro y cadena de custodia", "Integración de rastreo (opcional) por API"),
                    "contratos-terceros.md"),
            new Definition("NETWORK_VISA", "Visa", "Autorización en línea (ISO 8583) y compensación por archivo (BASE II → CMS-CLR)", Integration.FILE,
                    "Visa", List.of("ISO_PORT", "CLEARING_TOLERANCE_PERCENT"),
                    List.of("Licencia de emisor y BIN asignado", "Conexión VisaNet (VAP/VCMS) o a través de un procesador", "Certificación de autorización (VCMS test scripts)", "Certificación de compensación BASE II y liquidación", "Llaves de zona (ZPK/ZMK) y CVV/PVV con el HSM", "Programa de disputas VROL"),
                    "compensacion-liquidacion.md"),
            new Definition("NETWORK_MASTERCARD", "Mastercard", "Autorización en línea (ISO 8583) y compensación por archivo (IPM → CMS-CLR)", Integration.FILE,
                    "Mastercard", List.of("ISO_PORT", "CLEARING_TOLERANCE_PERCENT"),
                    List.of("Licencia de emisor y BIN asignado", "Conexión MIP o a través de un procesador", "Certificación de autorización (Simulator/CIS)", "Certificación IPM de compensación y liquidación", "Llaves de zona y CVC con el HSM", "Mastercom para disputas"),
                    "compensacion-liquidacion.md"),
            new Definition("NETWORK_DOMESTIC", "Red doméstica (switch nacional)", "Autorización y compensación en el switch local (ISO 8583 + archivo CMS-CLR)", Integration.FILE,
                    "Switch doméstico (PROSA / E-Global)", List.of("ISO_PORT", "CLEARING_TOLERANCE_PERCENT"),
                    List.of("Convenio de participación y BIN", "Enlace dedicado y ventana de pruebas", "Certificación de mensajes 0100/0200/0400/0420/0800", "Formato de compensación y horario de corte", "Esquema de liquidación (SPEI)"),
                    "compensacion-liquidacion.md"),
            new Definition("TREASURY_SPEI", "Banco liquidador (SPEI)", "Paga o cobra la posición neta de cada ciclo de liquidación", Integration.MANUAL,
                    "Banco de tesorería", List.of(),
                    List.of("Cuenta de liquidación por red", "Referencia SPEI capturada al pagar cada ciclo", "Conciliación bancaria del neto contra el archivo sellado"),
                    "compensacion-liquidacion.md"),
            new Definition("CREDIT_BUREAU", "Buró de crédito", "Consulta de historial para la línea de crédito y reporte mensual de la cartera", Integration.NONE,
                    "Buró de Crédito / Círculo de Crédito", List.of(),
                    List.of("Contrato de consulta y reporte", "Consentimiento del cliente (NIP) en el alta", "Formato de reporte mensual de cartera", "Integración pendiente en el CMS"),
                    "contratos-terceros.md")
    );

    private final ThirdPartyContractRepository contracts;
    private final ClearingBatchRepository batches;
    private final CoreOutageSwitch outage;
    private final CoreBankingClient core;
    private final PayShieldHostClient hsm;
    private final IamClient iam;
    private final GuildClient guild;
    private final MessagingProvider messaging;
    private final Environment env;
    private final AuditService audit;

    // ------------------------------------------------------------ seeding

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seed() {
        int created = 0;
        for (Definition d : CATALOG) {
            if (contracts.findByProviderKey(d.key()).isPresent()) continue;
            ThirdPartyContract c = new ThirdPartyContract();
            c.setProviderKey(d.key());
            c.setVendor(d.defaultVendor());
            c.setStatus(ThirdPartyContract.Status.PENDING);
            c.setChecklist(String.join("\n", d.checklist().stream().map(i -> "[ ] " + i).toList()));
            c.setUpdatedBy("system");
            contracts.save(c);
            created++;
        }
        if (created > 0) log.info("Third-party registry: {} contracts seeded as PENDING", created);
    }

    // ------------------------------------------------------------ queries

    public Definition definition(String key) {
        return CATALOG.stream().filter(d -> d.key().equalsIgnoreCase(key)).findFirst()
                .orElseThrow(() -> new BusinessException("THIRD_PARTY_NOT_FOUND", "Unknown third party " + key, HttpStatus.NOT_FOUND));
    }

    public Optional<ThirdPartyContract> contract(String key) { return contracts.findByProviderKey(key.toUpperCase()); }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Definition d : CATALOG) out.add(view(d, health(d, false)));
        return out;
    }

    public Map<String, Object> one(String key, boolean probe) {
        Definition d = definition(key);
        return view(d, health(d, probe));
    }

    public String mode(Definition d) {
        return switch (d.key()) {
            case "CORE_BANKING" -> env.getProperty("core.mode", "simulated");
            case "GUILD" -> guild.mode();
            case "MESSAGING" -> messaging.mode();
            case "HSM" -> env.getProperty("HSM_HOST", "localhost") + ":" + env.getProperty("HSM_PORT", "1500");
            case "IAM" -> Boolean.parseBoolean(env.getProperty("security.enabled", "true")) ? "introspection" : "disabled";
            case "PERSO_BUREAU" -> DEV_PERSO_KEY.equals(env.getProperty("plastics.manufacturer-key-base64", DEV_PERSO_KEY)) ? "dev-key" : "own-key";
            default -> d.integration().name().toLowerCase();
        };
    }

    /** What the connection says right now; {@code probe} makes the expensive calls too (core, IAM). */
    public Health health(Definition d, boolean probe) {
        LocalDateTime now = LocalDateTime.now();
        try {
            switch (d.key()) {
                case "CORE_BANKING": {
                    if (outage.isDown()) return new Health(false, outage.isForced() ? "marcado fuera de servicio a mano" : "sin respuesta hace poco; el autorizador está en stand-in", now);
                    if (!probe) return new Health(true, "sin señales de caída (modo " + mode(d) + ")", now);
                    core.findClientByExternalId("HEALTHCHECK-CMS");
                    return new Health(true, "responde (modo " + mode(d) + ")", now);
                }
                case "HSM": return hsm.isUp() ? new Health(true, "responde al diagnóstico (NC)", now) : new Health(false, "no responde en " + mode(d), now);
                case "IAM": {
                    if (!probe) return new Health(true, "introspección de tokens activa", now);
                    iam.introspect("healthcheck");
                    return new Health(true, "responde a la introspección", now);
                }
                case "GUILD": { GuildClient.Health h = guild.health(); return new Health(h.up(), h.detail(), now); }
                case "MESSAGING": { MessagingProvider.Health h = messaging.health(); return new Health(h.up(), h.detail(), now); }
                case "PERSO_BUREAU": return "dev-key".equals(mode(d)) ? new Health(true, "clave de desarrollo: sustituir por la del bureau antes de producción", now) : new Health(true, "clave propia configurada", now);
                case "COURIER": return new Health(true, "manual: la guía se captura al despachar", now);
                case "TREASURY_SPEI": return new Health(true, "manual: la referencia SPEI se captura al pagar el ciclo", now);
                case "CREDIT_BUREAU": return new Health(false, "sin integrar", now);
                default: {
                    Network n = Network.valueOf(d.key().substring("NETWORK_".length()));
                    Optional<ClearingBatch> last = batches.findTop100ByOrderByCreatedAtDesc().stream().filter(b -> b.getNetwork() == n).findFirst();
                    return last.map(b -> new Health(true, "último archivo de compensación: " + b.getCycleDate() + " (" + b.getRecordCount() + " registros)", now))
                            .orElse(new Health(true, "canal ISO 8583 arriba; sin archivos de compensación cargados", now));
                }
            }
        } catch (RuntimeException e) {
            return new Health(false, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), now);
        }
    }

    // ------------------------------------------------------------ contracts

    public record ContractUpdate(String vendor, String contractRef, String status, String signedAt, String expiresAt,
                                 String contact, String slaNotes, String checklist, String by) { }

    @Transactional
    public ThirdPartyContract update(String key, ContractUpdate u) {
        Definition d = definition(key);
        ThirdPartyContract c = contracts.findByProviderKey(d.key()).orElseGet(() -> { ThirdPartyContract n = new ThirdPartyContract(); n.setProviderKey(d.key()); return n; });
        if (u.vendor() != null) c.setVendor(u.vendor().trim());
        if (u.contractRef() != null) c.setContractRef(u.contractRef().trim());
        if (u.status() != null && !u.status().isBlank()) {
            try { c.setStatus(ThirdPartyContract.Status.valueOf(u.status().trim().toUpperCase())); }
            catch (IllegalArgumentException e) { throw new BusinessException("THIRD_PARTY_BAD_STATUS", "status must be one of PENDING, NEGOTIATION, SIGNED, CERTIFYING, ACTIVE, SUSPENDED, TERMINATED", HttpStatus.BAD_REQUEST); }
        }
        if (u.signedAt() != null) c.setSignedAt(u.signedAt().isBlank() ? null : LocalDate.parse(u.signedAt()));
        if (u.expiresAt() != null) c.setExpiresAt(u.expiresAt().isBlank() ? null : LocalDate.parse(u.expiresAt()));
        if (u.contact() != null) c.setContact(u.contact().trim());
        if (u.slaNotes() != null) c.setSlaNotes(u.slaNotes());
        if (u.checklist() != null) c.setChecklist(u.checklist());
        c.setUpdatedBy(CmsPrincipal.auditName(u.by()));
        c = contracts.save(c);
        audit.log("THIRD_PARTY_CONTRACT_UPDATED", "ThirdPartyContract", d.key(), c.getUpdatedBy());
        return c;
    }

    // ------------------------------------------------------------ views

    Map<String, Object> view(Definition d, Health h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", d.key()); m.put("name", d.name()); m.put("role", d.role()); m.put("integration", d.integration().name());
        m.put("mode", mode(d)); m.put("docs", d.docs());
        List<Map<String, Object>> vars = new ArrayList<>();
        for (String v : d.envVars()) { Map<String, Object> e = new LinkedHashMap<>(); e.put("name", v); e.put("set", env.getProperty(v) != null && !env.getProperty(v).isBlank()); vars.add(e); }
        m.put("envVars", vars);
        Map<String, Object> hm = new LinkedHashMap<>(); hm.put("up", h.up()); hm.put("detail", h.detail()); hm.put("checkedAt", h.checkedAt());
        m.put("health", hm);
        m.put("contract", contracts.findByProviderKey(d.key()).map(ThirdPartyRegistry::view).orElse(null));
        return m;
    }

    public static Map<String, Object> view(ThirdPartyContract c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId()); m.put("providerKey", c.getProviderKey()); m.put("vendor", c.getVendor()); m.put("contractRef", c.getContractRef());
        m.put("status", c.getStatus().name()); m.put("signedAt", c.getSignedAt()); m.put("expiresAt", c.getExpiresAt());
        m.put("expired", c.getExpiresAt() != null && c.getExpiresAt().isBefore(LocalDate.now()));
        m.put("contact", c.getContact()); m.put("slaNotes", c.getSlaNotes()); m.put("checklist", c.getChecklist());
        m.put("checklistDone", c.checklistDone()); m.put("checklistTotal", c.checklistTotal());
        m.put("updatedBy", c.getUpdatedBy()); m.put("updatedAt", c.getUpdatedAt());
        return m;
    }
}
