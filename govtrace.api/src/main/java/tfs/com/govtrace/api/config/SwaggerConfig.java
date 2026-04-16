package tfs.com.govtrace.api.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI govTraceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("GovTrace API - Auditoria com IA")
                        .description("Plataforma da Three Frog System (TFS) para auditoria automatizada de gastos públicos via IA e dados do TCE-SP.")
                        .version("v1.0.0")
                        .contact(new Contact()
                                .name("Enzo - TFS")
                                .email("suporte@threefrog.com"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("http://springdoc.org")));
    }
}