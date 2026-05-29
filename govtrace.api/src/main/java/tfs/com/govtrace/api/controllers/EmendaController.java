package tfs.com.govtrace.api.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tfs.com.govtrace.api.models.Emenda;
import tfs.com.govtrace.api.repositories.EmendaRepository;
import tfs.com.govtrace.api.services.EmendaService;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/emendas")
@CrossOrigin(origins = "*")
public class EmendaController {

    private final EmendaService emendaService;
    private final EmendaRepository emendaRepository;

    // Construtor explícito nativo - Blinda contra erros do Lombok
    public EmendaController(EmendaService emendaService, EmendaRepository emendaRepository) {
        this.emendaService = emendaService;
        this.emendaRepository = emendaRepository;
    }

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

    @PostMapping("/verificar-inidoneos")
    public ResponseEntity<String> verificarInidoneos() {
        CompletableFuture.runAsync(() -> emendaService.verificarInidôneosTCU());
        return ResponseEntity.accepted().body("Verificação de inidôneos do TCU iniciada em background.");
    }

    @GetMapping("/ranking")
    public ResponseEntity<List<Emenda>> getRanking() {
        return ResponseEntity.ok(emendaRepository.findAll());
    }

    @GetMapping("/inidoneos")
    public ResponseEntity<List<Emenda>> getInidoneos() {
        return ResponseEntity.ok(emendaRepository.findByFavorecidoInidoneoTrue());
    }

    @GetMapping("/por-funcao")
    public ResponseEntity<List<Emenda>> getPorFuncao(@RequestParam String funcao) {
        return ResponseEntity.ok(emendaRepository.findByFuncaoIgnoreCase(funcao));
    }

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