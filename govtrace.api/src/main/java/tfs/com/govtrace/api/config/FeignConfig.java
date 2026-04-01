package tfs.com.govtrace.api.config;

import feign.Client;
import feign.Logger;
import feign.RequestInterceptor;
import feign.hc5.ApacheHttp5Client;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FeignConfig {

    @Bean
    public RequestInterceptor requestInterceptor() {
        return requestTemplate -> {

            requestTemplate.header("chave-api-dados", "9797fb114b227441123a7916e94c8a34");


            requestTemplate.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36 OPR/128.0.0.0");


            requestTemplate.header("Accept", "application/json, text/plain, */*");
            requestTemplate.header("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7");
            requestTemplate.header("Connection", "keep-alive");
            requestTemplate.header("Cache-Control", "no-cache");
            requestTemplate.header("Pragma", "no-cache");
        };
    }

    @Bean
    Logger.Level feignLoggerLevel() {
        return Logger.Level.FULL;
    }

    @Bean
    public Client feignClient() {
        // Força o uso do motor Apache HC5, que é melhor para emular o navegador
        return new ApacheHttp5Client();
    }
}