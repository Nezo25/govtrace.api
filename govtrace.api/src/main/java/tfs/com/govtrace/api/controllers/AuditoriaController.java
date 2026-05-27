package tfs.com.govtrace.api.controllers;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tfs.com.govtrace.api.models.Despesa;
import tfs.com.govtrace.api.models.TransporteAuditoria;
import tfs.com.govtrace.api.repositories.DespesaRepository;
import tfs.com.govtrace.api.repositories.EmendaRepository;
import tfs.com.govtrace.api.repositories.TransporteRepository;
import tfs.com.govtrace.api.services.AuditoriaService;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/auditoria")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AuditoriaController {

    private final EmendaRepository emendaRepository;
    private final DespesaRepository despesaRepository;
    private final TransporteRepository transporteRepository;
    private final AuditoriaService auditoriaService;

    // =====================================================
    // 1. ENDPOINTS DE CARGA E INGESTÃO DE DADOS
    // =====================================================

    @PostMapping("/carga-despesas")
    public ResponseEntity<String> carregarDespesas(
            @RequestParam(defaultValue = "braganca-paulista") String cidade,
            @RequestParam(defaultValue = "2024") int anoInicio,
            @RequestParam(required = false) Integer anoFim) {

        int fim = (anoFim != null) ? anoFim : anoInicio;

        if (fim < anoInicio) {
            return ResponseEntity.badRequest().body("anoFim não pode ser menor que anoInicio.");
        }

        auditoriaService.solicitarCarga(cidade, anoInicio, fim);
        return ResponseEntity.accepted().body("Carga de despesas municipais enfileirada com sucesso via Kafka.");
    }

    @PostMapping("/upload-transporte")
    public ResponseEntity<String> uploadTransporte(@RequestParam("arquivo") MultipartFile file) {
        if (file.isEmpty()) return ResponseEntity.badRequest().body("Arquivo CSV vazio.");
        try {
            CsvMapper mapper = new CsvMapper();
            mapper.registerModule(new JavaTimeModule());
            CsvSchema schema = CsvSchema.emptySchema().withHeader();
            MappingIterator<TransporteAuditoria> it = mapper.readerFor(TransporteAuditoria.class)
                    .with(schema).readValues(file.getInputStream());
            List<TransporteAuditoria> dados = it.readAll();
            transporteRepository.saveAll(dados);
            return ResponseEntity.ok("Sucesso! " + dados.size() + " registros de transporte importados.");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Erro ao processar CSV: " + e.getMessage());
        }
    }

    // =====================================================
    // 2. ENDPOINTS DE PROCESSAMENTO E CRITICIDADE
    // =====================================================

    @PostMapping("/cruzar-emendas")
    public ResponseEntity<String> dispararCruzamento() {
        CompletableFuture.runAsync(auditoriaService::realizarCruzamentoEmendas);
        return ResponseEntity.accepted().body("Algoritmo de batimento de nexo causal iniciado em segundo plano.");
    }

    @PostMapping("/disparar-analise")
    public ResponseEntity<String> dispararAnalise() {
        CompletableFuture.runAsync(auditoriaService::analisarBaseComIA);
        return ResponseEntity.accepted().body("Auditoria complementar via IA disparada em segundo plano.");
    }

    // =====================================================
    // 3. ENDPOINTS DE LEITURA (DASHBOARD E CONSULTAS)
    // =====================================================

    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> getDashboard() {
        return ResponseEntity.ok(auditoriaService.obterEstatisticasDashboard());
    }

    @GetMapping("/ranking-risco")
    public ResponseEntity<List<Despesa>> getRankingRisco() {
        return ResponseEntity.ok(despesaRepository.findAllByOrderByScoreRiscoDesc());
    }

    @GetMapping("/pendentes")
    public ResponseEntity<List<Despesa>> getPendentes() {
        return ResponseEntity.ok(despesaRepository.findPendentesDeAuditoria());
    }

    @GetMapping("/casos-criticos")
    public ResponseEntity<List<Despesa>> getCasosCriticos(@RequestParam(defaultValue = "80") int threshold) {
        return ResponseEntity.ok(despesaRepository.findCasosCriticos(threshold));
    }

    // =====================================================
    // 4. UTILITÁRIOS (RESET E EXPORTAÇÃO)
    // =====================================================

    @DeleteMapping("/limpar")
    @Transactional
    public ResponseEntity<String> limparBase() {
        try {
            despesaRepository.deleteAll();
            emendaRepository.deleteAll();
            transporteRepository.deleteAll();
            return ResponseEntity.ok("Base de dados totalmente limpa e resetada para testes.");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Erro ao resetar tabelas: " + e.getMessage());
        }
    }

    @GetMapping("/exportar-auditoria")
    public void exportarRelatorio(HttpServletResponse response) throws IOException {
        response.setContentType("text/csv");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=auditoria_govtrace.csv");

        PrintWriter writer = response.getWriter();
        writer.println("Documento;Favorecido;Valor;Categoria;Risco;Veredito_IA");

        despesaRepository.findAllByOrderByScoreRiscoDesc().forEach(d -> {
            String nome = d.getNomeFavorecido() != null ? d.getNomeFavorecido() : "";
            String nomeMascarado = nome.length() > 20 ? nome.substring(0, 17) + "..." : nome;
            String cat = d.getCategoria() != null ? d.getCategoria() : "N/A";
            String veredito = d.getVereditoIA() != null ? d.getVereditoIA().replace("\n", " ") : "Sem análise";

            writer.println(String.format("%s;%s;%s;%s;%s;%s",
                    d.getDocumentoOrigem(), nomeMascarado, d.getValorPago(),
                    cat, d.getScoreRisco() != null ? d.getScoreRisco() : "0", veredito));
        });
    }
}