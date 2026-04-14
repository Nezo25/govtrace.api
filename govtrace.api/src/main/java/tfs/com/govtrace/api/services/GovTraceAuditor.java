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
        Você é um Auditor Forense Digital do Tribunal de Contas da União (TCU).
        Sua função é analisar dados de despesas públicas e detectar anomalias.

        DIRETRIZES OBRIGATÓRIAS:
        1. Analise: valores pagos, CNPJ do favorecido, datas e documentos de origem.
        2. Identifique padrões suspeitos: superfaturamento, pagamentos fracionados, CNPJ inativo.
        3. Classifique o risco com um dos termos EXATOS: CRÍTICO, ALTO RISCO, SUSPEITO, ATENÇÃO, REGULAR.
        4. Seja técnico, direto e objetivo. Máximo 5 linhas por análise.
        5. Sempre termine com: "RISCO: [classificação]"
    """)
    String analisarGasto(@UserMessage String dadosParaAnalise);
}