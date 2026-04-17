package tfs.com.govtrace.api.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tfs.com.govtrace.api.models.Despesa;
import tfs.com.govtrace.api.repositories.DespesaRepository;
import tfs.com.govtrace.api.services.AuditoriaService;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Controller principal da Three Frog System (TFS).
 * Agora integrado com Apache Kafka para resiliência.
 */
@RestController
@RequestMapping("/api/auditoria")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AuditoriaController {

    private final DespesaRepository repository;
    private final AuditoriaService auditoriaService;

    /**
     * Envia o pedido de carga para o Kafka.
     * O Kafka vai processar os 12 meses um por um em background.
     */
    @PostMapping("/carga-dados")
    public ResponseEntity<String> carregarDados(
            @RequestParam(defaultValue = "braganca-paulista") String cidade,
            @RequestParam(defaultValue = "2024") int ano) {


        auditoriaService.solicitarCargaAnual(cidade, ano);

        return ResponseEntity.accepted().body("Solicitação de carga anual enviada para a fila do Kafka. " +
                "Cidade: " + cidade + " | Ano: " + ano);
    }

    /**
     * Dispara análise de risco via Groq em background.
     */
    @PostMapping("/disparar-analise")
    public ResponseEntity<String> dispararAnalise() {
        auditoriaService.analisarBaseComIA();
        return ResponseEntity.ok("Processo de auditoria por IA iniciado.");
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
     * Limpa toda a base local.
     */
    @DeleteMapping("/limpar")
    public ResponseEntity<String> limparBase() {
        repository.deleteAll();
        return ResponseEntity.ok("Base de dados limpa com sucesso.");
    }
}