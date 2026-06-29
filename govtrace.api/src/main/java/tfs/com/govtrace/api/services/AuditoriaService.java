package tfs.com.govtrace.api.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tfs.com.govtrace.api.models.Contrato;
import tfs.com.govtrace.api.models.Despesa;
import tfs.com.govtrace.api.models.Emenda;
import tfs.com.govtrace.api.models.Receita;
import tfs.com.govtrace.api.repositories.ContratoRepository;
import tfs.com.govtrace.api.repositories.DespesaRepository;
import tfs.com.govtrace.api.repositories.EmendaRepository;
import tfs.com.govtrace.api.repositories.ReceitaRepository;
import tfs.com.govtrace.api.ai.AiAuditorFactory;
import tfs.com.govtrace.api.ai.IAuditorStrategy;
import tfs.com.govtrace.api.dtos.DashboardResumoDTO;
import tfs.com.govtrace.api.dtos.VereditoIaResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class AuditoriaService {

    private static final Logger log = LoggerFactory.getLogger(AuditoriaService.class);

    @Value("${govtrace.ia.provider:groq}")
    private String providerIa;

    private final AiAuditorFactory aiAuditorFactory;
    private final DespesaRepository repository;
    private final EmendaRepository emendaRepository;
    private final ContratoRepository contratoRepository;
    private final ReceitaRepository receitaRepository;
    private final ObjectMapper objectMapper;
    private final McpBrasilClient mcpClient;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;

    @Value("${govtrace.auditoria.lote-maximo:1200}")
    private int loteMaximoAuditoria;

    public AuditoriaService(AiAuditorFactory aiAuditorFactory, DespesaRepository repository,
                            EmendaRepository emendaRepository, ContratoRepository contratoRepository,
                            ReceitaRepository receitaRepository, ObjectMapper objectMapper,
                            McpBrasilClient mcpClient, KafkaTemplate<String, String> kafkaTemplate,
                            TransactionTemplate transactionTemplate) {
        this.aiAuditorFactory = aiAuditorFactory;
        this.repository = repository;
        this.emendaRepository = emendaRepository;
        this.contratoRepository = contratoRepository;
        this.receitaRepository = receitaRepository;
        this.objectMapper = objectMapper;
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

            if (fav.contains("(ID:")) {
                int idx = fav.lastIndexOf("(ID:");
                String idSujo = fav.substring(idx + 4, fav.lastIndexOf(")")).trim();
                cnpjLimpo = limparCnpj(idSujo);
                fav = fav.substring(0, idx).trim();
            }

            if (cnpjLimpo == null && fav.matches(".*\\s\\d{11,14}$")) {
                Pattern docPattern = Pattern.compile("\\s(\\d{11,14})$");
                Matcher docMatcher = docPattern.matcher(fav);
                if (docMatcher.find()) {
                    cnpjLimpo = docMatcher.group(1);
                    fav = fav.replace(cnpjLimpo, "").trim();
                }
            }

            if (cnpjLimpo == null) {
                String nomeUpper = fav.toUpperCase();
                if (nomeUpper.contains("PREFEITURA") && nomeUpper.contains("BRAGANCA")) {
                    cnpjLimpo = "46352746000165";
                } else if (nomeUpper.contains("CAIXA ECONOMICA") || nomeUpper.contains("CEF MATRIZ")) {
                    cnpjLimpo = "00360305000104";
                } else if (nomeUpper.contains("SEGURO SOCIAL") || nomeUpper.contains("INSS")) {
                    cnpjLimpo = "29979036000140";
                } else if (nomeUpper.contains("TRIBUNAL DE JUSTICA")) {
                    cnpjLimpo = "51174001000193";
                } else if (nomeUpper.contains("SANEAMENTO") || nomeUpper.contains("SABESP")) {
                    cnpjLimpo = "43776517000180";
                }
            }

            Despesa d = new Despesa();
            d.setMunicipio(municipio.toUpperCase());
            d.setNomeFavorecido(fav);
            d.setCnpjFavorecido(cnpjLimpo);
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
        log.info("[Auditoria] Iniciando Triangulação Financeira (3-Way Match) no Modo Avalanche...");
        List<Despesa> pendentes = repository.findDespesasSemEmenda();
        List<Emenda> emendas = emendaRepository.findAll();
        List<Contrato> contratos = contratoRepository.findAll();
        
        int triangulacoes = 0;
        int parciais = 0;

        // ====================================================================
        // FASE 1: Varredura por Contratos (Triangulação Completa)
        // ====================================================================
        for (Contrato contrato : contratos) {
            String cnpjContrato = limparCnpj(contrato.getNiFornecedor());
            if (cnpjContrato == null || cnpjContrato.isEmpty()) continue;

            double valorContrato = converterValor(contrato.getValorContrato());

            // 1. O Elo Forte (Contrato -> Despesa) (Fallback por Nome do Fornecedor)
            String nomeFornecedor = contrato.getNomeFornecedor() != null ? contrato.getNomeFornecedor().toUpperCase().trim() : "";

            List<Despesa> despesasFornecedor = pendentes.stream()
                    .filter(d -> {
                        String cnpjDesp = limparCnpj(d.getCnpjFavorecido());
                        boolean matchCnpj = cnpjContrato.equals(cnpjDesp);
                        boolean matchNome = !nomeFornecedor.isEmpty() && d.getNomeFavorecido() != null && 
                                            d.getNomeFavorecido().toUpperCase().contains(nomeFornecedor);
                        return matchCnpj || matchNome;
                    })
                    .collect(Collectors.toList());
            
            if (despesasFornecedor.isEmpty()) continue;

            double somaDespesas = despesasFornecedor.stream()
                    .mapToDouble(d -> converterValor(d.getValorPago()))
                    .sum();

            // A Nova Regra de Valor: A soma das Despesas não pode ultrapassar o Valor do Contrato (+5%)
            if (somaDespesas > valorContrato * 1.05) {
                log.warn("[Auditoria] Match abortado por valor: CNPJ {} | Soma Despesas: {} | Valor Contrato: {}", 
                         cnpjContrato, somaDespesas, valorContrato);
                continue;
            }

            // Descoberta Temporal: Se o contrato não tiver ano, pega o ano da primeira despesa
            String anoAlvo = contrato.getAno() != null ? contrato.getAno().toString() : "";
            if (anoAlvo.isEmpty() && despesasFornecedor.get(0).getDataPagamento() != null && despesasFornecedor.get(0).getDataPagamento().length() >= 4) {
                anoAlvo = despesasFornecedor.get(0).getDataPagamento().substring(0, 4);
            }

            // 2. O Elo Fraco (Contrato -> Emenda via Pool de Recursos)
            Emenda emendaCorrespondente = null;
            for (Emenda e : emendas) {
                double vEmenda = converterValor(e.getValorPago()); 
                
                // Emenda tem que ter caixa para cobrir o contrato
                if (vEmenda < valorContrato) continue; 
                
                String anoEmenda = e.getAno() != null ? e.getAno().toString() : "";
                
                if (!anoAlvo.isEmpty() && anoAlvo.equals(anoEmenda)) {
                    emendaCorrespondente = e;
                    break; // Achou a primeira que cobre a conta do mesmo ano!
                }
            }

            // 3. Efetivação da Triangulação
            if (emendaCorrespondente != null) {
                // AQUI ENTRA A FASE 2: 4-WAY MATCH (Receita Municipal)
                List<Receita> receitasAno = receitaRepository.findByCodigoRubricaStartingWithAndAno("1.7.1", anoAlvo);
                double somaReceitasFederais = receitasAno.stream().mapToDouble(Receita::getValorTotal).sum();
                double valorEmendaDouble = converterValor(emendaCorrespondente.getValorPago());
                
                if (somaReceitasFederais >= valorEmendaDouble) {
                    Receita receitaUtilizada = receitasAno.isEmpty() ? null : receitasAno.get(0);
                    
                    for (Despesa d : despesasFornecedor) {
                        d.setEmenda(emendaCorrespondente);
                        if (receitaUtilizada != null) d.setReceita(receitaUtilizada);
                        d.setNexoCausalConfirmado(true);
                        d.setMetodoCruzamento("TRIANGULACAO_COMPLETA");
                        repository.save(d);
                    }
                    
                    String vereditoSucesso = "HOMOLOGADO (4-WAY MATCH): Receita validou a entrada. IA validará o nexo semântico.";
                    
                    emendaCorrespondente.setVereditoIA(vereditoSucesso);
                    emendaCorrespondente.setScoreRisco(0);
                    emendaRepository.save(emendaCorrespondente);

                    contrato.setVereditoIA(vereditoSucesso);
                    contrato.setScoreRisco(0);
                    contratoRepository.save(contrato);
                } else {
                    log.warn("[Auditoria] 4-Way Match falhou: Emenda {} sem lastro contábil na Receita.", emendaCorrespondente.getCodigoEmenda());
                    continue;
                }

                log.info("[MATCH] Triangulação estabelecida: Emenda {} -> Contrato {} -> {} Despesa(s)", 
                         emendaCorrespondente.getCodigoEmenda(), contrato.getId(), despesasFornecedor.size());
                triangulacoes++;
                
                // Remove as despesas da lista de pendentes para não cruzar de novo na Fase 2
                pendentes.removeAll(despesasFornecedor);
            }
        }

        // ====================================================================
        // FASE 2: Fallback (JOIN DIRETO_CNPJ)
        // ====================================================================
        for (Emenda e : emendas) {
            String cnpjEmenda = limparCnpj(e.getCodigoFavorecido());
            double vEmenda = converterValor(e.getValorPago());

            if (cnpjEmenda == null || cnpjEmenda.isEmpty()) continue;

            List<Despesa> despesasFornecedor = pendentes.stream()
                    .filter(d -> cnpjEmenda.equals(limparCnpj(d.getCnpjFavorecido())))
                    .collect(Collectors.toList());

            if (despesasFornecedor.isEmpty()) continue;

            double somaDespesas = despesasFornecedor.stream()
                    .mapToDouble(d -> converterValor(d.getValorPago()))
                    .sum();

            // Relaxamento também no fallback
            boolean valorBate = (somaDespesas <= vEmenda * 1.05); 

            for (Despesa d : despesasFornecedor) {
                d.setEmenda(e);
                d.setNexoCausalConfirmado(valorBate);
                d.setMetodoCruzamento("JOIN_DIRETO_CNPJ");
                repository.save(d);
            }
            
            log.info("[MATCH] Vínculo Direto: Emenda {} -> {} Despesa(s) (Contrato não encontrado)", 
                     e.getCodigoEmenda(), despesasFornecedor.size());
            parciais++;
            
            pendentes.removeAll(despesasFornecedor);
        }

        log.info("[Auditoria] Auditoria finalizada. {} triangulações e {} cruzamentos diretos realizados.", triangulacoes, parciais);
    }

    public void analisarBaseComIA() {
        List<Despesa> despesasPendentes = repository.findByMetodoCruzamentoIsNotNullAndVereditoIAIsNull();

        if (despesasPendentes.isEmpty()) {
            log.info("[IA] Nenhum registro pendente de análise semântica.");
            return;
        }

        int limite = Math.min(loteMaximoAuditoria, despesasPendentes.size());
        log.info("[IA] Iniciando processamento de pendentes (lote de {} registros).", limite);

        IAuditorStrategy auditorIA = aiAuditorFactory.obterAuditor(providerIa);

        List<Despesa> loteParaProcessar = despesasPendentes.subList(0, limite);

        for (Despesa d : loteParaProcessar) {
            try {
                Emenda e = d.getEmenda();
                if (e != null) {
                    String cnpj = limparCnpj(d.getCnpjFavorecido());
                    List<Contrato> contratos = cnpj != null ? contratoRepository.findByNiFornecedor(cnpj) : new ArrayList<>();
                    
                    if (contratos.isEmpty() && "TRIANGULACAO_COMPLETA".equals(d.getMetodoCruzamento())) {
                        String nomeFav = d.getNomeFavorecido() != null ? d.getNomeFavorecido().toUpperCase().trim() : "";
                        if (!nomeFav.isEmpty()) {
                            List<Contrato> allContratos = contratoRepository.findAll();
                            contratos = allContratos.stream()
                                    .filter(c -> c.getNomeFornecedor() != null && 
                                            nomeFav.contains(c.getNomeFornecedor().toUpperCase().trim()))
                                    .collect(Collectors.toList());
                        }
                    }

                    String objetoContrato = contratos.isEmpty() ? "NÃO ESPECIFICADO NO PNCP" : 
                            contratos.stream().map(Contrato::getObjeto).collect(Collectors.joining(" | "));
                            
                    String anoEmenda = e.getAno() != null ? e.getAno().toString() : "N/D";
                    double valorEmenda = converterValor(e.getValorPago());
                    String funcaoEmenda = e.getFuncao() != null ? e.getFuncao() : "N/D";
                    String localidadeEmenda = e.getLocalidade() != null ? e.getLocalidade() : "N/D";
                    String nomeFavorecido = d.getNomeFavorecido() != null ? d.getNomeFavorecido() : "N/D";
                    double valorPago = converterValor(d.getValorPago());

                    String promptContextual = String.format(java.util.Locale.forLanguageTag("pt-BR"),
                        "Você é um Auditor de Contas Públicas do Tribunal de Contas. Analise este nexo causal probabilístico do município de Bragança Paulista.\n\n" +
                        "1. ORIGEM (Emenda Federal): Ano %s | Valor Total: R$ %.2f | Função: %s | Localidade: %s\n" +
                        "2. CONTRATO (PNCP): Objeto: '%s'\n" +
                        "3. PAGAMENTO (TCE): Favorecido: %s | Valor Pago: R$ %.2f\n\n" +
                        "DIRETRIZES DE AUDITORIA:\n" +
                        "- Despesas de CUSTEIO (ex: internet, telefonia, limpeza, pequenos materiais de TI) pagas a empresas como Telefônica ou provedores locais SÃO COMUNS e legais para manter o funcionamento da máquina pública ou do projeto fim. Não classifique imediatamente como desvio de finalidade só por ser uma conta básica de valor baixo.\n" +
                        "- Questione apenas bizarrices evidentes (ex: emenda para 'Asfalto' pagando 'Buffet de Festas').\n" +
                        "- Se a despesa for de custeio básico e o valor pago for compatível com a realidade, dê SCORE baixo (0 a 30).\n" +
                        "REGRA JURÍDICA ESTRITA: Aja de forma técnica. Nunca use a palavra 'crime'.\n\n" +
                        "OBRIGATÓRIO responder em 2 linhas EXATAS:\n" +
                        "SCORE: [número de 0 a 100]\n" +
                        "JUSTIFICATIVA: [Sua análise técnica curta e direta]",
                        anoEmenda, valorEmenda, funcaoEmenda, localidadeEmenda,
                        objetoContrato,
                        nomeFavorecido, valorPago
                    );
                    
                    String respostaIA = auditorIA.analisar(promptContextual);
                    
                    // Fase 3 (Cognitiva com JSON Blindado - Jackson)
                    int score = 0;
                    try {
                        // Limpa marcadores Markdown do JSON se existirem
                        String jsonLimpo = respostaIA.replace("```json", "").replace("```", "").trim();
                        VereditoIaResponse respostaIaDto = objectMapper.readValue(jsonLimpo, VereditoIaResponse.class);
                        d.setVereditoIA(respostaIaDto.getVereditoIA() + " | Justificativa: " + respostaIaDto.getJustificativa());
                        if (respostaIaDto.getScoreRisco() != null) {
                            score = respostaIaDto.getScoreRisco();
                        }
                    } catch (JsonProcessingException ex) {
                        log.warn("[IA] Falha ao parsear JSON da IA. Salvando texto bruto. Erro: {}", ex.getMessage());
                        d.setVereditoIA(respostaIA);
                    }
                    
                    d.setScoreRisco(score);

                    // 3. Garante o Save
                    repository.save(d);
                    log.info("[IA] Análise concluída para Despesa ID {}: Score {}", d.getId(), score);
                    
                    log.info("[IA] Pausa de cadência (6s) para preservar limite de tokens TPM...");
                    Thread.sleep(6000);
                }
            } catch (Exception ex) {
                log.error("[IA] Erro ao analisar a Despesa ID {}: {}", d.getId(), ex.getMessage());
            }
        }
        
        log.info("[IA] Processamento finalizado.");
    }

    public Map<String, Object> obterEstatisticasDashboard() {
        List<Despesa> todas = repository.findAll();
        List<Emenda> emendas = emendaRepository.findAll();

        Map<String, Object> s = new HashMap<>();
        s.put("total_registros", todas.size());
        s.put("total_emendas", emendas.size());
        s.put("contratos_analisados", contratoRepository.count());
        
        long nexoTriplo = todas.stream().filter(d -> "TRIANGULACAO_COMPLETA".equals(d.getMetodoCruzamento())).count();
        long nexoDireto = todas.stream().filter(d -> "JOIN_DIRETO_CNPJ".equals(d.getMetodoCruzamento())).count();
        long orfas = todas.stream().filter(d -> d.getEmenda() == null).count();

        s.put("nexo_triplo_perfeito", nexoTriplo);
        s.put("nexo_direto_parcial", nexoDireto);
        s.put("orfas_suspeitas", orfas);
        
        s.put("total_confirmados", todas.stream().filter(Despesa::isNexoCausalConfirmado).count());
        
        s.put("criticos", Stream.concat(
                todas.stream().filter(d -> d.getScoreRisco() != null && d.getScoreRisco() >= 80),
                emendas.stream().filter(e -> e.getScoreRisco() != null && e.getScoreRisco() >= 80)
        ).count());
        
        s.put("pendentes_ia_despesas", todas.stream().filter(d -> d.getVereditoIA() == null && d.getMetodoCruzamento() != null).count());
        s.put("pendentes_ia_emendas", emendas.stream().filter(e -> e.getVereditoIA() == null).count());
        s.put("lote_maximo_configurado", loteMaximoAuditoria);

        return s;
    }

    public DashboardResumoDTO obterResumoDashboard() {
        DashboardResumoDTO dto = new DashboardResumoDTO();
        dto.setTotalEmendas(emendaRepository.count());
        dto.setTotalContratos(contratoRepository.count());
        dto.setTotalDespesas(repository.count());
        dto.setNexoTriploPerfeito(repository.countByMetodoCruzamento("TRIANGULACAO_COMPLETA"));
        dto.setNexoDiretoParcial(repository.countByMetodoCruzamento("JOIN_DIRETO_CNPJ"));
        dto.setDespesasOrfas(repository.countByEmendaIsNull());
        
        // Alertas Críticos: Score > 70 ou Veredito contendo CRÍTICO (Scale-out)
        long criticos = repository.countByScoreRiscoGreaterThanOrVereditoIAContainsIgnoreCase(70, "CRÍTICO");
        dto.setAlertasCriticos(criticos);
        
        return dto;
    }

    public List<Despesa> obterAlertasRecentes() {
        return repository.findTop10ByOrderByScoreRiscoDesc();
    }

    private Integer extrairScore(String v) {
        if (v == null) return 50;

        java.util.regex.Matcher mScore = java.util.regex.Pattern.compile("(?i)SCORE:\\s*(\\d+)").matcher(v);
        if (mScore.find()) {
            try {
                return Integer.parseInt(mScore.group(1));
            } catch (Exception e) {}
        }

        java.util.regex.Matcher mRisco = java.util.regex.Pattern.compile("(?i)RISCO:\\s*([^\\n]+)").matcher(v);
        if (mRisco.find()) {
            String r = mRisco.group(1).toUpperCase();
            if (r.contains("CRÍTICO") || r.contains("CRITICO")) return 95;
            if (r.contains("ALTO RISCO")) return 85;
            if (r.contains("SUSPEITO")) return 60;
            if (r.contains("ATENÇÃO") || r.contains("ATENCAO")) return 40;
            if (r.contains("REGULAR")) return 15;
        }

        String u = v.toUpperCase();
        if (u.contains("CRÍTICO") || u.contains("CRITICO")) return 95;
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

    public byte[] exportarAuditoriaCsv() {
        List<Despesa> despesasCruzadas = repository.findByMetodoCruzamentoIsNotNull();
        StringBuilder csvBuilder = new StringBuilder();
        
        // Cabeçalho
        csvBuilder.append("ID_Despesa;Data;CNPJ_Fornecedor;Nome_Fornecedor;Valor_Pago;Metodo_Cruzamento;Score_Risco;Veredito_IA;Evidencias_Rastreabilidade\n");
        
        for (Despesa d : despesasCruzadas) {
            String id = d.getId() != null ? d.getId().toString() : "";
            String data = d.getDataPagamento() != null ? d.getDataPagamento() : "";
            String cnpj = d.getCnpjFavorecido() != null ? d.getCnpjFavorecido() : "";
            String nome = d.getNomeFavorecido() != null ? d.getNomeFavorecido().replace(";", ",") : "";
            String valor = d.getValorPago() != null ? d.getValorPago() : "";
            String metodo = d.getMetodoCruzamento() != null ? d.getMetodoCruzamento() : "";
            String score = d.getScoreRisco() != null ? d.getScoreRisco().toString() : "0";
            
            // Limpa quebras de linha e ponto e vírgula do veredito para não quebrar o CSV
            String veredito = d.getVereditoIA() != null ? d.getVereditoIA().replace("\n", " ").replace(";", ",") : "Sem análise";
            
            String emendaRef = d.getEmenda() != null && d.getEmenda().getCodigoEmenda() != null ? d.getEmenda().getCodigoEmenda() : "N/A";
            String evidencias = String.format("[Empenho: %s | CNPJ: %s | Emenda_Ref: %s]",
                    d.getDocumentoOrigem() != null ? d.getDocumentoOrigem() : "N/A",
                    cnpj,
                    emendaRef);
                    
            csvBuilder.append(String.format("%s;%s;%s;%s;%s;%s;%s;%s;%s\n",
                    id, data, cnpj, nome, valor, metodo, score, veredito, evidencias));
        }
        
        return csvBuilder.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}