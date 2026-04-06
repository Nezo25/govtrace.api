package tfs.com.govtrace.api.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class McpBrasilClient {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();
    private final AtomicInteger idCounter = new AtomicInteger(1);

    @Value("${govtrace.mcp.url:http://localhost:8000/mcp}")
    private String mcpUrl;

    private volatile String sessionId = null;
    private final Object sessionLock = new Object();

    public String callTool(String toolName, Map<String, Object> arguments) throws Exception {
        ObjectNode toolParams = mapper.createObjectNode();
        toolParams.put("name", toolName);
        toolParams.set("arguments", mapper.valueToTree(arguments));

        JsonNode response = sendRequest("tools/call", toolParams);

        if (response.has("error")) {
            throw new RuntimeException("Erro MCP: " + response.path("error").path("message").asText());
        }

        JsonNode content = response.path("result").path("content");
        if (content.isArray() && !content.isEmpty()) {
            return content.get(0).path("text").asText();
        }
        return "[]";
    }

    private void ensureSession() throws Exception {
        if (sessionId != null) return;
        synchronized (sessionLock) {
            if (sessionId != null) return;

            log.info("[MCP] Realizando handshake oficial...");

            // 1. INITIALIZE (Incluindo capabilities vazias porém presentes)
            ObjectNode initParams = mapper.createObjectNode();
            initParams.put("protocolVersion", "2024-11-05");
            initParams.set("capabilities", mapper.createObjectNode());
            initParams.set("clientInfo", mapper.createObjectNode()
                    .put("name", "govtrace-api")
                    .put("version", "1.0.0"));

            ObjectNode initReq = buildRpc(idCounter.getAndIncrement(), "initialize", initParams);

            ResponseEntity<String> resp = restTemplate.postForEntity(mcpUrl,
                    new HttpEntity<>(mapper.writeValueAsString(initReq), baseHeaders(null)), String.class);

            String sid = resp.getHeaders().getFirst("mcp-session-id");
            if (sid == null) throw new RuntimeException("Handshake falhou: sem mcp-session-id");

            // 2. NOTIFICATIONS/INITIALIZED (Obrigatório para o Python liberar o acesso)
            ObjectNode notifiedReq = mapper.createObjectNode();
            notifiedReq.put("jsonrpc", "2.0");
            notifiedReq.put("method", "notifications/initialized");
            notifiedReq.set("params", mapper.createObjectNode()); // Params vazio, mas presente

            restTemplate.postForEntity(mcpUrl,
                    new HttpEntity<>(mapper.writeValueAsString(notifiedReq), baseHeaders(sid)), String.class);

            this.sessionId = sid;
            log.info("[MCP] Conexão estabelecida com sucesso!");
        }
    }

    private JsonNode sendRequest(String method, ObjectNode params) throws Exception {
        ensureSession();
        ObjectNode req = buildRpc(idCounter.getAndIncrement(), method, params);

        ResponseEntity<String> resp = restTemplate.postForEntity(mcpUrl,
                new HttpEntity<>(mapper.writeValueAsString(req), baseHeaders(sessionId)), String.class);

        return parseSseOrJson(resp.getBody());
    }

    private JsonNode parseSseOrJson(String raw) throws Exception {
        if (raw == null || raw.isBlank()) return mapper.createObjectNode();
        JsonNode lastNode = null;
        for (String line : raw.split("\\r?\\n")) {
            String jsonPart = line.startsWith("data:") ? line.substring(5).trim() : line.trim();
            if (jsonPart.startsWith("{")) {
                try {
                    JsonNode n = mapper.readTree(jsonPart);
                    if (n.has("id") || n.has("result")) lastNode = n;
                } catch (Exception ignored) {}
            }
        }
        return lastNode != null ? lastNode : mapper.readTree(raw);
    }

    private ObjectNode buildRpc(int id, String method, ObjectNode params) {
        ObjectNode r = mapper.createObjectNode();
        r.put("jsonrpc", "2.0");
        r.put("id", id);
        r.put("method", method);
        r.set("params", params != null ? params : mapper.createObjectNode());
        return r;
    }

    private HttpHeaders baseHeaders(String sid) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set(HttpHeaders.ACCEPT, "application/json, text/event-stream");
        if (sid != null) h.set("mcp-session-id", sid);
        return h;
    }
}