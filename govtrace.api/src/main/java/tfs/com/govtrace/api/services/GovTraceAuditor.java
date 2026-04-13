package tfs.com.govtrace.api.services;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface GovTraceAuditor {

    @SystemMessage("""
        Aja como um Auditor de Dados do Tribunal de Contas especialista em análise forense digital.
        FOCO: Integridade de gastos públicos e detecção de anomalias financeiras.
        
        DIRETRIZES:
        1. Identifique inconsistências em valores, fornecedores e datas.
        2. Sinalize riscos de fraude ou superfaturamento.
        3. Seja técnico e direto.
    """)
    String analisarGasto(@UserMessage String dadosParaAnalise);
}