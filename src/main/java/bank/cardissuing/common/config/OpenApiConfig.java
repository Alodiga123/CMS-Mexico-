package bank.cardissuing.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.tags.Tag;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/** Swagger UI at /swagger-ui.html: every /api endpoint, grouped by module in the order the operation runs. */
@Configuration
public class OpenApiConfig {

    /** Tag per path prefix, so the UI reads like the product and not like the package tree. */
    private static final Map<String, String> TAGS = Map.ofEntries(
            Map.entry("/api/products", "01 Productos"),
            Map.entry("/api/customers", "02 Clientes"),
            Map.entry("/api/cards/{cardId}/controls", "04 Controles por tarjeta"),
            Map.entry("/api/cards", "03 Tarjetas"),
            Map.entry("/api/plastics", "05 Plasticos y bureau"),
            Map.entry("/api/authorization", "06 Autorizador (ISO 8583 campo 39)"),
            Map.entry("/api/ledger", "07 Ledger prepago"),
            Map.entry("/api/accounting", "07 Ledger prepago"),
            Map.entry("/api/reconciliation", "08 Conciliacion con el core"),
            Map.entry("/api/disputes", "09 Aclaraciones y disputas"),
            Map.entry("/api/fraud", "10 Fraude en linea"),
            Map.entry("/api/guild", "11 Antifraude de industria (gremio)"),
            Map.entry("/api/reports", "12 Reportes"),
            Map.entry("/api/hsm", "13 HSM"),
            Map.entry("/api/promotions", "14 Promociones"),
            Map.entry("/api/audit", "15 Auditoria"));

    @Bean
    public OpenAPI cmsOpenApi() {
        return new OpenAPI()
                .components(new Components()
                        .addSecuritySchemes("bearer", new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                                .description("Token del IAM obtenido con POST /api/auth/login"))
                        .addSecuritySchemes("apiKey", new SecurityScheme().type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-Api-Key")
                                .description("Llave de sistema (security.api-key.value) para scripts e integraciones")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"))
                .addSecurityItem(new SecurityRequirement().addList("apiKey"))
                .info(new Info()
                .title("CMS Mexico -- emision y autorizador multiproducto")
                .version("feature/autorizador-multiproducto")
                .description("""
                        Sistema de gestion de tarjetas con autorizador propio para tres familias de producto:
                        debito prepagado (ledger interno), debito con saldo del core (Mifos/Fineract) y credito (linea).
                        Cubre emision, controles, plasticos, retenciones y captura, conciliacion con el core, aclaraciones,
                        fraude en linea, conexion antifraude de industria y reportes regulatorios.
                        Las respuestas del autorizador usan los codigos del campo 39 de ISO 8583 (00, 05, 14, 51, 54, 57, 59, 61, 62, 1A...).
                        """)
                .contact(new Contact().name("Equipo CMS Mexico")))
                .tags(TAGS.values().stream().distinct().sorted().map(t -> new Tag().name(t)).toList());
    }

    @Bean
    public OpenApiCustomizer tagByModule() {
        return api -> {
            if (api.getPaths() == null) return;
            api.getPaths().forEach((path, item) -> item.readOperations().forEach(op -> {
                String tag = TAGS.entrySet().stream()
                        .filter(e -> path.startsWith(e.getKey()))
                        .max((a, b) -> Integer.compare(a.getKey().length(), b.getKey().length()))
                        .map(Map.Entry::getValue).orElse("99 Otros");
                op.setTags(List.of(tag));
            }));
        };
    }
}
