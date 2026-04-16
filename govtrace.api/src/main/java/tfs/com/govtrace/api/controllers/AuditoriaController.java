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
     * Controller principal da auditoria do GovTrace - Three Frog System (TFS).
     */
    @RestController
    @RequestMapping("/api/auditoria")
    @RequiredArgsConstructor
    @CrossOrigin(origins = "*")
    public class AuditoriaController {

        private final DespesaRepository repository;
        private final AuditoriaService auditoriaService;

        /**
         * Carrega despesas  do TCE-SP de forma assíncrona para o ano todo.
         * Retorna 202 Accepted para indicar processamento em background.
         */
        @PostMapping("/carga-dados")
        public ResponseEntity<String> carregarDados(
                @RequestParam(defaultValue = "braganca-paulista") String cidade,
                @RequestParam(defaultValue = "2024") int ano) {

            auditoriaService.carregarBaseTransparenciaAnual(cidade, ano);

            return ResponseEntity.accepted().body("Solicitação de carga anual para " + cidade +
                    " iniciada. O processamento seguirá em background para contornar limites de API.");
        }

        /**
         * Dispara análise de risco via Groq (Llama 3.3 70B) em background.
         */
        @PostMapping("/disparar-analise")
        public ResponseEntity<String> dispararAnalise() {
            auditoriaService.analisarBaseComIA();
            return ResponseEntity.ok("Processo de auditoria por IA iniciado. Verifique o ranking em alguns instantes.");
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
            return ResponseEntity.ok("Base de dados limpa.");
        }
    }