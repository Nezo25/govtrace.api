package tfs.com.govtrace.api.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tfs.com.govtrace.api.models.Despesa;
import tfs.com.govtrace.api.repositories.DespesaRepository;

import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuditoriaService {

    private final GovTraceAuditor auditorIA;
    private final DespesaRepository repository;

    /**
     * Analisa despesas usando os nomes de campos oficiais do Portal da Transparência.
     */
    @Transactional
    public void analisarBaseComIA() {
        List<Despesa> despesasPendentes = repository.findAll()
                .stream()
                .filter(d -> d.getScoreRisco() == null)
                .toList();

        log.info("Iniciando auditoria de {} despesas oficiais via Virtual Threads...", despesasPendentes.size());

        despesasPendentes.parallelStream().forEach(despesa -> {
            try {
                // 1. Contexto montado com as variáveis padrão do Portal
                String contexto = String.format(
                        "ID: %s, Favorecido: %s, CNPJ: %s, Valor Pago: R$ %s, Data: %s, Doc Origem: %s",
                        despesa.getId(),
                        despesa.getNomeFavorecido(),
                        despesa.getCnpjFavorecido(),
                        despesa.getValorPago(),
                        despesa.getDataPagamento(),
                        despesa.getDocumentoOrigem()
                );

                // 2. Chamada à Groq (Llama 3.3 70B)
                String resultadoAnalise = auditorIA.analisarGasto(contexto);

                // 3. Persistência dos resultados no padrão da Entity
                despesa.setVereditoIA(resultadoAnalise);
                despesa.setScoreRisco(extrairScore(resultadoAnalise));

                repository.save(despesa);

            } catch (Exception e) {
                log.error("Falha na auditoria da despesa ID {}: {}", despesa.getId(), e.getMessage());
            }
        });
    }

    private Integer extrairScore(String analise) {
        String texto = analise.toUpperCase();
        if (texto.contains("CRÍTICO") || texto.contains("ALTO RISCO")) return 95;
        if (texto.contains("SUSPEITO") || texto.contains("ATENÇÃO")) return 75;
        if (texto.contains("REGULAR")) return 20;
        return 50; // Default caso a IA seja inconclusiva
    }

    public void carregarBaseTransparenciaAsync() {
        log.info("Integrando com API do Portal da Transparência...");
    }
}