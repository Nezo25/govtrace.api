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

/**
 * Cliente MCP via Streamable HTTP transport (FastMCP 3.x).
 *
 * O protocolo Streamable HTTP exige:
 *   1. POST /mcp com initialize → recebe mcp-session-id no header de resposta
 *   2. POST /mcp com notifications/initialized (mesmo session-id)
 *   3. POST /mcp com tools/call (mesmo session-id)
 *
 * Servidor: python -c "from mcp_brasil.server import mcp; mcp.run(transport='http', port=8000)"
 * Endpoint: http://localhost:8000/mcp
 */
@Slf4j
@Component
public class McpBrasilClient {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();
    private final AtomicInteger idCounter = new AtomicInteger(1);

    @Value("${govtrace.mcp.url:http://localhost:8000/mcp}")
    private String mcpUrl;

    // Session ID negociado no initialize — reutilizado em todas as chamadas
    private volatile String sessionId = null;
    private final Object sessionLock = new Object();

    // -------------------------------------------------------------------------
    // API pública
    // -------------------------------------------------------------------------

    public String callTool(String toolName, Map<String, Object> arguments) throws Exception {
        ensureSession();

        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", mapper.valueToTree(arguments));

        JsonNode response = sendRequest("tools/call", params, sessionId);

        if (response.has("error")) {
            String msg = response.path("error").path("message").asText("Erro desconhecido");
            throw new RuntimeException("[MCP] Erro da ferramenta '" + toolName + "': " + msg);
        }

        JsonNode content = response.path("result").path("content");
        if (content.isArray() && !content.isEmpty()) {
            String text = content.get(0).path("text").asText();
            log.info("[MCP] '{}' retornou {} caracteres.", toolName, text.length());
            return text;
        }

        log.warn("[MCP] Formato inesperado de '{}': {}", toolName, response);
        return "{}";
    }

    // -------------------------------------------------------------------------
    // Handshake: initialize → notifications/initialized
    // -------------------------------------------------------------------------

    private void ensureSession() throws Exception {
        if (sessionId != null) return;

        synchronized (sessionLock) {
            if (sessionId != null) return;

            log.info("[MCP] Iniciando sessão HTTP com {}...", mcpUrl);

            // ── 1. initialize ──────────────────────────────────────────────
            int initId = idCounter.getAndIncrement();

            ObjectNode clientInfo = mapper.createObjectNode();
            clientInfo.put("name", "govtrace-api");
            clientInfo.put("version", "1.0.0");

            ObjectNode initParams = mapper.createObjectNode();
            initParams.put("protocolVersion", "2024-11-05");
            initParams.set("clientInfo", clientInfo);
            initParams.set("capabilities", mapper.createObjectNode());

            ObjectNode initRequest = buildRequest(initId, "initialize", initParams);

            // No initialize NÃO enviamos session-id — o servidor vai criar e devolver
            HttpHeaders initHeaders = baseHeaders(null);
            HttpEntity<String> initEntity = new HttpEntity<>(
                    mapper.writeValueAsString(initRequest), initHeaders);

            ResponseEntity<String> initResponse;
            try {
                initResponse = restTemplate.postForEntity(mcpUrl, initEntity, String.class);
            } catch (Exception e) {
                throw new RuntimeException(
                        "[MCP] Falha ao conectar em " + mcpUrl +
                                ". Servidor rodando? (python -c \"from mcp_brasil.server import mcp; mcp.run(transport='http', port=8000)\")", e);
            }

            if (!initResponse.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("[MCP] initialize retornou HTTP " + initResponse.getStatusCode()
                        + " | body: " + initResponse.getBody());
            }

            // Extrai o session-id do header de resposta
            String sid = initResponse.getHeaders().getFirst("mcp-session-id");
            if (sid == null || sid.isBlank()) {
                throw new RuntimeException("[MCP] Servidor não retornou mcp-session-id no initialize.");
            }

            log.info("[MCP] Sessão criada: {}", sid);

            // ── 2. notifications/initialized ──────────────────────────────
            ObjectNode notif = mapper.createObjectNode();
            notif.put("jsonrpc", "2.0");
            notif.put("method", "notifications/initialized");

            HttpHeaders notifHeaders = baseHeaders(sid);
            HttpEntity<String> notifEntity = new HttpEntity<>(
                    mapper.writeValueAsString(notif), notifHeaders);

            // Notificações podem retornar 202 Accepted ou 200 — ambos OK
            try {
                restTemplate.postForEntity(mcpUrl, notifEntity, String.class);
            } catch (Exception e) {
                log.warn("[MCP] notifications/initialized retornou erro (não crítico): {}", e.getMessage());
            }

            sessionId = sid;
            log.info("[MCP] Sessão pronta.");
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private JsonNode sendRequest(String method, ObjectNode params, String sid) throws Exception {
        int id = idCounter.getAndIncrement();
        ObjectNode request = buildRequest(id, method, params);

        HttpHeaders headers = baseHeaders(sid);
        HttpEntity<String> entity = new HttpEntity<>(mapper.writeValueAsString(request), headers);

        log.debug("[MCP] >>> {} (id={})", method, id);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(mcpUrl, entity, String.class);

            String body = response.getBody() != null ? response.getBody().trim() : "";

            // Limpeza de SSE (event/data)
            if (body.contains("data:")) {
                body = body.substring(body.lastIndexOf("data:") + 5).trim();
            }
            if (body.contains("\n")) {
                for (String line : body.split("\n")) {
                    if (line.trim().startsWith("{")) {
                        body = line.trim();
                        break;
                    }
                }
            }

            return mapper.readTree(body);

        } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
            // --- AQUI ESTÁ O PULO DO GATO ---
            log.warn("[MCP] Sessão {} não encontrada no servidor. Resetando...", sid);
            synchronized (sessionLock) {
                this.sessionId = null; // Força criar nova sessão na próxima chamada
            }
            throw new RuntimeException("Sessão expirada. Por favor, tente a requisição novamente.");
        } catch (Exception e) {
            log.error("[MCP] Erro na comunicação: {}", e.getMessage());
            throw e;
        }
    }

    private ObjectNode buildRequest(int id, String method, ObjectNode params) {
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        req.set("params", params);
        return req;
    }

    private HttpHeaders baseHeaders(String sid) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("Accept", "application/json, text/event-stream");
        if (sid != null && !sid.isBlank()) {
            h.set("mcp-session-id", sid);
        }
        return h;
    }
}