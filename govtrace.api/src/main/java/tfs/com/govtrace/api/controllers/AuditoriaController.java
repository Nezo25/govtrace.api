package tfs.com.govtrace.api.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tfs.com.govtrace.api.models.Despesa;
import tfs.com.govtrace.api.repositories.DespesaRepository;
import tfs.com.govtrace.api.services.AuditoriaService;
import tfs.com.govtrace.api.services.McpBrasilClient;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Controller principal da auditoria do GovTrace.
 *
 * Endpoints:
 *   POST /api/auditoria/carga-dados?cidade=braganca-paulista&ano=2024&mes=1
 *   POST /api/auditoria/disparar-analise
 *   GET  /api/auditoria/ranking-risco
 *   GET  /api/auditoria/casos-criticos
 *   GET  /api/auditoria/pendentes
 *   DELETE /api/auditoria/limpar
 */
@RestController
@RequestMapping("/api/auditoria")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AuditoriaController {

    private final DespesaRepository repository;
    private final AuditoriaService auditoriaService;
    private final McpBrasilClient mcpClient;

    /**
     * Carrega despesas reais do TCE-SP via mcp-brasil.
     * Parâmetros com valores padrão para facilitar testes no Swagger.
     */
    @PostMapping("/carga-dados")
    public ResponseEntity<String> carregarDados(
            @RequestParam(defaultValue = "braganca-paulista") String cidade,
            @RequestParam(defaultValue = "2024") int ano,
            @RequestParam(defaultValue = "1") int mes) {

        auditoriaService.carregarBaseTransparenciaAsync(cidade, ano, mes);
        return ResponseEntity.ok("Carga concluída para " + cidade + " — " + mes + "/" + ano +
                ". Total no banco: " + repository.count());
    }

    /**
     * Dispara análise de risco via Groq para despesas pendentes (até 10 por vez).
     */
    @PostMapping("/disparar-analise")
    public ResponseEntity<String> dispararAnalise() {
        auditoriaService.analisarBaseComIA();
        return ResponseEntity.ok("Análise concluída. Veja os resultados em /ranking-risco.");
    }

    /**
     * Ranking completo de despesas por score de risco (maior → menor).
     */
    @GetMapping("/ranking-risco")
    public ResponseEntity<List<Despesa>> getRankingRisco() {
        return ResponseEntity.ok(repository.findAllByOrderByScoreRiscoDesc());
    }

    /**
     * Top 10 casos mais críticos (score > 80).
     */
    @GetMapping("/casos-criticos")
    public ResponseEntity<List<Despesa>> getCasosCriticos() {
        List<Despesa> criticos = repository.findCasosCriticos(80)
                .stream()
                .limit(10)
                .collect(Collectors.toList());
        return ResponseEntity.ok(criticos);
    }

    /**
     * Despesas ainda não analisadas pela IA.
     */
    @GetMapping("/pendentes")
    public ResponseEntity<List<Despesa>> getPendentes() {
        return ResponseEntity.ok(repository.findPendentesDeAuditoria());
    }

    /**
     * Limpa toda a base. Use com cautela.
     */
    @DeleteMapping("/limpar")
    public ResponseEntity<String> limparBase() {
        repository.deleteAll();
        return ResponseEntity.ok("Base limpa.");
    }

    @GetMapping("/debug/tools")
    public ResponseEntity<String> listTools() throws Exception {
        return ResponseEntity.ok(mcpClient.listTools());
    }
    @GetMapping("/debug/schema-tce")
    public ResponseEntity<String> schemaTce() throws Exception {
        String result = mcpClient.callTool("search_tools",
                Map.of("query", "tce_sp consultar despesas municipio"));
        return ResponseEntity.ok(result);
    }
}