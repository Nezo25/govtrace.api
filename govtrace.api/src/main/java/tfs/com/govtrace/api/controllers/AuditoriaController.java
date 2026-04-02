package tfs.com.govtrace.api.controllers;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tfs.com.govtrace.api.services.GeminiService;
import tfs.com.govtrace.api.models.Despesa;

import java.util.List;

@RestController
@RequestMapping("/api/auditoria")
@RequiredArgsConstructor
public class AuditoriaController {

    private final GeminiService geminiService;

    /**
     * 1. DISPARAR AUDITORIA GERAL

     * URL: GET http://localhost:8080/api/auditoria/processar
     */
    @GetMapping("/processar")
    public ResponseEntity<String> processarAuditoria() {
        // Chama o método que criamos no seu GeminiService
        String resultado = geminiService.analisarBase();
        return ResponseEntity.ok(resultado);
    }
    @PostMapping("/carga-dados")
    public ResponseEntity<String> carga() {
        return ResponseEntity.ok(geminiService.carregarBaseTransparencia());
    }
}