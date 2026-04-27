package tfs.com.govtrace.api.services;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "govtrace.ia.provider", havingValue = "groq")
public class GroqAuditor implements IAuditorAgente {

    // O seu componente que faz a chamada real para a Groq
    private final GovTraceAuditor groqClient;

    @Override
    public String analisar(String contexto) {
        // O "Veredito" é o retorno da chamada para a Groq (Llama 3.3)
        // Aqui a IA recebe o texto da despesa e devolve a análise
        try {
            return groqClient.analisarGasto(contexto);
        } catch (Exception e) {
            return "ERRO NA ANÁLISE: " + e.getMessage();
        }
    }

    @Override
    public String getTipoIA() {
        return "GROQ_LLAMA_3.3_CLOUD";
    }
}