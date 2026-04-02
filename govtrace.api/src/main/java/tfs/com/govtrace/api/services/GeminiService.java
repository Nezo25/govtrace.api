package tfs.com.govtrace.api.services;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
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
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(JsonReadFeature.ALLOW_LEADING_ZEROS_FOR_NUMBERS.mappedFeature())
            .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES.mappedFeature())
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES.mappedFeature());

    @Value("${govtrace.ai.gemini.api-key}")
    private String geminiKey;

    public GeminiService(DespesaRepository repository) {
        this.repository = repository;
    }

    private String getGeminiUrl() {
        return "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=" + geminiKey;
    }

    public String carregarBaseTransparencia() {
        try {
            log.info("[GovTrace] Sincronizando dados de Bragança Paulista via MCP...");
            Map<String, Object> params = Map.of(
                    "municipio", "Bragança Paulista",
                    "ano", 2024,
                    "mes", 1
            );

            String jsonResposta = consultarMcpBrasil("tce_sp", params);

            if (jsonResposta == null || jsonResposta.isEmpty()) {
                return "Erro: O MCP não retornou um JSON válido.";
            }

            JsonNode despesasNoPortal = mapper.readTree(jsonResposta);

            if (despesasNoPortal.isArray()) {
                for (JsonNode item : despesasNoPortal) {
                    Despesa d = Despesa.builder()
                            .nomeFavorecido(item.path("nm_fornecedor").asText("Desconhecido"))
                            .cnpjFavorecido(item.path("nr_cnpj_cpf_fornecedor").asText("000.000.000-00"))
                            .valorPago(item.path("vl_despesa").asText("0.00"))
                            .dataPagamento(item.path("dt_emissao_despesa").asText("2024-01-01"))
                            .documentoOrigem("MCP-" + item.path("nr_empenho").asText("SN"))
                            .build();
                    repository.save(d);
                }
                return "Sucesso: " + despesasNoPortal.size() + " registros importados.";
            }
            return "Nenhum dado encontrado.";
        } catch (Exception e) {
            log.error("[GovTrace] Erro na carga: {}", e.getMessage());
            return "Erro técnico: " + e.getMessage();
        }
    }

    public String analisarBase() {
        List<Despesa> despesas = repository.findAll().stream()
                .filter(d -> d.getVereditoIA() == null || d.getVereditoIA().isEmpty())
                .toList();

        if (despesas.isEmpty()) return "Sem pendências.";

        for (Despesa d : despesas) {
            executarFluxoAuditoria(d);
        }
        return "Auditoria concluída.";
    }

    private void executarFluxoAuditoria(Despesa d) {
        try {
            log.info("[GovTrace] Auditando: {}", d.getNomeFavorecido());
            String dadosCnpj = consultarMcpBrasil("brasilapi_cnpj", Map.of("cnpj", d.getCnpjFavorecido()));

            String prompt = String.format(
                    "Aja como Auditor do TCE-SP. Analise o risco de fraude:\n" +
                            "FORNECEDOR: %s | VALOR: R$ %s\n" +
                            "DADOS CNPJ: %s\n" +
                            "Responda estritamente em JSON: {\"veredito\": \"texto\", \"score\": 0-100}",
                    d.getNomeFavorecido(), d.getValorPago(), dadosCnpj
            );

            String respostaIA = chamarGemini(prompt);
            JsonNode root = mapper.readTree(respostaIA);

            d.setVereditoIA(root.path("veredito").asText());
            d.setScoreRisco(root.path("score").asInt());
            repository.save(d);

            TimeUnit.SECONDS.sleep(2);
        } catch (Exception e) {
            log.error("[GovTrace] Erro na auditoria do ID {}: {}", d.getId(), e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private String chamarGemini(String prompt) {
        try {
            Map<String, Object> request = Map.of("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));
            ResponseEntity<Map> response = restTemplate.postForEntity(getGeminiUrl(), request, Map.class);

            if (response.getBody() == null) return "{}";

            List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.getBody().get("candidates");
            Map<String, Object> firstCandidate = candidates.get(0);
            Map<String, Object> content = (Map<String, Object>) firstCandidate.get("content");
            List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
            String text = (String) parts.get(0).get("text");

            return text.replace("```json", "").replace("```", "").trim();
        } catch (Exception e) {
            log.error("[GovTrace] Erro Gemini: {}", e.getMessage());
            return "{\"veredito\": \"Erro IA\", \"score\": 0}";
        }
    }

    private String consultarMcpBrasil(String toolName, Map<String, Object> arguments) {
        try {
            log.info("[GovTrace] Chamando ferramenta MCP via Python: {}", toolName);
            String jsonArgs = mapper.writeValueAsString(arguments);

            ProcessBuilder pb = new ProcessBuilder("python", "-m", "mcp_brasil.server", "call", toolName);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (var os = process.getOutputStream()) {
                os.write(jsonArgs.getBytes());
                os.flush();
            }

            var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            process.waitFor(60, TimeUnit.SECONDS);

            String raw = output.toString();

            int startArray = raw.indexOf("[");
            int startObj = raw.indexOf("{");

            int start = -1;
            if (startArray != -1 && startObj != -1) start = Math.min(startArray, startObj);
            else if (startArray != -1) start = startArray;
            else if (startObj != -1) start = startObj;

            int endArray = raw.lastIndexOf("]");
            int endObj = raw.lastIndexOf("}");
            int end = Math.max(endArray, endObj);

            if (start != -1 && end != -1 && start < end) {
                String finalJson = raw.substring(start, end + 1).trim();
                log.info("[GovTrace] JSON extraído com sucesso.");
                return finalJson;
            }

            log.error("[GovTrace] Falha ao localizar JSON na saída.");
            return "";
        } catch (Exception e) {
            log.error("[GovTrace] Falha crítica na integração MCP: {}", e.getMessage());
            return "";
        }
    }
}