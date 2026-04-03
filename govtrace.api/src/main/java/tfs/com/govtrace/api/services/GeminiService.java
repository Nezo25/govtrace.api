package tfs.com.govtrace.api.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
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
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${govtrace.ai.gemini.api-key}")
    private String geminiKey;

    public GeminiService(DespesaRepository repository, McpBrasilClient mcpClient) {
        this.repository = repository;
        this.mcpClient  = mcpClient;
    }

    private String getGeminiUrl() {
        return "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=" + geminiKey;
    }

    /**
     * Sincroniza dados REAIS do portal do TCE-SP via MCP-Brasil
     */
    public String carregarBaseTransparencia() {
        try {
            log.info("[GovTrace] MCP-BRASIL: Sincronizando despesas reais de Bragança Paulista...");

            Map<String, Object> params = Map.of(
                    "municipio", "Bragança Paulista",
                    "ano", 2024,
                    "mes", 1
            );


            String jsonResposta = mcpClient.callTool("tce_sp", params);

            if (jsonResposta == null || jsonResposta.trim().isEmpty() ||
                    (!jsonResposta.trim().startsWith("[") && !jsonResposta.trim().startsWith("{"))) {
                log.error("[GovTrace] Resposta inválida do MCP: {}", jsonResposta);
                return "Erro: O minerador não retornou um JSON válido.";
            }

            JsonNode despesasNoPortal = mapper.readTree(jsonResposta);

            if (despesasNoPortal.isArray()) {
                for (JsonNode item : despesasNoPortal) {
                    Despesa d = Despesa.builder()
                            .nomeFavorecido(item.path("nm_fornecedor").asText("Desconhecido"))
                            .cnpjFavorecido(item.path("nr_cnpj_cpf_fornecedor").asText("00000000000000"))
                            .valorPago(item.path("vl_despesa").asText("0.00"))
                            .dataPagamento(item.path("dt_emissao_despesa").asText("2024-01-01"))
                            .documentoOrigem("TCE-SP-" + item.path("nr_empenho").asText("SN"))
                            .build();
                    repository.save(d);
                }
                return "Sucesso! " + despesasNoPortal.size() + " registros reais importados de Bragança Paulista.";
            }
            return "Nenhum dado encontrado para o período.";

        } catch (Exception e) {
            log.error("[GovTrace] Erro técnico na integração: {}", e.getMessage());
            return "Erro técnico: " + e.getMessage();
        }
    }

    public String analisarBase() {
        List<Despesa> pendentes = repository.findAll().stream()
                .filter(d -> d.getVereditoIA() == null || d.getVereditoIA().isEmpty())
                .toList();

        if (pendentes.isEmpty()) return "Sem pendências para auditar.";

        log.info("[GovTrace] Auditando {} registros com Gemini...", pendentes.size());
        pendentes.forEach(this::executarFluxoAuditoria);
        return "Auditoria concluída.";
    }

    private void executarFluxoAuditoria(Despesa d) {
        try {
            log.info("[GovTrace] Buscando dados CNPJ para: {}", d.getNomeFavorecido());

            // Voltamos para o nome original da ferramenta brasilapi_cnpj
            String dadosCnpj = mcpClient.callTool("brasilapi_cnpj", Map.of("cnpj", d.getCnpjFavorecido()));

            String prompt = String.format(
                    "Aja como Auditor do TCE-SP. Analise o risco de fraude:\n" +
                            "FORNECEDOR: %s | VALOR: R$ %s\n" +
                            "DADOS REAIS CNPJ: %s\n\n" +
                            "Responda estritamente em JSON: {\"veredito\": \"texto\", \"score\": 0-100}",
                    d.getNomeFavorecido(), d.getValorPago(), dadosCnpj
            );

            String respostaIA = chamarGemini(prompt);
            JsonNode root = mapper.readTree(respostaIA);

            d.setVereditoIA(root.path("veredito").asText("Análise indisponível"));
            d.setScoreRisco(root.path("score").asInt(0));
            repository.save(d);

            TimeUnit.SECONDS.sleep(1);
        } catch (Exception e) {
            log.error("[GovTrace] Falha no registro {}: {}", d.getId(), e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private String chamarGemini(String prompt) {
        try {
            Map<String, Object> body = Map.of("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));
            ResponseEntity<Map> response = restTemplate.postForEntity(getGeminiUrl(), body, Map.class);

            if (response.getBody() == null) return "{}";

            List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.getBody().get("candidates");
            Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
            List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
            return parts.get(0).get("text").toString().replaceAll("(?s)```json\\s*|```\\s*", "").trim();
        } catch (Exception e) {
            return "{\"veredito\": \"Erro na IA\", \"score\": 0}";
        }
    }
}