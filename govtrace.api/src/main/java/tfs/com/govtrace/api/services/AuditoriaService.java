package tfs.com.govtrace.api.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tfs.com.govtrace.api.models.Despesa;
import tfs.com.govtrace.api.repositories.DespesaRepository;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Orquestrador principal da Three Frog System (TFS).
 * Gerencia a carga dinâmica via MCP Brasil e auditoria via Groq.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AuditoriaService {

    private final GovTraceAuditor auditorIA;
    private final DespesaRepository repository;
    private final McpBrasilClient mcpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    // Rate limiting para não estourar o Free Tier da Groq (Llama 3.3 70B)
    private final Semaphore groqRateLimiter = new Semaphore(4);
    private static final long DELAY_MS = 300;

    // ── Carga de Dados Dinâmica ──────────────────────────────────────────

    public void carregarBaseTransparenciaAsync(String municipioSlug, int ano, int mes) {
        log.info("[Carga] Solicitando dados de {} ({} / {}) via MCP...", municipioSlug, mes, ano);
        try {
            String jsonRaw = mcpClient.callTool(
                    "tce_sp_consultar_despesas_sp",
                    Map.of(
                            "municipio", municipioSlug,
                            "exercicio", ano,
                            "mes", mes
                    )
            );

            log.info("[Carga] Dados recebidos. Iniciando processamento...");
            int salvos = mapearESalvar(jsonRaw, municipioSlug);
            log.info("[Carga] Finalizada! Total no banco: {}", repository.count());

        } catch (Exception e) {
            log.error("[Carga] Erro ao comunicar com MCP: {}", e.getMessage());
        }
    }

    /**
     * Parser Híbrido: Resolve o erro do caractere '*' (Markdown) limpando a String
     * antes de converter para JSON e usando Regex como fallback.
     */
    private int mapearESalvar(String jsonRaw, String municipio) {
        int count = 0;

        // 1. TENTATIVA: Tratar como JSON (Caso o servidor envie o protocolo completo)
        if (jsonRaw != null && jsonRaw.contains("{")) {
            try {
                String jsonLimpo = jsonRaw.substring(jsonRaw.indexOf("{"));
                JsonNode root = mapper.readTree(jsonLimpo);
                JsonNode items = root.path("result").path("structuredContent").path("result");

                if (items.isArray() && !items.isEmpty()) {
                    log.info("[Carga] Extraindo registros do array estruturado...");
                    for (JsonNode node : items) {
                        if (salvarDespesa(node, municipio)) count++;
                    }
                    return count; // Se salvou via JSON, encerra aqui
                }
            } catch (Exception e) {
                log.warn("[Carga] Falha ao ler como JSON estruturado, tentando Regex...");
            }
        }

        // 2. TENTATIVA: Tratar como Texto/Markdown (O que está no seu log atual)
        log.info("[Carga] Analisando resposta como texto formatado via Regex...");
        count = extrairViaRegex(jsonRaw, municipio);

        return count;
    }

    private int extrairViaRegex(String text, String municipio) {
        int count = 0;
        // Este Regex ignora os asteriscos e foca no padrão: - [Status] Nome: R$ Valor (empenho ID)
        Pattern pattern = Pattern.compile("- \\[(.*?)\\] (.*?): R\\$ (.*?) \\(empenho (.*?)\\)");
        Matcher matcher = pattern.matcher(text);

        while (matcher.find()) {
            try {
                String favorecido = matcher.group(2).trim();
                String valor = matcher.group(3).trim();
                String empenho = matcher.group(4).trim();

                if (!repository.existsByDocumentoOrigem(empenho)) {
                    Despesa d = Despesa.builder()
                            .municipio(municipio)
                            .nomeFavorecido(favorecido)
                            .valorPago(valor)
                            .documentoOrigem(empenho)
                            .dataPagamento("2024-01-01")
                            .build();
                    repository.save(d);
                    count++;
                }
            } catch (Exception e) {
                log.debug("Linha inválida ignorada pelo Regex.");
            }
        }
        return count;
    }
    private boolean salvarDespesa(JsonNode node, String municipio) {
        String empenho = node.path("empenho").asText(node.path("documento").asText(""));
        if (empenho.isBlank() || repository.existsByDocumentoOrigem(empenho)) return false;

        Despesa d = Despesa.builder()
                .municipio(municipio)
                .nomeFavorecido(node.path("favorecido").asText("N/A"))
                .valorPago(node.path("valor").asText("0,00"))
                .documentoOrigem(empenho)
                .dataPagamento(node.path("data").asText("2024-01-01"))
                .build();
        repository.save(d);
        return true;
    }

    // ── Auditoria com IA (Groq) ──────────────────────────────────────────

    public void analisarBaseComIA() {
        // Pega apenas 5 por vez para não estourar o limite de tokens da Groq
        List<Despesa> pendentes = repository.findPendentesDeAuditoria()
                .stream().limit(5).toList();

        if (pendentes.isEmpty()) {
            log.info("[Auditoria] Nenhuma despesa pendente.");
            return;
        }

        log.info("[Auditoria] Analisando {} registros sequencialmente para respeitar o limite...", pendentes.size());
        int concluidas = 0;

        // Trocamos .parallelStream() por um for comum (sequencial)
        for (Despesa despesa : pendentes) {
            try {
                // Espera 3 segundos entre cada chamada (Segurança total para o Free Tier)
                Thread.sleep(3000);

                String context = String.format("Cidade: %s | Favorecido: %s | Valor: R$ %s | Doc: %s",
                        despesa.getMunicipio(), despesa.getNomeFavorecido(),
                        despesa.getValorPago(), despesa.getDocumentoOrigem());

                String veredito = auditorIA.analisarGasto(context);
                despesa.setVereditoIA(veredito);
                despesa.setScoreRisco(extrairScore(veredito));
                repository.save(despesa);

                concluidas++;
                log.info("[Auditoria] {}/{} concluído: {}", concluidas, pendentes.size(), despesa.getNomeFavorecido());

            } catch (Exception e) {
                log.error("[Auditoria] Erro no registro {}: {}", despesa.getId(), e.getMessage());
                // Se der erro de Rate Limit (429), para o loop para não queimar a chave
                if (e.getMessage().contains("429")) break;
            }
        }
        log.info("[Auditoria] Ciclo finalizado. {} registros auditados.", concluidas);
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