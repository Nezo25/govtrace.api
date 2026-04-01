package tfs.com.govtrace.api.controllers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tfs.com.govtrace.api.services.CargaDadosService;
import tfs.com.govtrace.api.services.GeminiService;

@RestController
@RequestMapping("/api/auditoria")
public class AuditoriaController {

    @Autowired
    private GeminiService geminiService;

    // Endpoint principal para análise inicial
    @GetMapping("/investigar")
    public String investigarBase() {
        return geminiService.analisarBase();
    }

    // Para corrigir registros que voltaram com "Erro na IA"
    @GetMapping("/recuperar")
    public String recuperarFalhas() {
        return geminiService.recuperarFalhas();


    }

    @Autowired
    private CargaDadosService cargaService;

    // GET http://localhost:8080/api/auditoria/carregar-base
    @GetMapping("/carregar-base")
    public String carregarBase() {
        // Vamos buscar as primeiras 10 páginas (aprox. 150 registos)
        return cargaService.carregarMassaDados(1);

    }
}