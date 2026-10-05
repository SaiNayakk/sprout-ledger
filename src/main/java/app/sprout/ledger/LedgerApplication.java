package app.sprout.ledger;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The ledger: Sprout's double-entry books and the single source of truth for money.
 *
 * <p>Runs on its own ({@link #main}) or inside a shared JVM host, which calls {@link #builder()}.
 * Either way it reads {@code ledger.yml}, never {@code application.yml}, so services sharing a
 * host can't read each other's settings.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class LedgerApplication {

    public static final String CONFIG_NAME = "ledger";

    public static void main(String[] args) {
        builder().run(args);
    }

    public static SpringApplicationBuilder builder() {
        return new SpringApplicationBuilder(LedgerApplication.class)
                .properties("spring.config.name=" + CONFIG_NAME);
    }
}
