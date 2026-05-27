package tfs.com.govtrace.api.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tfs.com.govtrace.api.models.Despesa;
import tfs.com.govtrace.api.models.Emenda;
import tfs.com.govtrace.api.repositories.DespesaRepository;
import tfs.com.govtrace.api.repositories.EmendaRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Responsável por:
 *   1. Enfileirar e processar carga de despesas TCE-SP via Kafka
 *   2. Cruzamento de nexo causal (despesas x emendas)
 *   3. Análise de risco via IA (Groq)
 *
 * Emendas são gerenciadas exclusivamente pelo EmendaService.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AuditoriaService {

    private final IAuditorAgente auditorIA;
    private final DespesaRepository repository;
    private final EmendaRepository emendaRepository;
    private final McpBrasilClient mcpClient;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;

    @Value("${govtrace.auditoria.lote-maximo:1200}")
    private int loteMaximoAuditoria;

    // =====================================================
    // 1. CARGA DE DESPESAS (KAFKA PRODUCER)
    // =====================================================

    public void solicitarCarga(String municipio, int anoInicio, int anoFim) {
        mcpClient.resetSession();
        int total = 0;
        for (int ano = anoInicio; ano <= anoFim; ano++) {
            for (int mes = 1; mes <= 12; mes++) {
                kafkaTemplate.send("fila-carga-tce", String.format("%s;%d;%d", municipio, ano, mes));
                total++;
            }
        }
        log.info("[Kafka] {} mensagens enfileiradas: {} | {} → {}", total, municipio, anoInicio, anoFim);
    }

    // =====================================================
    // 2. PROCESSAMENTO (KAFKA CONSUMER)
    // =====================================================

    @KafkaListener(topics = "fila-carga-tce", groupId = "govtrace-carga-v3")
    public void processarMensagemCarga(String payload) {
        String[] dados = payload.split(";");
        if (dados.length < 3) return;

        String municipio  = dados[0];
        int ano           = Integer.parseInt(dados[1]);
        int mes           = Integer.parseInt(dados[2]);
        String dataPadrao = String.format("%d-%02d-01", ano, mes);

        mcpClient.resetSession();

        try {
            Map<String, Object> args = new HashMap<>();
            args.put("municipio", municipio.toLowerCase().trim());
            args.put("exercicio", ano);
            args.put("mes", mes);

            // HTTP fora da transação para evitar deadlock
            String texto = mcpClient.callTool("tce_sp_consultar_despesas_sp", args);

            transactionTemplate.executeWithoutResult(status ->
                    parsear(texto, municipio, dataPadrao));

        } catch (Exception e) {
            log.error("[Kafka] Erro {}/{}/{}: {}", municipio, mes, ano, e.getMessage());
        }
    }

    // =====================================================
    // 3. PARSER TCE-SP
    // Formato: "- [Empenhado] FAVORECIDO: R$ VALOR (empenho NUMERO)"
    // =====================================================

    private void parsear(String texto, String municipio, String data) {
        Pattern pattern = Pattern.compile(
                "(?i)-\\s+\\[.*?\\]\\s+(.*?):\\s+R\\$\\s+([\\d.,]+)\\s+\\(empenho\\s+(.*?)\\)"
        );
        Matcher matcher = pattern.matcher(texto);
        List<Despesa> lote = new ArrayList<>();

        while (matcher.find()) {
            String fav    = matcher.group(1).trim();
            String valor  = matcher.group(2).trim();
            String empenho = matcher.group(3).trim();

            if (empenho.isBlank() || repository.existsByDocumentoOrigem(empenho)) continue;

            lote.add(Despesa.builder()
                    .municipio(municipio)
                    .nomeFavorecido(fav)
                    .valorPago(valor)
                    .categoria(inferirCategoria(fav))
                    .dataPagamento(data)
                    .documentoOrigem(empenho)
                    .nexoCausalConfirmado(false)
                    .build());
        }

        if (!lote.isEmpty()) {
            repository.saveAll(lote);
            log.info("[Carga] {} registros salvos para {}.", lote.size(), data);
        } else {
            log.warn("[Carga] 0 registros para {}. Início do texto: {}",
                    data, texto.substring(0, Math.min(150, texto.length())));
        }
    }

    /**
     * Infere categoria pelo nome do favorecido.
     * Será sobrescrita pelo cruzamento quando houver nexo causal confirmado.
     */
    private String inferirCategoria(String fav) {
        String f = fav.toUpperCase();
        if (f.contains("SAUDE") || f.contains("MEDIC") || f.contains("HOSPIT") || f.contains("FARMAC")) return "SAÚDE";
        if (f.contains("EDUCA") || f.contains("ENSINO") || f.contains("ESCOLA") || f.contains("PROMOVE")) return "EDUCAÇÃO";
        if (f.contains("TRANSPORT") || f.contains("COMBUSTIV") || f.contains("VIACO") || f.contains("ONIBUS")) return "TRANSPORTE";
        if (f.contains("OBRA") || f.contains("CONSTRUC") || f.contains("SANEAM") || f.contains("SABESP")) return "INFRAESTRUTURA";
        if (f.contains("ADVOGAD") || f.contains("TRIBUNAL") || f.contains("JUSTICA") || f.contains("PROCURAD")) return "LEGAL";
        if (f.contains("PREFEITURA")) return "ADMINISTRAÇÃO";
        return "OUTROS";
    }

    // =====================================================
    // 4. CRUZAMENTO NEXO CAUSAL (despesas x emendas)
    // =====================================================

    @Transactional
    public void realizarCruzamentoEmendas() {
        log.info("[Cruzamento] Iniciando batimento nexo causal...");
        List<Despesa> pendentes = repository.findDespesasSemEmenda();
        List<Emenda> emendas    = emendaRepository.findAll();

        int matches = 0;
        for (Despesa d : pendentes) {
            String fav = d.getNomeFavorecido().toUpperCase();
            String cat = d.getCategoria() != null ? d.getCategoria().toUpperCase() : "";

            for (Emenda e : emendas) {
                if (e.getAutor() == null) continue;
                String autor  = e.getAutor().toUpperCase();
                String funcao = e.getFuncao() != null ? e.getFuncao().toUpperCase() : "";

                boolean autorMatch = fav.contains(autor);
                boolean areaMatch  = cat.contains(funcao) || fav.contains(funcao);

                if (autorMatch && areaMatch) {
                    double vDespesa = converterValor(d.getValorPago());
                    double vEmenda  = converterValor(e.getValorPago());

                    if (vDespesa <= vEmenda + 0.01) {
                        d.setEmenda(e);
                        d.setNexoCausalConfirmado(true);
                        d.setCategoria(funcao);
                        d.setMetodoCruzamento("NEXO_AUTOR_VALOR");
                        d.setVereditoIA("NEXO CONFIRMADO: vínculo por metadados.");
                        repository.save(d);
                        matches++;
                        log.info("[MATCH] {} → emenda de {}", d.getDocumentoOrigem(), e.getAutor());
                    }
                }
            }
        }
        log.info("[Cruzamento] Concluído: {} vínculos gerados.", matches);
    }

    // =====================================================
    // 5. BALANCEAMENTO E ANÁLISE IA (50/50 despesas x emendas)
    // =====================================================

    public record LoteBalanceado(List<Despesa> despesas, List<Emenda> emendas, int limite) {}

    /**
     * Iguala despesas e emendas no mesmo lote: até {@code loteMaximoAuditoria} (padrão 1200)
     * e nunca mais que o menor estoque disponível.
     */
    public LoteBalanceado balancearParaAuditoria(List<Despesa> despesas, List<Emenda> emendas) {
        int limite = Math.min(loteMaximoAuditoria, Math.min(despesas.size(), emendas.size()));
        if (limite <= 0) {
            return new LoteBalanceado(List.of(), List.of(), 0);
        }
        List<Despesa> despesasBalanceadas = despesas.stream().limit(limite).toList();
        List<Emenda> emendasBalanceadas = emendas.stream().limit(limite).toList();
        return new LoteBalanceado(despesasBalanceadas, emendasBalanceadas, limite);
    }

    public LoteBalanceado obterLotePendenteBalanceado() {
        return balancearParaAuditoria(
                repository.findPendentesDeAuditoria(),
                emendaRepository.findPendentesDeAuditoria());
    }

    public void analisarBaseComIA() {
        LoteBalanceado lote = obterLotePendenteBalanceado();
        log.info("[IA] Lote balanceado: {} despesas + {} emendas (limite={})",
                lote.despesas().size(), lote.emendas().size(), lote.limite());

        if (lote.limite() == 0) {
            log.warn("[IA] Nada a analisar — é necessário ter despesas E emendas pendentes.");
            return;
        }

        for (int i = 0; i < lote.limite(); i++) {
            Despesa d = lote.despesas().get(i);
            Emenda e = lote.emendas().get(i);
            try {
                analisarDespesaComIA(d);
                analisarEmendaComIA(e);
                Thread.sleep(5000);
            } catch (Exception ex) {
                log.error("[IA] Falha no par índice {}: {}", i, ex.getMessage());
            }
        }
        log.info("[IA] Análise balanceada concluída ({} pares processados).", lote.limite());
    }

    private void analisarDespesaComIA(Despesa d) {
        String prompt = String.format(
                "[DESPESA] Fav: %s | Val: %s | Cat: %s | Empenho: %s",
                d.getNomeFavorecido(), d.getValorPago(), d.getCategoria(), d.getDocumentoOrigem());
        String veredito = auditorIA.analisar(prompt);
        d.setVereditoIA(veredito);
        d.setScoreRisco(extrairScore(veredito));
        repository.save(d);
    }

    private void analisarEmendaComIA(Emenda e) {
        String prompt = String.format(
                "[EMENDA] Autor: %s | Tipo: %s | Valor: %s | Localidade: %s | Código: %s",
                e.getAutor(), e.getTipoEmenda(), e.getValorPago(), e.getLocalidade(), e.getCodigoEmenda());
        String veredito = auditorIA.analisar(prompt);
        e.setVereditoIA(veredito);
        e.setScoreRisco(extrairScore(veredito));
        emendaRepository.save(e);
    }

    // =====================================================
    // 6. DASHBOARD
    // =====================================================

    public Map<String, Object> obterEstatisticasDashboard() {
        List<Despesa> todas = repository.findAll();
        List<Emenda> emendas = emendaRepository.findAll();
        LoteBalanceado lote = obterLotePendenteBalanceado();

        Map<String, Object> s = new HashMap<>();
        s.put("total_registros", todas.size());
        s.put("total_emendas", emendas.size());
        s.put("total_confirmados", todas.stream().filter(Despesa::isNexoCausalConfirmado).count());
        s.put("criticos", Stream.concat(
                todas.stream().filter(d -> d.getScoreRisco() != null && d.getScoreRisco() >= 80),
                emendas.stream().filter(e -> e.getScoreRisco() != null && e.getScoreRisco() >= 80)
        ).count());
        s.put("pendentes_ia_despesas", repository.findPendentesDeAuditoria().size());
        s.put("pendentes_ia_emendas", emendaRepository.findPendentesDeAuditoria().size());
        s.put("lote_balanceado", lote.limite());
        s.put("lote_maximo_configurado", loteMaximoAuditoria);
        return s;
    }

    // =====================================================
    // UTILITÁRIOS
    // =====================================================

    private double converterValor(String valor) {
        if (valor == null || valor.isBlank()) return 0.0;
        try {
            return Double.parseDouble(
                    valor.replace("R$", "").replace(".", "").replace(",", ".").trim());
        } catch (Exception e) {
            return 0.0;
        }
    }

    private Integer extrairScore(String v) {
        if (v == null) return 50;
        String u = v.toUpperCase();
        if (u.contains("CRÍTICO") || u.contains("CRITICO")) return 95;
        if (u.contains("ALTO RISCO"))                        return 85;
        if (u.contains("SUSPEITO"))                          return 60;
        if (u.contains("ATENÇÃO") || u.contains("ATENCAO")) return 40;
        if (u.contains("REGULAR"))                           return 15;
        return 50;
    }
}