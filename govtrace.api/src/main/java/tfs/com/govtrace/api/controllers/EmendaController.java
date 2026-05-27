package tfs.com.govtrace.api.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tfs.com.govtrace.api.models.Emenda;
import tfs.com.govtrace.api.repositories.EmendaRepository;
import tfs.com.govtrace.api.services.EmendaService;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Controller do módulo de Emendas Parlamentares.
 *
 * ENDPOINTS:
 * POST /api/emendas/carregar-cgu?municipio=braganca-paulista&ano=2024
 * → Carrega todas as emendas do Portal da Transparência (CGU) para a cidade
 *
 * POST /api/emendas/verificar-inidoneos
 * → Cruza favorecidos com lista negra do TCU
 *
 * GET  /api/emendas/ranking
 * → Lista emendas ordenadas por valor
 *
 * GET  /api/emendas/inidoneos
 * → Lista emendas com favorecido inidôneo (Alerta TCU)
 *
 * GET  /api/emendas/por-funcao?funcao=SAÚDE
 * → Filtra por área (SAÚDE, EDUCAÇÃO, TRANSPORTE...)
 *
 * GET  /api/emendas/dashboard
 * → Contagem por função para o front
 */
@RestController
@RequestMapping("/api/emendas")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class EmendaController {

    private final EmendaService emendaService;
    private final EmendaRepository emendaRepository;

    /**
     * Carrega emendas reais repassadas ao município via Portal da Transparência (CGU).
     * Exemplo: POST /api/emendas/carregar-cgu?municipio=braganca-paulista&ano=2024
     */
    @PostMapping("/carregar-cgu")
    public ResponseEntity<String> carregarCGU(
            @RequestParam(defaultValue = "braganca-paulista") String municipio,
            @RequestParam(defaultValue = "2024") int ano,
            @RequestParam(defaultValue = "ESTADO") String escopo) {

        CompletableFuture.runAsync(() -> emendaService.carregarEmendasMunicipio(municipio, ano, escopo));
        return ResponseEntity.accepted().body(String.format(
                "Carga CGU: %s / %d | escopo=%s (ESTADO=SP+Bragança até 1200; MUNICIPIO=só Bragança).",
                municipio, ano, escopo.toUpperCase()));
    }

    /**
     * Verifica inidôneos do TCU contra os autores/favorecidos das emendas.
     */
    @PostMapping("/verificar-inidoneos")
    public ResponseEntity<String> verificarInidoneos() {
        CompletableFuture.runAsync(() -> emendaService.verificarInidôneosTCU());
        return ResponseEntity.accepted().body("Verificação de inidôneos do TCU iniciada em background.");
    }

    /**
     * Lista todas as emendas (Ideal para a tabela principal do front-end).
     */
    @GetMapping("/ranking")
    public ResponseEntity<List<Emenda>> getRanking() {
        return ResponseEntity.ok(emendaRepository.findAll());
    }

    /**
     * Lista emendas marcadas com alerta do TCU.
     */
    @GetMapping("/inidoneos")
    public ResponseEntity<List<Emenda>> getInidoneos() {
        return ResponseEntity.ok(emendaRepository.findByFavorecidoInidoneoTrue());
    }

    /**
     * Filtra emendas por função orçamentária.
     */
    @GetMapping("/por-funcao")
    public ResponseEntity<List<Emenda>> getPorFuncao(@RequestParam String funcao) {
        return ResponseEntity.ok(emendaRepository.findByFuncaoIgnoreCase(funcao));
    }

    /**
     * Dashboard: contagem de emendas por função para gráficos.
     */
    @GetMapping("/dashboard")
    public ResponseEntity<List<Object[]>> getDashboard() {
        return ResponseEntity.ok(emendaRepository.contarPorFuncao());
    }
    @GetMapping("/diagnostico")
    public ResponseEntity<String> rodarDiagnostico() throws Exception {
        emendaService.diagnosticarTool();
        return ResponseEntity.ok("Diagnóstico rodado! Olhe o console do IntelliJ.");
    }
}