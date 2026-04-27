package tfs.com.govtrace.api.controllers;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/auditoria")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AuditoriaController {

    private final EmendaRepository emendaRepository;
    private final DespesaRepository despesaRepository;
    private final TransporteRepository transporteRepository;
    private final AuditoriaService auditoriaService;

    /**
     * CARGA DE DADOS — flexível por cidade + intervalo de anos.
     *
     * Exemplos:
     *   POST /api/auditoria/carga-dados?cidade=braganca-paulista&anoInicio=2024&anoFim=2024
     *   POST /api/auditoria/carga-dados?cidade=campinas&anoInicio=2022&anoFim=2024
     *
     * Se anoFim não for informado, usa o mesmo valor de anoInicio (só um ano).
     */
    @PostMapping("/carga-dados")
    public ResponseEntity<String> carregarDados(
            @RequestParam(defaultValue = "braganca-paulista") String cidade,
            @RequestParam(defaultValue = "2024") int anoInicio,
            @RequestParam(required = false) Integer anoFim) {

        int fim = (anoFim != null) ? anoFim : anoInicio;

        if (fim < anoInicio) {
            return ResponseEntity.badRequest()
                    .body("anoFim não pode ser menor que anoInicio.");
        }

        int totalMeses = (fim - anoInicio + 1) * 12;
        auditoriaService.solicitarCarga(cidade, anoInicio, fim);

        return ResponseEntity.accepted().body(String.format(
                "Carga iniciada para %s | %d → %d | %d mensagens enfileiradas.",
                cidade, anoInicio, fim, totalMeses));
    }

    @PostMapping("/upload-transporte")
    public ResponseEntity<String> uploadTransporte(@RequestParam("arquivo") MultipartFile file) {
        if (file.isEmpty()) return ResponseEntity.badRequest().body("Arquivo vazio.");
        try {
            CsvMapper mapper = new CsvMapper();
            mapper.registerModule(new JavaTimeModule());
            CsvSchema schema = CsvSchema.emptySchema().withHeader();
            MappingIterator<TransporteAuditoria> it = mapper.readerFor(TransporteAuditoria.class)
                    .with(schema).readValues(file.getInputStream());
            List<TransporteAuditoria> dados = it.readAll();
            transporteRepository.saveAll(dados);
            return ResponseEntity.ok("Sucesso! " + dados.size() + " registros importados.");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Erro: " + e.getMessage());
        }
    }

    @PostMapping("/disparar-analise")
    public ResponseEntity<String> dispararAnalise() {
        auditoriaService.analisarBaseComIA();
        return ResponseEntity.ok("Análise por IA iniciada.");
    }

    @GetMapping("/ranking-risco")
    public ResponseEntity<List<Despesa>> getRankingRisco() {
        return ResponseEntity.ok(despesaRepository.findAllByOrderByScoreRiscoDesc());
    }

    @GetMapping("/pendentes")
    public ResponseEntity<List<Despesa>> getPendentes() {
        return ResponseEntity.ok(despesaRepository.findPendentesDeAuditoria());
    }

    @GetMapping("/dashboard")
    public ResponseEntity<Map<String, Object>> getDashboard() {
        return ResponseEntity.ok(auditoriaService.obterEstatisticasDashboard());
    }

    @DeleteMapping("/limpar")
    @Transactional
    public ResponseEntity<String> limparBase() {
        try {
            despesaRepository.deleteAll();
            emendaRepository.deleteAll();
            transporteRepository.deleteAll();
            return ResponseEntity.ok("Base resetada com sucesso.");
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("Erro: " + e.getMessage());
        }
    }

    @GetMapping("/exportar-auditoria")
    public void exportarRelatorio(HttpServletResponse response) throws IOException {
        response.setContentType("text/csv");
        response.setHeader("Content-Disposition", "attachment; filename=auditoria_govtrace.csv");
        PrintWriter writer = response.getWriter();
        writer.println("Documento;Favorecido;Valor;Risco;Veredito_IA");
        despesaRepository.findAllByOrderByScoreRiscoDesc().forEach(d -> {
            String nome = d.getNomeFavorecido() != null ? d.getNomeFavorecido() : "";
            String nomeMascarado = nome.length() > 15 ? nome.substring(0, 12) + "..." : nome;
            writer.println(String.format("%s;%s;%s;%s;%s",
                    d.getDocumentoOrigem(), nomeMascarado, d.getValorPago(),
                    d.getScoreRisco(), d.getVereditoIA()));
        });
    }
}