package tfs.com.govtrace.api.services;


import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
    @ConditionalOnProperty(name = "govtrace.ia.provider", havingValue = "local")
    public class LocalAuditor implements IAuditorAgente {
        public String analisar(String contexto) {
            return "Veredito via IA Local (Ollama): ...";
        }
        public String getTipoIA() { return "LOCAL_GEMMA"; }
    }

