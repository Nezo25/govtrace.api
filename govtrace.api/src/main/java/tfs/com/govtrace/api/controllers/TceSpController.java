package tfs.com.govtrace.api.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tfs.com.govtrace.api.services.McpBrasilClient;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/tce")
@CrossOrigin(origins = "*")
public class TceSpController {

    private final McpBrasilClient mcpClient;

    public TceSpController(McpBrasilClient mcpClient) {
        this.mcpClient = mcpClient;
    }

    @GetMapping("/despesas")
    public ResponseEntity<String> getDespesas(
            @RequestParam String municipio,
            @RequestParam int exercicio,
            @RequestParam int mes,
            @RequestParam(defaultValue = "1") int pagina) throws Exception {

        Map<String, Object> args = new HashMap<>();
        args.put("municipio", municipio);
        args.put("exercicio", exercicio);
        args.put("mes", mes);
        args.put("pagina", pagina);

        return ResponseEntity.ok(mcpClient.callTool("tce_sp_consultar_despesas_sp", args));
    }

    @GetMapping("/receitas")
    public ResponseEntity<String> getReceitas(
            @RequestParam String municipio,
            @RequestParam int exercicio,
            @RequestParam int mes) throws Exception {

        Map<String, Object> args = new HashMap<>();
        args.put("municipio", municipio);
        args.put("exercicio", exercicio);
        args.put("mes", mes);

        return ResponseEntity.ok(mcpClient.callTool("tce_sp_consultar_receitas_sp", args));
    }
}