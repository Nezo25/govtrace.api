package tfs.com.govtrace.api.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tfs.com.govtrace.api.models.Despesa;
import tfs.com.govtrace.api.models.Emenda;
import tfs.com.govtrace.api.repositories.DespesaRepository;
import tfs.com.govtrace.api.repositories.EmendaRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class AuditoriaService {

    private final IAuditorAgente auditorIA;
    private final DespesaRepository repository;
    private final EmendaRepository emendaRepository;
    private final McpBrasilClient mcpClient;
    private final KafkaTemplate<String, String> kafkaTemplate;

    // =====================================================
    // 1. CARGA DE DADOS (KAFKA PRODUCER)
    // =====================================================
    public void solicitarCarga(String municipio, int anoInicio, int anoFim) {
        int totalMensagens = 0;
        for (int ano = anoInicio; ano <= anoFim; ano++) {
            for (int mes = 1; mes <= 12; mes++) {
                // HACK: Enviamos 5 chamadas por mês com offsets para tentar burlar o limite de 30
                for (int p = 1; p <= 5; p++) {
                    String payload = String.format("%s;%d;%d;%d", municipio, ano, mes, p);
                    kafkaTemplate.send("fila-carga-tce", payload);
                    totalMensagens++;
                }
            }
        }
        log.info("[Kafka] Carga enfileirada: {} mensagens para {} | {} a {}",
                totalMensagens, municipio, anoInicio, anoFim);
    }

    // =====================================================
    // 2. PROCESSAMENTO (KAFKA CONSUMER)
    // =====================================================
    @KafkaListener(topics = "fila-carga-tce", groupId = "govtrace-carga-v1")
    public void processarMensagemCarga(String payload) {
        String[] dados = payload.split(";");
        if (dados.length < 4) return;

        String municipio = dados[0];
        int ano = Integer.parseInt(dados[1]);
        int mes = Integer.parseInt(dados[2]);
        int pagina = Integer.parseInt(dados[3]);
        String dataPadrao = String.format("%d-%02d-01", ano, mes);

        mcpClient.resetSession();

        try {
            Map<String, Object> args = new HashMap<>();
            args.put("municipio", municipio);
            args.put("exercicio", ano);
            args.put("mes", mes);
            args.put("pagina", pagina);
            args.put("offset", (pagina - 1) * 30);

            String texto = mcpClient.callTool("tce_sp_consultar_despesas_sp", args);
            int salvos = parsear(texto, municipio, dataPadrao);

            log.info("[Carga] {}/{}/{} Pág {}: {} novos. Total banco: {}",
                    municipio, mes, ano, pagina, salvos, repository.count());

        } catch (Exception e) {
            log.error("[Kafka] Erro na carga: {}", e.getMessage());
        }
    }

    // =====================================================
    // 3. PARSER (REGEX CORRIGIDO)
    // =====================================================
    private int parsear(String texto, String municipio, String dataPadrao) {
        int count = 0;
        // Regex focado em capturar o Favorecido, Valor e Empenho, ignorando o status entre colchetes
        Pattern pattern = Pattern.compile(
                "-\\s+\\[.*?\\]\\s+(.*?):\\s+R\\$\\s+([\\d.,]+)\\s+\\(empenho\\s+(.*?)\\)"
        );
        Matcher matcher = pattern.matcher(texto);

        while (matcher.find()) {
            String favorecido = matcher.group(1).trim();
            String valor      = matcher.group(2).trim();
            String empenho    = matcher.group(3).trim();

            if (empenho.isBlank()) continue;

            if (!repository.existsByDocumentoOrigem(empenho)) {
                repository.save(Despesa.builder()
                        .municipio(municipio)
                        .nomeFavorecido(favorecido)
                        .valorPago(valor)
                        .dataPagamento(dataPadrao)
                        .documentoOrigem(empenho)
                        .build());
                count++;
            }
        }
        return count;
    }

    // =====================================================
    // 4. CRUZAMENTO DE DADOS
    // =====================================================
    @Transactional
    public void realizarCruzamentoEmendas() {
        log.info("[Auditoria] Iniciando cruzamento...");
        List<Despesa> despesas = repository.findAll();
        List<Emenda> emendas   = emendaRepository.findAll();
        for (Despesa d : despesas) {
            for (Emenda e : emendas) {
                if (d.getNomeFavorecido() != null &&
                        d.getNomeFavorecido().toUpperCase().contains(e.getAutor().toUpperCase())) {
                    d.setEmenda(e);
                    repository.save(d);
                }
            }
        }
    }

    // =====================================================
    // 5. ANÁLISE IA (GROQ/OLLAMA)
    // =====================================================
    public void analisarBaseComIA() {
        List<Despesa> pendentes = repository.findPendentesDeAuditoria()
                .stream().limit(100).toList();

        for (Despesa d : pendentes) {
            try {
                String veredito = auditorIA.analisar(sanitizarDados(d));
                d.setVereditoIA(veredito);
                d.setScoreRisco(extrairScore(veredito));
                repository.save(d);
                Thread.sleep(1500);
            } catch (Exception e) {
                log.error("[IA] Erro no registro {}: {}", d.getDocumentoOrigem(), e.getMessage());
            }
        }
    }

    private String sanitizarDados(Despesa d) {
        String nome = d.getNomeFavorecido() != null ? d.getNomeFavorecido() : "N/A";
        String nomeMascarado = nome.length() > 20 ? nome.substring(0, 15) + "..." : nome;
        return String.format("Favorecido: %s | Valor: %s | Município: %s | Doc: %s",
                nomeMascarado, d.getValorPago(), d.getMunicipio(), d.getDocumentoOrigem());
    }

    private Integer extrairScore(String analise) {
        if (analise == null) return 50;
        String u = analise.toUpperCase();
        if (u.contains("CRÍTICO") || u.contains("CRITICO")) return 95;
        if (u.contains("ALTO RISCO")) return 85;
        if (u.contains("SUSPEITO")) return 60;
        if (u.contains("ATENÇÃO") || u.contains("ATENCAO")) return 40;
        return 20;
    }

    // =====================================================
    // 6. DASHBOARD
    // =====================================================
    public Map<String, Object> obterEstatisticasDashboard() {
        List<Despesa> todas = repository.findAll();
        Map<String, Object> stats = new HashMap<>();
        stats.put("total_registros", todas.size());
        stats.put("total_valor", todas.stream().mapToDouble(d -> {
            try { return Double.parseDouble(d.getValorPago().replace(".", "").replace(",", ".")); }
            catch (Exception e) { return 0.0; }
        }).sum());
        stats.put("criticos", todas.stream().filter(d -> d.getScoreRisco() != null && d.getScoreRisco() >= 80).count());
        stats.put("suspeitos", todas.stream().filter(d -> d.getScoreRisco() != null && d.getScoreRisco() >= 50 && d.getScoreRisco() < 80).count());
        return stats;
    }
}