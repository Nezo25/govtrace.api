package tfs.com.govtrace.api.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import tfs.com.govtrace.api.models.Despesa;
import tfs.com.govtrace.api.repositories.DespesaRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuditoriaService {

    private final GovTraceAuditor auditorIA;
    private final DespesaRepository repository;
    private final McpBrasilClient mcpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final KafkaTemplate<String, String> kafkaTemplate;

    // --- PRODUCER: Envia as solicitações para o tópico ---
    public void solicitarCargaAnual(String municipio, int ano) {
        log.info("[Kafka] Criando eventos de carga para {}/{}", municipio, ano);
        for (int m = 1; m <= 12; m++) {
            String payload = String.format("%s;%d;%d", municipio, ano, m);
            kafkaTemplate.send("fila-carga-tce", payload);
        }
    }

    // --- CONSUMER: Processa cada mês conforme a fila anda ---
    @KafkaListener(topics = "fila-carga-tce", groupId = "govtrace-group")
    public void processarMensagemCarga(String payload) {
        String[] dados = payload.split(";");
        String municipio = dados[0];
        int ano = Integer.parseInt(dados[1]);
        int mes = Integer.parseInt(dados[2]);

        log.info("[Kafka Consumer] Processando Mês: {}/{}", mes, ano);

        try {
            String jsonRaw = mcpClient.callTool(
                    "tce_sp_consultar_despesas_sp",
                    Map.of("municipio", municipio, "exercicio", ano, "mes", mes)
            );

            int salvos = mapearESalvar(jsonRaw, municipio);
            log.info("[Kafka Consumer] Mês {} finalizado. Registros novos: {}", mes, salvos);

            Thread.sleep(800);
        } catch (Exception e) {
            log.error("[Kafka Consumer] Erro ao processar {}/{}: {}", mes, ano, e.getMessage());
        }
    }

    private int mapearESalvar(String jsonRaw, String municipio) {
        int count = 0;
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
                log.warn("[Carga] Falha no JSON estruturado, tentando Regex fallback...");
            }
        }
        return extrairViaRegex(jsonRaw, municipio);
    }

    private int extrairViaRegex(String text, String municipio) {
        int count = 0;
        Pattern pattern = Pattern.compile("- \\[(.*?)\\] (.*?): R\\$ (.*?) \\(empenho (.*?)\\)");
        Matcher matcher = pattern.matcher(text);

        while (matcher.find()) {
            try {
                String empenho = matcher.group(4).trim();
                if (!repository.existsByDocumentoOrigem(empenho)) {
                    Despesa d = Despesa.builder()
                            .municipio(municipio)
                            .nomeFavorecido(matcher.group(2).trim())
                            .valorPago(matcher.group(3).trim())
                            .documentoOrigem(empenho)
                            .dataPagamento("2024-01-01")
                            .build();
                    repository.save(d);
                    count++;
                }
            } catch (Exception e) {
                log.debug("Linha ignorada no Regex.");
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

    // --- AUDITORIA IA ---
    public void analisarBaseComIA() {
        List<Despesa> pendentes = repository.findPendentesDeAuditoria()
                .stream().limit(15).toList();

        if (pendentes.isEmpty()) return;

        for (Despesa despesa : pendentes) {
            try {
                Thread.sleep(3000);
                String context = String.format("Cidade: %s | Favorecido: %s | Valor: R$ %s | Doc: %s",
                        despesa.getMunicipio(), despesa.getNomeFavorecido(),
                        despesa.getValorPago(), despesa.getDocumentoOrigem());

                String veredito = auditorIA.analisarGasto(context);
                despesa.setVereditoIA(veredito);
                despesa.setScoreRisco(extrairScore(veredito));
                repository.save(despesa);
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("429")) break;
            }
        }
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

    // --- NOVAS FUNCIONALIDADES PARA DASHBOARD E EMENDAS ---

    /**
     * Gera estatísticas consolidadas para os gráficos do Front-End.
     */
    public Map<String, Object> obterEstatisticasDashboard() {
        List<Despesa> todas = repository.findAll();

        long criticos = todas.stream().filter(d -> d.getScoreRisco() != null && d.getScoreRisco() >= 80).count();
        long suspeitos = todas.stream().filter(d -> d.getScoreRisco() != null && d.getScoreRisco() >= 50 && d.getScoreRisco() < 80).count();
        long regulares = todas.stream().filter(d -> d.getScoreRisco() != null && d.getScoreRisco() < 50).count();
        long pendentes = todas.stream().filter(d -> d.getVereditoIA() == null).count();

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalProcessado", todas.size());
        stats.put("criticos", criticos);
        stats.put("suspeitos", suspeitos);
        stats.put("regulares", regulares);
        stats.put("aguardandoIA", pendentes);

        return stats;
    }

    /**
     * Tenta vincular despesas existentes a emendas parlamentares pelo nome do favorecido.
     * Útil para detectar desvios em verbas carimbadas.
     */
    public void realizarCruzamentoEmendas() {
        log.info("[Auditoria] Iniciando cruzamento de dados Despesas x Emendas...");
        // Futura implementação: Repositorio de Emendas parlamentares
    }
}