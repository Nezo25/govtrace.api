package tfs.com.govtrace.api.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import tfs.com.govtrace.api.controllers.PortalTransparenciaClient;
import tfs.com.govtrace.api.models.Despesa;
import tfs.com.govtrace.api.repositories.DespesaRepository;

import java.util.List;
import java.util.Map;

@Service
public class GeminiService {

    @Autowired
    private DespesaRepository repository;

    @Autowired
    private PortalTransparenciaClient portalClient;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    private final String GEMINI_KEY = "AIzaSyD0QT1srukZViQMz1CfrVIp7duiCTP7r-k";
    private final String GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=" + GEMINI_KEY;

    public String analisarBase() {
        List<Despesa> despesas = repository.findAll().stream()
                .filter(d -> d.getVereditoIA() == null || d.getVereditoIA().isEmpty())
                .toList();

        if (despesas.isEmpty()) return "Nada para analisar agora.";
        return executarAuditoria(despesas);
    }

    public String recuperarFalhas() {
        List<Despesa> falhas = repository.findAll().stream()
                .filter(d -> d.getVereditoIA() != null && d.getVereditoIA().contains("Erro na IA"))
                .toList();

        if (falhas.isEmpty()) return "Nenhuma falha para corrigir!";
        return executarAuditoria(falhas);
    }

    private String executarAuditoria(List<Despesa> lista) {
        for (Despesa d : lista) {
            try {
                String dadosItens = buscarItensReais(d.getDocumentoOrigem());
                String dadosLicitacao = buscarParticipantesReais(d.getDocumentoOrigem());

                String prompt = String.format(
                        "Aja como auditor do TCU. Analise o risco de corrupção:\n" +
                                "DESPESA: Favorecido %s recebeu R$ %s.\n" +
                                "ITENS: %s\n" +
                                "LICITACAO: %s\n" +
                                "Retorne apenas um JSON: {\"veredito\": \"texto curto\", \"score\": 0}",
                        d.getNomeFavorecido(), d.getValorPago(), dadosItens, dadosLicitacao
                );

                String respostaIA = chamarGemini(prompt);
                Map<String, Object> resultadoIA = mapper.readValue(respostaIA, Map.class);

                d.setVereditoIA(String.valueOf(resultadoIA.get("veredito")));
                Object scoreObj = resultadoIA.get("score");
                d.setScoreRisco(scoreObj instanceof Integer ? (Integer) scoreObj : Integer.valueOf(scoreObj.toString()));

                repository.save(d);
                System.out.println("ID " + d.getId() + " auditado. Score: " + d.getScoreRisco());

                Thread.sleep(15000);

            } catch (Exception e) {
                System.err.println("Erro no ID " + d.getId() + ": " + e.getMessage());
            }
        }
        return "Auditoria finalizada.";
    }

    private String chamarGemini(String prompt) {
        try {
            Map<String, Object> requestBody = Map.of("contents", List.of(Map.of("parts", List.of(Map.of("text", prompt)))));
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(GEMINI_URL, entity, Map.class);

            List candidates = (List) response.getBody().get("candidates");
            Map firstCandidate = (Map) candidates.get(0);
            Map content = (Map) firstCandidate.get("content");
            List parts = (List) content.get("parts");
            Map firstPart = (Map) parts.get(0);

            String text = (String) firstPart.get("text");
            return text.replace("```json", "").replace("```", "").trim();
        } catch (Exception e) {
            return "{\"veredito\": \"Erro na IA\", \"score\": 0}";
        }
    }

    private String buscarItensReais(String doc) {
        try {
            // CHAMADA CORRIGIDA: Apenas 1 argumento
            List<Map<String, Object>> itens = portalClient.buscarItensDeEmpenho(doc);
            return (itens != null) ? itens.toString() : "Sem itens.";
        } catch (Exception e) { return "Erro nos itens."; }
    }

    private String buscarParticipantesReais(String doc) {
        try {
            // CHAMADA CORRIGIDA: Apenas 1 argumento
            List<Map<String, Object>> partes = portalClient.buscarParticipantesLicitacao(doc);
            return (partes != null) ? partes.toString() : "Sem licitantes.";
        } catch (Exception e) { return "Erro na licitação."; }
    }
}