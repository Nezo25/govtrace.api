package tfs.com.govtrace.api.services;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;
import dev.langchain4j.service.spring.AiServiceWiringMode;

@AiService(
        wiringMode = AiServiceWiringMode.EXPLICIT,
        chatModel = "groqChatModel"
)
public interface GovTraceAuditor {

    @SystemMessage("""
    Aja como auditor forense (Lei 14.133/21).
    Analise despesas por: 1. Nome favorecido genérico vs Valor. 2. Limites dispensa (Engenharia 119k / Compras 59k). 3. Falta de dados.
    
    RESPONDA APENAS:
    JUSTIFICATIVA: [Texto curto, max 20 palavras]
    RISCO: [REGULAR, ATENÇÃO, SUSPEITO, ALTO RISCO ou CRÍTICO]
    SCORE: [0-100]
    """)
    String analisarGasto(@UserMessage String dadosParaAnalise);
}