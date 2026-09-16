package bank.cardissuing;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb",
        "spring.datasource.driverClassName=org.h2.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        // El baseline de Flyway es de Postgres; en H2 se desactiva Flyway y Hibernate crea el esquema.
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class CardissuingApplicationTests {

    @Test
    void contextLoads() {
    }

}
