package ke.co.technopick.mfi.core.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Metadata for the generated OpenAPI contract (reviewed copy: shared/contracts/openapi). */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI coreBankingApi() {
        return new OpenAPI()
            .info(new Info()
                .title("Technopick Core Banking API")
                .version("v1")
                .description("Core banking and switch authorization API. Money values are decimal strings. "
                        + "Errors are RFC 9457 problem details."))
            .components(new Components().addSecuritySchemes("bearer",
                new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
            .addSecurityItem(new SecurityRequirement().addList("bearer"))
            // Relative, so the contract is identical in every environment.
            .servers(List.of(new Server().url("/").description("Relative to the deployment base URL")));
    }
}
