package tfs.com.govtrace.api.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import tfs.com.govtrace.api.models.Despesa;
import tfs.com.govtrace.api.repositories.DespesaRepository;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class GeminiService {

    private final DespesaRepository repository;
    private final McpBrasilClient mcpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Client client;


    private final String MODEL = "gemini-2.0-flash-lite";

    public GeminiService(DespesaRepository repository,
                         McpBrasilClient mcpClient,
                         @Value("${govtrace.ai.gemini.api-key}") String apiKey) {
        this.repository = repository;
        this.mcpClient = mcpClient;
        // Inicializa o cliente oficial do Google uma única vez
        this.client = Client.builder().apiKey(apiKey).build();
    }

    // ── Carga de Dados ───────────────────────────────────────────────────────

    @Async
    public void carregarBaseTransparenciaAsync() {
        try {
            log.info("[GovTrace] Iniciando carga de Bragança Paulista...");

            Map<String, Object> args = Map.of(
                    "municipio", "braganca-paulista",
                    "exercicio", 2024,
                    "mes", 1
            );

            String textoBruto = mcpClient.callTool("tce_sp_consultar_despesas_sp", args);

            String jsonExtraido;
            if (textoBruto.trim().startsWith("[") || textoBruto.trim().startsWith("{")) {
                jsonExtraido = textoBruto;
            } else {
                log.info("[GovTrace] Usando SDK Oficial para extrair JSON...");
                String prompt = "Extraia APENAS as 10 primeiras despesas para um ARRAY JSON []. " +
                        "Use os campos: fornecedor, valor, empenho. Não responda nada além do JSON. Texto:\n" + textoBruto;
                jsonExtraido = chamarGeminiSDK(prompt);
            }

            if (!jsonExtraido.startsWith("ERRO:")) {
                processarResposta(jsonExtraido);
            } else {
                log.error("[GovTrace] Falha na IA: {}", jsonExtraido);
            }

        } catch (Exception e) {
            log.error("[GovTrace] Falha na carga: {}", e.getMessage());
        }
    }
    private String processarResposta(String jsonResposta) throws Exception {
        // O SDK novo já tende a vir limpo, mas vamos garantir o parse
        int start = jsonResposta.indexOf("[");
        int end = jsonResposta.lastIndexOf("]");
        if (start == -1 || end == -1) return "Erro: Formato inválido.";

        String jsonLimpo = jsonResposta.substring(start, end + 1);
        JsonNode despesasNode = mapper.readTree(jsonLimpo);

        int novos = 0;
        for (JsonNode item : despesasNode) {
            String empenho = item.path("empenho").asText("0");
            String idOrigem = "TCE-" + empenho;

            if (repository.existsByDocumentoOrigem(idOrigem)) continue;

            Despesa d = Despesa.builder()
                    .nomeFavorecido(item.path("fornecedor").asText("N/A"))
                    .valorPago(item.path("valor").asText("0,00"))
                    .documentoOrigem(idOrigem)
                    .build();

            repository.save(d);
            novos++;
        }

        log.info("[GovTrace] Sucesso! {} registros salvos.", novos);
        return String.format("Carga finalizada: %d novos registros salvos.", novos);
    }

    // ── Auditoria ────────────────────────────────────────────────────────────

    @Async
    public void analisarBase() {
        List<Despesa> pendentes = repository.findAll().stream()
                .filter(d -> d.getVereditoIA() == null || d.getVereditoIA().isEmpty())
                .toList();

        for (Despesa d : pendentes) {
            try {
                String prompt = String.format(
                        "Aja como Auditor. Analise o risco de fraude: Fornecedor %s | Valor %s. " +
                                "Responda apenas JSON: {\"veredito\": \"...\", \"score\": 0-100}",
                        d.getNomeFavorecido(), d.getValorPago());

                String resposta = chamarGeminiSDK(prompt);
                JsonNode node = mapper.readTree(resposta.substring(resposta.indexOf("{"), resposta.lastIndexOf("}") + 1));

                d.setVereditoIA(node.path("veredito").asText("Concluído"));
                d.setScoreRisco(node.path("score").asInt(0));
                repository.save(d);

                TimeUnit.SECONDS.sleep(5);
            } catch (Exception e) {
                log.error("Erro no registro {}: {}", d.getId(), e.getMessage());
            }
        }
    }

    // ── Motor Gemini (Via SDK) ──────────────────────────────────────────────

    private String chamarGeminiSDK(String prompt) {
        int tentativas = 3;
        for (int i = 0; i < tentativas; i++) {
            try {
                GenerateContentConfig config = GenerateContentConfig.builder()
                        .responseMimeType("application/json")
                        .build();
                return client.models.generateContent(MODEL, prompt, config).text();

            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (msg.contains("429") && i < tentativas - 1) {
                    long espera = extrairRetryAfter(msg);
                    log.warn("[Gemini] Rate limit, aguardando {}ms...", espera);
                    try { TimeUnit.MILLISECONDS.sleep(espera); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                } else {
                    log.error("[Gemini SDK Error] {}", msg);
                    return "ERRO: " + msg;
                }
            }
        }
        return "ERRO: Limite de tentativas atingido.";
    }

    private long extrairRetryAfter(String msg) {
        try {
            // Tenta ler "Please retry in XX.XXXs" da mensagem
            int idx = msg.indexOf("Please retry in ");
            if (idx != -1) {
                String sub = msg.substring(idx + 16);
                String segundos = sub.replaceAll("[^0-9.]", "").split("\\.")[0];
                return (Long.parseLong(segundos) + 2) * 1000L; // +2s de margem
            }
        } catch (Exception ignored) {}
        return 40_000L; // fallback: 40 segundos
    }

    }
