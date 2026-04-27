package tfs.com.govtrace.api.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private String providerAtual = "groq"; // Padrão

    @PostMapping("/trocar-ia")
    public ResponseEntity<String> mudarIA(@RequestParam String provider) {
        this.providerAtual = provider;
        // Aqui você salvaria no banco ou em um ConfigContext
        return ResponseEntity.ok("IA alterada para: " + provider);
    }

    @GetMapping("/ia-ativa")
    public ResponseEntity<String> getIAAtiva() {
        return ResponseEntity.ok(this.providerAtual);
    }
}
