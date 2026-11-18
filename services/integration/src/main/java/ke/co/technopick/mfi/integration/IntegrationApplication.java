package ke.co.technopick.mfi.integration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Apache Camel integration runtime (separate deployable, integration network zone). */
@SpringBootApplication
@ConfigurationPropertiesScan
public class IntegrationApplication {

    public static void main(String[] args) {
        SpringApplication.run(IntegrationApplication.class, args);
    }
}
