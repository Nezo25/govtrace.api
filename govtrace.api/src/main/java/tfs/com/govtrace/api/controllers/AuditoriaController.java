package tfs.com.govtrace.api.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tfs.com.govtrace.api.models.Despesa;
import tfs.com.govtrace.api.repositories.DespesaRepository;
import tfs.com.govtrace.api.service.AuditoriaService; // Novo Service

import java.util.List;
import java.util.stream.Collectors;

/**
 * Controller evoluído para Java 21 + Virtual Threads.
 * Cérebro da auditoria do GovTrace rodando na Groq.
 */
@RestController
@RequestMapping("/api/auditoria")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AuditoriaController {

    private final DespesaRepository repository;
    private final AuditoriaService auditoriaService; // Substituiu o GeminiService

    /**
     * 1. CARGA DE DADOS
     * Mantemos a lógica de carga, mas agora integrada à nova infra.
     */
    @PostMapping("/carga-dados")
    public ResponseEntity<String> carregarDados() {
        auditoriaService.carregarBaseTransparenciaAsync();
        return ResponseEntity.accepted().body("Carga iniciada via Virtual Threads. Aguarde.");
    }

    /**
     * 2. DISPARAR ANÁLISE DE RISCO
     * Aqui é onde a Groq e as Virtual Threads brilham.
     */
    @PostMapping("/disparar-analise")
    public ResponseEntity<String> dispararAnalise() {
        // O método no service agora processa em paralelo de forma eficiente
        auditoriaService.analisarBaseComIA();
        return ResponseEntity.accepted()
                .body("Auditoria iniciada na Groq. Alta performance via Llama 3.3 70B.");
    }

    /**
     * 3. RANKING DE RISCO COMPLETO
     */
    @GetMapping("/ranking-risco")
    public ResponseEntity<List<Despesa>> getRankingRisco() {
        return ResponseEntity.ok(repository.findAllByOrderByScoreRiscoDesc());
    }

    /**
     * 4. CASOS CRÍTICOS (Top 10 com Score > 80)
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
     * 5. LIMPAR BASE
     */
    @DeleteMapping("/limpar")
    public ResponseEntity<String> limparBase() {
        repository.deleteAll();
        return ResponseEntity.ok("Base de despesas limpa com sucesso.");
    }
}