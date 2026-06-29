package tfs.com.govtrace.api.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tfs.com.govtrace.api.models.Emenda;
import tfs.com.govtrace.api.repositories.DespesaRepository;
import tfs.com.govtrace.api.repositories.EmendaRepository;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.annotation.KafkaListener;

@Service
public class EmendaService {

    private static final Logger log = LoggerFactory.getLogger(EmendaService.class);

    private final McpBrasilClient mcpClient;
    private final EmendaRepository emendaRepository;
    private final DespesaRepository despesaRepository;
    private final TransactionTemplate transactionTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${govtrace.emendas.limite-carga:10000}")
    private int limiteCargaEmendas;

    @Value("${govtrace.emendas.max-paginas-busca:1000}")
    private int maxPaginasBusca;

    @Value("${govtrace.emendas.codigo-ibge:3507605}")
    private int codigoIbgePadrao;

    public EmendaService(McpBrasilClient mcpClient, EmendaRepository emendaRepository,
                         DespesaRepository despesaRepository, TransactionTemplate transactionTemplate,
                         KafkaTemplate<String, String> kafkaTemplate) {
        this.mcpClient = mcpClient;
        this.emendaRepository = emendaRepository;
        this.despesaRepository = despesaRepository;
        this.transactionTemplate = transactionTemplate;
        this.kafkaTemplate = kafkaTemplate;
    }

    public void carregarEmendasMunicipio(String nomeMunicipio, int ano) {
        carregarEmendasMunicipio(nomeMunicipio, ano, "MUNICIPIO");
    }

    public void carregarEmendasMunicipio(String nomeMunicipio, int ano, String escopo) {
        String escopoEfetivo = (escopo != null && !escopo.isBlank()) ? escopo.toUpperCase() : "ESTADO";
        log.info("[Emendas] Iniciando carga massiva | {} | {} | Escopo: {}", nomeMunicipio, ano, escopoEfetivo);
        executarCarga(nomeMunicipio, ano, escopoEfetivo);
    }

    public void solicitarCargaEmendas(String municipio, int anoInicio, int anoFim) {
        mcpClient.resetSession();
        int total = 0;
        for (int ano = anoInicio; ano <= anoFim; ano++) {
            kafkaTemplate.send("fila-carga-emendas", String.format("%s;%d", municipio, ano));
            total++;
        }
        log.info("[Kafka] {} mensagens enfileiradas para carga de emendas.", total);
    }

    @KafkaListener(topics = "fila-carga-emendas", groupId = "govtrace-carga-v3")
    public void processarMensagemCargaEmendas(String payload) {
        String[] dados = payload.split(";");
        if (dados.length < 2) return;
        String municipio = dados[0];
        int ano = Integer.parseInt(dados[1]);
        try {
            executarCarga(municipio, ano, "MUNICIPIO");
        } catch (Exception e) {
            log.error("[Kafka] Erro na carga de emendas: {}", e.getMessage());
        }
    }

    private void executarCarga(String nomeMunicipio, int ano, String escopo) {
        mcpClient.resetSession();
        try {
            Map<String, Object> args = new HashMap<>();
            args.put("ano", ano);
            args.put("municipio", nomeMunicipio);
            args.put("codigo_ibge", codigoIbgePadrao);
            args.put("buscar_todas_paginas", true);
            args.put("limite_registros", limiteCargaEmendas);
            args.put("escopo", escopo);
            args.put("max_paginas", maxPaginasBusca);
            args.put("priorizar_municipio", true);

            String texto = mcpClient.callTool("govtrace_buscar_emendas", args);

            if (texto != null && texto.contains("|")) {
                transactionTemplate.executeWithoutResult(status -> {
                    int salvas = parsearEmendasCGU(texto, nomeMunicipio, ano, escopo);
                    log.info("[Emendas] Persistidas {} novas emendas do escopo {}.", salvas, escopo);
                });
            } else {
                log.warn("[Emendas] Resposta vazia ou sem tabela para escopo {}", escopo);
            }
        } catch (Exception e) {
            log.error("[Emendas] Falha crítica na carga de {}: {}", escopo, e.getMessage());
        }
    }

    /**
     * Fatiamento da tabela Markdown retornada pelo MCP (7 colunas: Número … Pago).
     * Índices úteis após split("|"): [1]..[7] quando [0] é vazio por causa do "|" inicial.
     */
    private int parsearEmendasCGU(String texto, String municipioAlvo, int ano, String escopo) {
        if (texto == null || texto.isBlank()) {
            log.warn("[Parser][Emendas] Texto MCP vazio ou nulo recebido para processamento.");
            return 0;
        }

        List<Emenda> emendasLote = new ArrayList<>();
        String[] linhas = texto.split("\\r?\\n");

        int linhaNum = 0;
        int ignoradas = 0;
        int colunasInsuficientes = 0;
        int duplicadas = 0;
        int errosParse = 0;

        log.info("[Parser][Emendas] Iniciando processamento de {} linhas...", linhas.length);

        for (String linhaBruta : linhas) {
            linhaNum++;
            String linha = linhaBruta.trim();

            if (linha.isEmpty() || !linha.startsWith("|") || linha.contains("---")) {
                ignoradas++;
                continue;
            }

            String linhaUpper = linha.toUpperCase(Locale.ROOT);
            if (linhaUpper.contains("NÚMERO") || linhaUpper.contains("NUMERO")
                    || linhaUpper.contains("COD. EMENDA") || linhaUpper.contains("COD EMENDA")) {
                ignoradas++;
                continue;
            }

            try {
                // ISO/OWASP: Sanitização da linha e extração baseada em Streams para evitar offsets erráticos
                String linhaLimpa = linha;
                if (linhaLimpa.startsWith("|")) linhaLimpa = linhaLimpa.substring(1);
                if (linhaLimpa.endsWith("|")) linhaLimpa = linhaLimpa.substring(0, linhaLimpa.length() - 1);

                List<String> cols = Arrays.stream(linhaLimpa.split("\\|", -1))
                        .map(String::trim)
                        .toList();

                boolean formatoEstendido = cols.size() > 9;
                
                int idxCodigoEmenda = 0;
                int idxNumero = formatoEstendido ? 1 : 0;
                int idxAutor = formatoEstendido ? 2 : 1;
                int idxNatureza = formatoEstendido ? 3 : 2;
                int idxTipo = formatoEstendido ? 4 : 3;
                int idxLocalidade = formatoEstendido ? 5 : 4;
                int idxCnpj = formatoEstendido ? 6 : 5;
                int idxFuncao = formatoEstendido ? 7 : -1;
                int idxSubfuncao = formatoEstendido ? 8 : -1;
                int idxEmpenhado = formatoEstendido ? 9 : 6;
                int idxPago = formatoEstendido ? 10 : 7;

                if (cols.size() <= idxPago) {
                    colunasInsuficientes++;
                    log.warn("[Parser][Emendas] L{}: Colunas insuficientes (encontradas {}, necessárias {}).", linhaNum, cols.size(), idxPago + 1);
                    continue;
                }

                String codigo = cols.get(idxCodigoEmenda);
                if (!formatoEstendido && isVazioOuTraco(codigo)) {
                    codigo = gerarCodigoFallback(ano);
                } else if (formatoEstendido && (isVazioOuTraco(codigo) || codigo.startsWith("DOC-"))) {
                    String numeroTmp = cols.get(idxNumero);
                    codigo = !isVazioOuTraco(numeroTmp) ? "CGU-" + ano + "-" + numeroTmp.replaceAll("[^A-Za-z0-9]", "") : gerarCodigoFallback(ano);
                }

                if (emendaRepository.existsByCodigoEmenda(codigo)) {
                    duplicadas++;
                    continue;
                }

                String autor = sanitizarTexto(cols.get(idxAutor)).toUpperCase(Locale.ROOT);
                String natureza = sanitizarTexto(cols.get(idxNatureza));
                String tipo = sanitizarTexto(cols.get(idxTipo));
                String localidade = sanitizarTexto(cols.get(idxLocalidade)).toUpperCase(Locale.ROOT);

                String cnpjBruto = cols.get(idxCnpj);
                String cnpjLimpo = sanitizarDocumento(cnpjBruto);
                
                if (cnpjLimpo.isEmpty() && localidadeNormalizada(localidade).contains("BRAGANC")) {
                    cnpjLimpo = "46352746000165";
                }

                String valEmpenhado = normalizarValorMonetarioSeguro(cols.get(idxEmpenhado));
                String valPago = normalizarValorMonetarioSeguro(cols.get(idxPago));
                
                String funcaoApi = idxFuncao >= 0 ? sanitizarTexto(cols.get(idxFuncao)) : "";
                String subfuncaoApi = idxSubfuncao >= 0 ? sanitizarTexto(cols.get(idxSubfuncao)) : "";

                Emenda emenda = new Emenda();
                emenda.setCodigoEmenda(codigo);
                emenda.setAutor(autor);
                emenda.setNomeAutor(autor);
                emenda.setNaturezaDespesa(natureza);
                emenda.setTipoEmenda(tipo);
                emenda.setLocalidade(localidade);
                emenda.setCodigoFavorecido(cnpjLimpo.isEmpty() ? null : cnpjLimpo);
                emenda.setValorEmpenhado(valEmpenhado);
                emenda.setValorPago(valPago);
                
                if (!funcaoApi.isEmpty() && !"—".equals(funcaoApi)) {
                    emenda.setFuncao(funcaoApi);
                } else {
                    emenda.setFuncao(localidadeNormalizada(localidade).contains("BRAGANC")
                            ? "BRAGANCA_PAULISTA" : "INVESTIMENTO_SP");
                }
                
                if (!subfuncaoApi.isEmpty() && !"—".equals(subfuncaoApi)) {
                    emenda.setSubfuncao(subfuncaoApi);
                }
                
                emenda.setAno(ano);
                emenda.setFonteDados(formatoEstendido ? "CGU-CNPJ-" + escopo : "CGU-" + escopo);
                emenda.setFavorecidoInidoneo(false);

                emendasLote.add(emenda);

            } catch (Exception ex) {
                errosParse++;
                // ITIL/LGPD: Log estruturado mascarando dados sensíveis sem derrubar a thread
                log.warn("[Parser][Emendas] Falha na L{}: {}. Dados: {}", 
                         linhaNum, ex.getMessage(), mascararLGPD(resumirLinha(linha)));
            }
        }

        if (!emendasLote.isEmpty()) {
            emendaRepository.saveAll(emendasLote);
            // ITIL: Info level para consolidação de negócio
            log.info("[Parser][Emendas] Lote processado. {} emendas salvas.", emendasLote.size());
        }

        log.info("[Parser][Emendas] Resumo: {} linhas | {} salvas | {} ignoradas | {} s/ colunas | {} duplicadas | {} erros",
                linhas.length, emendasLote.size(), ignoradas, colunasInsuficientes, duplicadas, errosParse);

        return emendasLote.size();
    }

    // --- DIRETRIZES: ISO/OWASP E LGPD ---

    private String gerarCodigoFallback(int ano) {
        return "CGU-" + ano + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String sanitizarTexto(String entrada) {
        if (entrada == null) return "";
        // ISO/OWASP: Previne XSS e corrupção removendo tags HTML e caracteres de controle
        return entrada.trim().replaceAll("<[^>]*>", "").replaceAll("[\\x00-\\x1F]", "");
    }

    private String sanitizarDocumento(String doc) {
        if (doc == null) return "";
        return doc.replaceAll("[^0-9]", "");
    }

    private String normalizarValorMonetarioSeguro(String bruto) {
        if (bruto == null || bruto.isBlank()) return "0";
        try {
            String numerico = bruto.replaceAll("[^\\d,]", "").replace(",", ".");
            if (numerico.isEmpty()) return "0";
            Double.parseDouble(numerico); // Validação de cast fail-fast
            return numerico;
        } catch (NumberFormatException e) {
            log.warn("[Segurança] Cast monetário falhou para '{}'. Assumindo 0.", mascararLGPD(bruto));
            return "0";
        }
    }

    private String mascararLGPD(String dadoSensivel) {
        if (dadoSensivel == null) return "null";
        // Mascara CPFs/CNPJs para evitar vazamento de PII em logs
        return dadoSensivel.replaceAll("\\b(\\d{3})\\.(\\d{3})\\.(\\d{3})-(\\d{2})\\b", "***.$2.$3-**")
                           .replaceAll("\\b(\\d{2})\\.(\\d{3})\\.(\\d{3})/(\\d{4})-(\\d{2})\\b", "**.$2.$3/$4-**");
    }

    private static String col(String[] cols, int index) {
        if (index < 0 || index >= cols.length) {
            throw new IndexOutOfBoundsException(
                    "índice " + index + " fora do array (length=" + cols.length + ")");
        }
        return cols[index].trim();
    }

    private static boolean isVazioOuTraco(String valor) {
        if (valor == null || valor.isBlank()) {
            return true;
        }
        String t = valor.trim();
        return "-".equals(t)
                || "—".equals(t)
                || "–".equals(t)
                || "‑".equals(t)
                || "N/A".equalsIgnoreCase(t)
                || "NA".equalsIgnoreCase(t);
    }

    private static String normalizarValorMonetario(String bruto) {
        String numerico = bruto.replaceAll("[^\\d,]", "").replace(",", ".");
        return numerico.isEmpty() ? "0" : numerico;
    }

    private static String localidadeNormalizada(String localidade) {
        if (localidade == null) {
            return "";
        }
        return localidade
                .toUpperCase(Locale.ROOT)
                .replace("Á", "A")
                .replace("À", "A")
                .replace("Ã", "A")
                .replace("Â", "A")
                .replace("É", "E")
                .replace("Ê", "E")
                .replace("Í", "I")
                .replace("Ó", "O")
                .replace("Ô", "O")
                .replace("Õ", "O")
                .replace("Ú", "U")
                .replace("Ç", "C");
    }

    private static String resumirLinha(String linha) {
        if (linha == null) {
            return "";
        }
        return linha.length() <= 160 ? linha : linha.substring(0, 157) + "...";
    }

    public void diagnosticarTool() {
        try {
            String manual = mcpClient.callTool("help", Collections.singletonMap("tool", "govtrace_buscar_emendas"));
            log.info("[DEBUG_MANUAL]: {}", manual);
        } catch (Exception e) {
            log.error("Erro ao rodar diagnóstico: {}", e.getMessage());
        }
    }

    public void verificarInidôneosTCU() {
        mcpClient.resetSession();
        try {
            String texto = mcpClient.callTool("tcu_consultar_inidoneos", new HashMap<>());
            Set<String> inidoneos = extrairNomesLista(texto);
            List<Emenda> emendas = emendaRepository.findAll();
            int marcados = 0;
            for (Emenda e : emendas) {
                if (e.getAutor() == null) continue;
                String autorUpper = e.getAutor().toUpperCase();
                for (String inidoneo : inidoneos) {
                    if (autorUpper.contains(inidoneo)) {
                        e.setFavorecidoInidoneo(true);
                        emendaRepository.save(e);
                        marcados++;
                        break;
                    }
                }
            }
            log.info("[TCU] Verificação concluída. {} emendas marcadas.", marcados);
        } catch (Exception e) {
            log.error("[TCU] Erro: {}", e.getMessage());
        }
    }

    private Set<String> extrairNomesLista(String texto) {
        Set<String> nomes = new HashSet<>();
        Pattern tabela = Pattern.compile("\\|\\s*([A-ZÁÉÍÓÚÀÃÕÂÊÔÇ][A-ZÁÉÍÓÚÀÃÕÂÊÔÇ\\s]{5,60})\\s*\\|");
        Matcher m = tabela.matcher(texto);
        while (m.find()) {
            String nome = m.group(1).trim().toUpperCase();
            if (!nome.startsWith("---") && !nome.equals("NOME") && !nome.equals("EMPRESA")) {
                nomes.add(nome);
            }
        }
        return nomes;
    }
}