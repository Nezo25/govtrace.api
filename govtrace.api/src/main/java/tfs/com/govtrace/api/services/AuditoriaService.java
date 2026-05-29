package tfs.com.govtrace.api.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

@Service
public class AuditoriaService {

    private static final Logger log = LoggerFactory.getLogger(AuditoriaService.class);

    private final IAuditorAgente auditorIA;
    private final DespesaRepository repository;
    private final EmendaRepository emendaRepository;
    private final McpBrasilClient mcpClient;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;

    @Value("${govtrace.auditoria.lote-maximo:1200}")
    private int loteMaximoAuditoria;

    public AuditoriaService(IAuditorAgente auditorIA, DespesaRepository repository,
                            EmendaRepository emendaRepository, McpBrasilClient mcpClient,
                            KafkaTemplate<String, String> kafkaTemplate, TransactionTemplate transactionTemplate) {
        this.auditorIA = auditorIA;
        this.repository = repository;
        this.emendaRepository = emendaRepository;
        this.mcpClient = mcpClient;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    public void solicitarCarga(String municipio, int anoInicio, int anoFim) {
        mcpClient.resetSession();
        int total = 0;
        for (int ano = anoInicio; ano <= anoFim; ano++) {
            for (int mes = 1; mes <= 12; mes++) {
                kafkaTemplate.send("fila-carga-tce", String.format("%s;%d;%d", municipio, ano, mes));
                total++;
            }
        }
        log.info("[Kafka] {} mensagens enfileiradas.", total);
    }

    @KafkaListener(topics = "fila-carga-tce", groupId = "govtrace-carga-v3")
    public void processarMensagemCarga(String payload) {
        String[] dados = payload.split(";");
        if (dados.length < 3) return;

        String municipio = dados[0];
        int ano = Integer.parseInt(dados[1]);
        int mes = Integer.parseInt(dados[2]);
        String dataPadrao = String.format("%d-%02d-01", ano, mes);

        try {
            Map<String, Object> args = new HashMap<>();
            args.put("municipio", municipio.toLowerCase().trim());
            args.put("exercicio", ano);
            args.put("mes", mes);
            String texto = mcpClient.callTool("tce_sp_consultar_despesas_sp", args);
            transactionTemplate.executeWithoutResult(status -> parsear(texto, municipio, dataPadrao));
        } catch (Exception e) {
            log.error("[Kafka] Erro na carga: {}", e.getMessage());
        }
    }

    private void parsear(String texto, String municipio, String data) {
        if (texto == null || texto.isEmpty()) return;

        Pattern pattern = Pattern.compile("(?i)-\\s+\\[.*?\\]\\s+(.*?):\\s+R\\$\\s+([\\d.,]+)\\s+\\(empenho\\s+(.*?)\\)");
        Matcher matcher = pattern.matcher(texto);
        List<Despesa> lote = new ArrayList<>();

        while (matcher.find()) {
            String fav = matcher.group(1).trim();
            String valor = matcher.group(2).trim();
            String empenho = matcher.group(3).trim();

            if (empenho.isBlank() || repository.existsByDocumentoOrigem(empenho)) continue;

            String cnpjLimpo = null;

            // 1. Tenta extrair a tag (ID: ...) caso a API um dia passe a enviar
            if (fav.contains("(ID:")) {
                int idx = fav.lastIndexOf("(ID:");
                String idSujo = fav.substring(idx + 4, fav.lastIndexOf(")")).trim();
                cnpjLimpo = limparCnpj(idSujo);
                fav = fav.substring(0, idx).trim();
            }

            // 2. Extrai o CPF/CNPJ que já vem colado no nome do fornecedor (O que funcionou na sua Planilha 2!)
            if (cnpjLimpo == null && fav.matches(".*\\s\\d{11,14}$")) {
                Pattern docPattern = Pattern.compile("\\s(\\d{11,14})$");
                Matcher docMatcher = docPattern.matcher(fav);
                if (docMatcher.find()) {
                    cnpjLimpo = docMatcher.group(1);
                    fav = fav.replace(cnpjLimpo, "").trim(); // Limpa o nome para o banco
                }
            }

            // 3. CAMADA DE ENRIQUECIMENTO (Master Fallback do TCC)
            // Resolve o buraco de dados da API do TCE-SP mapeando os maiores recebedores
            if (cnpjLimpo == null) {
                String nomeUpper = fav.toUpperCase();
                if (nomeUpper.contains("PREFEITURA") && nomeUpper.contains("BRAGANCA")) {
                    cnpjLimpo = "46352746000165"; // CNPJ Prefeitura Bragança Paulista
                } else if (nomeUpper.contains("CAIXA ECONOMICA") || nomeUpper.contains("CEF MATRIZ")) {
                    cnpjLimpo = "00360305000104"; // CNPJ Caixa Econômica Federal
                } else if (nomeUpper.contains("SEGURO SOCIAL") || nomeUpper.contains("INSS")) {
                    cnpjLimpo = "29979036000140"; // CNPJ INSS
                } else if (nomeUpper.contains("TRIBUNAL DE JUSTICA")) {
                    cnpjLimpo = "51174001000193"; // CNPJ TJ-SP
                } else if (nomeUpper.contains("SANEAMENTO") || nomeUpper.contains("SABESP")) {
                    cnpjLimpo = "43776517000180"; // CNPJ SABESP
                }
            }

            Despesa d = new Despesa();
            d.setMunicipio(municipio.toUpperCase());
            d.setNomeFavorecido(fav);
            d.setCnpjFavorecido(cnpjLimpo); // Agora o CNPJ da prefeitura será preenchido!
            d.setValorPago(valor);
            d.setDataPagamento(data);
            d.setDocumentoOrigem(empenho);
            d.setNexoCausalConfirmado(false);
            lote.add(d);
        }

        if (!lote.isEmpty()) {
            repository.saveAll(lote);
            log.info("[Carga] {} despesas salvas no banco com sucesso.", lote.size());
        } else {
            log.warn("[Carga] Nenhum registro encontrado para salvar. (Verifique o log do Python)");
        }
    }

    @Transactional
    public void realizarCruzamentoEmendas() {
        log.info("[Auditoria] Iniciando batimento definitivo (JOIN Direto por CNPJ + Valor)...");

        List<Despesa> pendentes = repository.findDespesasSemEmenda();
        List<Emenda> emendas = emendaRepository.findAll();
        int totalVinculos = 0;

        for (Emenda e : emendas) {
            String cnpjEmenda = limparCnpj(e.getCodigoFavorecido());
            double vEmenda = converterValor(e.getValorPago());

            if (vEmenda < 1000 || cnpjEmenda == null || cnpjEmenda.isEmpty()) continue;

            for (Despesa d : pendentes) {
                if (d.getEmenda() != null) continue;

                String cnpjDespesa = limparCnpj(d.getCnpjFavorecido());
                double vDespesa = converterValor(d.getValorPago());

                if (cnpjEmenda.equals(cnpjDespesa)) {
                    boolean valorBate = (vDespesa >= vEmenda * 0.95 && vDespesa <= vEmenda * 1.05);

                    d.setEmenda(e);
                    d.setNexoCausalConfirmado(valorBate);
                    d.setMetodoCruzamento("JOIN_DIRETO_CNPJ");

                    if (valorBate) {
                        d.setVereditoIA("HOMOLOGADO: CNPJ e Valores conferem perfeitamente.");
                    } else {
                        d.setVereditoIA("ALERTA: CNPJ confere, mas há divergência no montante repassado.");
                    }

                    repository.save(d);
                    totalVinculos++;
                    log.info("[MATCH] Vínculo CNPJ: Emenda {} -> Documento {}", e.getCodigoEmenda(), d.getDocumentoOrigem());
                }
            }
        }
        log.info("[Auditoria] Auditoria finalizada. {} vínculos exatos encontrados.", totalVinculos);
    }

    public void analisarBaseComIA() {
        List<Despesa> despesasPendentes = repository.findPendentesDeAuditoria();
        List<Emenda> emendasPendentes = emendaRepository.findPendentesDeAuditoria();

        int limite = Math.min(loteMaximoAuditoria, Math.min(despesasPendentes.size(), emendasPendentes.size()));
        log.info("[IA] Iniciando processamento de {} pares pendentes.", limite);

        for (int i = 0; i < limite; i++) {
            Despesa d = despesasPendentes.get(i);
            Emenda e = emendasPendentes.get(i);
            try {
                String promptD = String.format("[DESPESA] Fav: %s | Val: %s | Cat: %s | Empenho: %s",
                        d.getNomeFavorecido(), d.getValorPago(), d.getCategoria(), d.getDocumentoOrigem());
                d.setVereditoIA(auditorIA.analisar(promptD));
                d.setScoreRisco(extrairScore(d.getVereditoIA()));
                repository.save(d);

                String promptE = String.format("[EMENDA] Autor: %s | Tipo: %s | Val: %s | Loc: %s",
                        e.getAutor(), e.getTipoEmenda(), e.getValorPago(), e.getLocalidade());
                e.setVereditoIA(auditorIA.analisar(promptE));
                e.setScoreRisco(extrairScore(e.getVereditoIA()));
                emendaRepository.save(e);
                Thread.sleep(5000);
            } catch (Exception ex) {
                log.error("[IA] Erro ao analisar: {}", ex.getMessage());
            }
        }
    }

    public Map<String, Object> obterEstatisticasDashboard() {
        List<Despesa> todas = repository.findAll();
        List<Emenda> emendas = emendaRepository.findAll();

        Map<String, Object> s = new HashMap<>();
        s.put("total_registros", todas.size());
        s.put("total_emendas", emendas.size());
        s.put("total_confirmados", todas.stream().filter(Despesa::isNexoCausalConfirmado).count());
        s.put("criticos", Stream.concat(
                todas.stream().filter(d -> d.getScoreRisco() != null && d.getScoreRisco() >= 80),
                emendas.stream().filter(e -> e.getScoreRisco() != null && e.getScoreRisco() >= 80)
        ).count());
        s.put("pendentes_ia_despesas", todas.stream().filter(d -> d.getVereditoIA() == null).count());
        s.put("pendentes_ia_emendas", emendas.stream().filter(e -> e.getVereditoIA() == null).count());
        s.put("lote_maximo_configurado", loteMaximoAuditoria);

        return s;
    }

    private Integer extrairScore(String v) {
        if (v == null) return 50;
        String u = v.toUpperCase();
        if (u.contains("CRÍTICO") || u.contains("CRITICO")) return 95;
        if (u.contains("ALTO RISCO")) return 85;
        if (u.contains("SUSPEITO")) return 60;
        if (u.contains("ATENÇÃO") || u.contains("ATENCAO")) return 40;
        if (u.contains("REGULAR")) return 15;
        return 50;
    }

    private String limparCnpj(String documentoSujo) {
        if (documentoSujo == null || documentoSujo.isBlank()) return null;
        String apenasNumeros = documentoSujo.replaceAll("[^0-9]", "");
        return apenasNumeros.isEmpty() ? null : apenasNumeros;
    }

    private double converterValor(String valor) {
        if (valor == null || valor.isBlank()) return 0.0;
        try {
            return Double.parseDouble(valor.replaceAll("[^\\d,]", "").replace(",", "."));
        } catch (Exception e) { return 0.0; }
    }

    @Transactional
    public void resetarCruzamentos() {
        repository.resetarCruzamentos();
        emendaRepository.resetarVereditos();
        log.info("[Auditoria] Base resetada para nova auditoria.");
    }
}