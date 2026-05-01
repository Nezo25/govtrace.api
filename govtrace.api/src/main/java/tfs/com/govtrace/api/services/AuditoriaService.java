package tfs.com.govtrace.api.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tfs.com.govtrace.api.models.Despesa;
import tfs.com.govtrace.api.repositories.DespesaRepository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Orquestrador de Inteligência e Dados da Three Frog System (TFS).
 * Implementa processamento assíncrono para garantir performance de mercado.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AuditoriaService {

    private final GovTraceAuditor auditorIA;
    private final DespesaRepository repository;
    private final McpBrasilClient mcpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    // ── CARGA DE DADOS ASSÍNCRONA (CICLO ANUAL) ──────────────────────────

    /**
     * Realiza a carga de 12 meses de dados em background.
     * Resolve a limitação de 30 registros por chamada do MCP de forma elegante.
     */
    @Async
    public CompletableFuture<Void> carregarBaseTransparenciaAnual(String municipioSlug, int ano) {
        log.info("[Carga Task] Iniciando indexação assíncrona do exercício {} para {}...", ano, municipioSlug);

        int totalSalvoNoAno = 0;

        for (int m = 1; m <= 12; m++) {
            try {
                log.info("[Carga Task] Processando mês {}/{}...", m, ano);

                String jsonRaw = mcpClient.callTool(
                        "tce_sp_consultar_despesas_sp",
                        Map.of(
                                "municipio", municipioSlug,
                                "exercicio", ano,
                                "mes", m
                        )
                );

                int salvosNoMes = mapearESalvar(jsonRaw, municipioSlug);
                totalSalvoNoAno += salvosNoMes;

                // Delay estratégico para não sobrecarregar o túnel MCP
                Thread.sleep(400);

            } catch (Exception e) {
                log.error("[Carga Task] Erro no mês {}/{}: {}", m, ano, e.getMessage());
            }
        }
        log.info("[Carga Task] CONCLUÍDA! {} registros indexados para {}.", totalSalvoNoAno, municipioSlug);
        return CompletableFuture.completedFuture(null);
    }

    private int mapearESalvar(String jsonRaw, String municipio) {
        int count = 0;
        // Tenta parsear como JSON estruturado apenas
        if (jsonRaw != null && jsonRaw.contains("{")) {
            try {
                String jsonLimpo = jsonRaw.substring(jsonRaw.indexOf("{"));
                JsonNode root = mapper.readTree(jsonLimpo);
                JsonNode items = root.path("result").path("structuredContent").path("result");

                if (items.isArray() && !items.isEmpty()) {
                    for (JsonNode node : items) {
                        if (salvarDespesa(node, municipio)) count++;
                    }
                    return count;
                }
            } catch (Exception e) {
                log.warn("[Carga Task] Formato JSON não estruturado recebido do MCP. A carga deste lote foi ignorada.");
            }
        } else {
            log.warn("[Carga Task] Retorno vazio ou sem JSON do MCP.");
        }

        // Fim da Era Regex: Se não é JSON estruturado, a gente não suja o banco.
        return count;
    }

    private boolean salvarDespesa(JsonNode node, String municipio) {
        String empenho = node.path("empenho").asText(node.path("documento").asText(""));

        // Evita duplicidade e garante que tem ID de origem
        if (empenho.isBlank() || repository.existsByDocumentoOrigem(empenho)) {
            return false;
        }

        // 1. Extração segura de valores para BigDecimal
        String valorStr = node.path("valor").asText("0,00");
        BigDecimal valorConvertido;
        try {
            valorConvertido = new BigDecimal(valorStr.replace(".", "").replace(",", "."));
        } catch (NumberFormatException e) {
            log.debug("[Carga Task] Valor inválido recebido '{}'. Convertendo para 0.", valorStr);
            valorConvertido = BigDecimal.ZERO;
        }

        // 2. Montagem do objeto tipado
        Despesa d = Despesa.builder()
                .municipio(municipio)
                .nomeFavorecido(node.path("favorecido").asText("N/A"))
                .valorPago(valorConvertido)
                .documentoOrigem(empenho)
                .dataPagamento(node.path("data").asText("2024-01-01"))
                .build();

        repository.save(d);
        return true;
    }

    // ── AUDITORIA COM IA (GROQ) ──────────────────────────────────────────
    // NOTA: Esta parte sofrerá refatoração pesada na Fase 3 e 4.
    // Por enquanto, ela se mantém funcionando para não quebrar a compilação.

    /**
     * Auditoria assíncrona. Dispara as análises sem bloquear a aplicação.
     */
    @Async
    public void analisarBaseComIA() {
        List<Despesa> pendentes = repository.findPendentesDeAuditoria()
                .stream().limit(15).toList();

        if (pendentes.isEmpty()) {
            log.info("[Auditoria Task] Sem despesas pendentes para análise.");
            return;
        }

        log.info("[Auditoria Task] Analisando lote de {} registros...", pendentes.size());

        for (Despesa despesa : pendentes) {
            try {
                // Intervalo de segurança para o Rate Limit do Llama 3.3 70B (Free Tier)
                Thread.sleep(3000);

                String context = String.format("Cidade: %s | Favorecido: %s | Valor: R$ %s | Doc: %s",
                        despesa.getMunicipio(), despesa.getNomeFavorecido(),
                        despesa.getValorPago().toString(), // Atualizado para chamar .toString() do BigDecimal
                        despesa.getDocumentoOrigem());

                String veredito = auditorIA.analisarGasto(context);
                despesa.setVereditoIA(veredito);
                despesa.setScoreRisco(extrairScore(veredito));
                repository.save(despesa);

                log.info("[Auditoria Task] Veredito concluído para: {}", despesa.getNomeFavorecido());

            } catch (Exception e) {
                log.error("[Auditoria Task] Falha no ID {}: {}", despesa.getId(), e.getMessage());
                if (e.getMessage() != null && e.getMessage().contains("429")) {
                    log.warn("[Auditoria Task] Rate Limit atingido. Encerrando lote.");
                    break;
                }
            }
        }
        log.info("[Auditoria Task] Lote finalizado.");
    }

    private Integer extrairScore(String analise) {
        if (analise == null) return 50;
        String t = analise.toUpperCase();
        if (t.contains("CRÍTICO")) return 95;
        if (t.contains("ALTO RISCO")) return 85;
        if (t.contains("SUSPEITO")) return 70;
        if (t.contains("ATENÇÃO")) return 55;
        return 15;
    }
}