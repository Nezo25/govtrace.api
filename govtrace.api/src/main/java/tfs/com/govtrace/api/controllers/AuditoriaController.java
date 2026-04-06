package tfs.com.govtrace.api.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tfs.com.govtrace.api.models.Despesa;
import tfs.com.govtrace.api.repositories.DespesaRepository;
import tfs.com.govtrace.api.services.GeminiService;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Controller responsável por gerenciar o ciclo de vida da auditoria:
 * Carga de dados, Disparo de análise e Visualização de riscos.
 */
@RestController
@RequestMapping("/api/auditoria")
@RequiredArgsConstructor
@CrossOrigin(origins = "*") // Permite integração com Frontend
public class AuditoriaController {

    private final DespesaRepository repository;
    private final GeminiService geminiService;

    /**
     * 1. CARGA DE DADOS (TCE-SP -> IA -> Banco)
     * URL: POST http://localhost:8080/api/auditoria/carga-dados
     */
    @PostMapping("/carga-dados")
    public ResponseEntity<String> carregarDados() {
        geminiService.carregarBaseTransparenciaAsync(); // dispara em background
        return ResponseEntity.accepted().body("Carga iniciada em background. Aguarde alguns minutos.");
    }

    /**
     * 2. DISPARAR ANÁLISE DE RISCO (Background via IA)
     * URL: POST http://localhost:8080/api/auditoria/disparar-analise
     */
    @PostMapping("/disparar-analise")
    public ResponseEntity<String> dispararAnalise() {
        // Como o método no Service é @Async, ele retorna imediatamente
        geminiService.analisarBase();
        return ResponseEntity.accepted()
                .body("Auditoria iniciada em segundo plano. Monitore os logs e o Ranking de Risco.");
    }

    /**
     * 3. RANKING DE RISCO COMPLETO
     * URL: GET http://localhost:8080/api/auditoria/ranking-risco
     */
    @GetMapping("/ranking-risco")
    public ResponseEntity<List<Despesa>> getRankingRisco() {
        return ResponseEntity.ok(repository.findAllByOrderByScoreRiscoDesc());
    }

    /**
     * 4. CASOS CRÍTICOS (Top 10 com Score > 80)
     * URL: GET http://localhost:8080/api/auditoria/casos-criticos
     */
    @GetMapping("/casos-criticos")
    public ResponseEntity<List<Despesa>> getCasosCriticos() {
        List<Despesa> criticas = repository.findAllByOrderByScoreRiscoDesc()
                .stream()
                .filter(d -> d.getScoreRisco() != null && d.getScoreRisco() > 80)
                .limit(10)
                .collect(Collectors.toList());

        return ResponseEntity.ok(criticas);
    }

    /**
     * 5. LIMPAR BASE (Opcional - Útil para testes do TCC)
     * URL: DELETE http://localhost:8080/api/auditoria/limpar
     */
    @DeleteMapping("/limpar")
    public ResponseEntity<String> limparBase() {
        repository.deleteAll();
        return ResponseEntity.ok("Base de despesas limpa com sucesso.");
    }
}