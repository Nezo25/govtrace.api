package tfs.com.govtrace.api.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class McpBrasilClient {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate;
    private final AtomicInteger idCounter = new AtomicInteger(1);

    @Value("${govtrace.mcp.url:http://localhost:8000/mcp}")
    private String mcpUrl;

    private volatile String sessionId = null;
    private final Object sessionLock = new Object();

    public McpBrasilClient(
            @Value("${govtrace.mcp.connect-timeout-ms:15000}") int connectTimeoutMs,
            @Value("${govtrace.mcp.read-timeout-ms:1800000}") int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        this.restTemplate = new RestTemplate(factory);
        log.info("[MCP] Cliente HTTP | connect={}ms | read={}ms (~{} min)",
                connectTimeoutMs, readTimeoutMs, readTimeoutMs / 60_000);
    }

    public void resetSession() {
        synchronized (sessionLock) {
            log.info("[MCP] Resetando sessão...");
            this.sessionId = null;
        }
    }

    public String callTool(String toolName, Map<String, Object> arguments) throws Exception {
        long inicio = System.currentTimeMillis();
        log.info("[MCP] Chamando tool '{}' (aguarde — carga grande pode levar vários minutos)...", toolName);

        ObjectNode toolParams = mapper.createObjectNode();
        toolParams.put("name", toolName);
        toolParams.set("arguments", mapper.valueToTree(arguments));

        JsonNode response = sendRequest("tools/call", toolParams);

        if (response.has("error")) {
            String msg = response.path("error").path("message").asText("erro desconhecido");
            throw new RuntimeException("Erro MCP: " + msg);
        }

        JsonNode content = response.path("result").path("content");
        String texto;
        if (content.isArray() && !content.isEmpty()) {
            texto = content.get(0).path("text").asText();
        } else {
            texto = "[]";
        }

        log.info("[MCP] Tool '{}' OK em {}s | resposta: {} caracteres",
                toolName, (System.currentTimeMillis() - inicio) / 1000, texto.length());
        return texto;
    }

    private void ensureSession() throws Exception {
        if (sessionId != null) return;
        synchronized (sessionLock) {
            if (sessionId != null) return;

            log.info("[MCP] Realizando handshake oficial...");

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

            ObjectNode notifiedReq = mapper.createObjectNode();
            notifiedReq.put("jsonrpc", "2.0");
            notifiedReq.put("method", "notifications/initialized");
            notifiedReq.set("params", mapper.createObjectNode());

            restTemplate.postForEntity(mcpUrl,
                    new HttpEntity<>(mapper.writeValueAsString(notifiedReq), baseHeaders(sid)), String.class);

            this.sessionId = sid;
            log.info("[MCP] Conexão estabelecida! Session: {}", sid);
        }
    }

    private JsonNode sendRequest(String method, ObjectNode params) throws Exception {
        ensureSession();
        ObjectNode req = buildRpc(idCounter.getAndIncrement(), method, params);

        ResponseEntity<String> resp = null;
        JsonNode result = mapper.createObjectNode();
        boolean tryReconect = false;

        try {
            resp = restTemplate.postForEntity(mcpUrl,
                    new HttpEntity<>(mapper.writeValueAsString(req), baseHeaders(sessionId)), String.class);
            result = parseSseOrJson(resp.getBody());
        } catch (org.springframework.web.client.HttpStatusCodeException ex) {
            String errorBody = ex.getResponseBodyAsString();
            if (errorBody.contains("session") || ex.getStatusCode().value() == 404 || ex.getStatusCode().value() == 400) {
                tryReconect = true;
            } else {
                throw new RuntimeException("MCP Error " + ex.getStatusCode() + ": " + errorBody);
            }
        }

        if (tryReconect || result.path("error").path("code").asInt(0) == -32600
                || result.path("error").path("message").asText("").contains("session")) {
            log.warn("[MCP] Sessão expirada ou não encontrada. Reconectando...");
            resetSession();
            ensureSession();
            req = buildRpc(idCounter.getAndIncrement(), method, params);
            resp = restTemplate.postForEntity(mcpUrl,
                    new HttpEntity<>(mapper.writeValueAsString(req), baseHeaders(sessionId)), String.class);
            result = parseSseOrJson(resp.getBody());
        }

        return result;
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
                } catch (Exception ignored) {
                }
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
