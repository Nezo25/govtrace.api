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

@Service
public class EmendaService {

    private static final Logger log = LoggerFactory.getLogger(EmendaService.class);

    private final McpBrasilClient mcpClient;
    private final EmendaRepository emendaRepository;
    private final DespesaRepository despesaRepository;
    private final TransactionTemplate transactionTemplate;

    @Value("${govtrace.emendas.limite-carga:10000}")
    private int limiteCargaEmendas;

    @Value("${govtrace.emendas.max-paginas-busca:1000}")
    private int maxPaginasBusca;

    @Value("${govtrace.emendas.codigo-ibge:3507605}")
    private int codigoIbgePadrao;

    public EmendaService(McpBrasilClient mcpClient, EmendaRepository emendaRepository,
                         DespesaRepository despesaRepository, TransactionTemplate transactionTemplate) {
        this.mcpClient = mcpClient;
        this.emendaRepository = emendaRepository;
        this.despesaRepository = despesaRepository;
        this.transactionTemplate = transactionTemplate;
    }

    public void carregarEmendasMunicipio(String nomeMunicipio, int ano) {
        carregarEmendasMunicipio(nomeMunicipio, ano, "ESTADO");
    }

    public void carregarEmendasMunicipio(String nomeMunicipio, int ano, String escopo) {
        String escopoEfetivo = (escopo != null && !escopo.isBlank()) ? escopo.toUpperCase() : "ESTADO";
        log.info("[Emendas] Iniciando carga massiva | {} | {} | Escopo: {}", nomeMunicipio, ano, escopoEfetivo);
        executarCarga(nomeMunicipio, ano, escopoEfetivo);
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

    // =====================================================
    // PARSER CORRIGIDO - LÊ EXATAMENTE AS 7 COLUNAS DO PYTHON
    // =====================================================
    private int parsearEmendasCGU(String texto, String municipioAlvo, int ano, String escopo) {
        if (texto == null || texto.isBlank()) return 0;

        List<Emenda> emendasLote = new ArrayList<>();
        String[] linhas = texto.split("\\r?\\n");

        for (String linha : linhas) {
            // Se a linha não começa com "|" ou é o cabeçalho (que contém --- ou a palavra Número), ignora.
            if (linha.trim().isEmpty() || !linha.trim().startsWith("|") || linha.contains("---") || linha.contains("Número")) {
                continue;
            }

            // Quebra nas barras verticais do Markdown
            // O split("\\|") em uma string como "| A | B | C |" gera um array onde o índice 0 é vazio.
            // Os dados úteis começam no índice 1.
            String[] cols = linha.split("\\|");

            // Como a tabela tem 7 colunas visuais, o array resultante do split terá pelo menos 8 posições.
            if (cols.length >= 8) {
                try {
                    String codigo = cols[1].trim();
                    if (codigo.isEmpty() || codigo.equals("—")) {
                        // Gera um UUID único para caso o Python não mande o número (para não bater duplicado com nulo)
                        codigo = "CGU-" + ano + "-" + UUID.randomUUID().toString().substring(0,8);
                    }

                    if (emendaRepository.existsByCodigoEmenda(codigo)) continue;

                    Emenda emenda = new Emenda();
                    emenda.setCodigoEmenda(codigo);
                    emenda.setAutor(cols[2].trim().toUpperCase());
                    emenda.setNomeAutor(cols[2].trim().toUpperCase());
                    emenda.setTipoEmenda(cols[3].trim());

                    String localidade = cols[4].trim().toUpperCase();
                    emenda.setLocalidade(localidade);

                    // Coluna CNPJ Favorecido (O 5º elemento útil, logo índice 5)
                    String cnpjSujo = cols[5].trim();
                    String cnpjLimpo = cnpjSujo.replaceAll("[^0-9]", "");
                    // Se não encontrou número, salva nulo (ou a String da prefeitura por fallback se for o caso)
                    if(cnpjLimpo.isEmpty() && localidade.contains("BRAGANC")) {
                        cnpjLimpo = "46352746000165";
                    }
                    emenda.setCodigoFavorecido(cnpjLimpo.isEmpty() ? null : cnpjLimpo);

                    // Valor Empenhado (Índice 6) e Valor Pago (Índice 7)
                    String valEmpenhadoStr = cols[6].trim().replaceAll("[^\\d,]", "").replace(",", ".");
                    String valPagoStr = cols[7].trim().replaceAll("[^\\d,]", "").replace(",", ".");

                    emenda.setValorEmpenhado(valEmpenhadoStr.isEmpty() ? "0" : valEmpenhadoStr);
                    emenda.setValorPago(valPagoStr.isEmpty() ? "0" : valPagoStr);

                    emenda.setFuncao(localidade.contains("BRAGANC") ? "BRAGANCA_PAULISTA" : "INVESTIMENTO_SP");
                    emenda.setAno(ano);
                    emenda.setFonteDados("CGU-" + escopo);
                    emenda.setFavorecidoInidoneo(false);

                    emendasLote.add(emenda);
                } catch (Exception ex) {
                    log.warn("[Parser] Ignorando linha mal formatada: {} | Erro: {}", linha, ex.getMessage());
                }
            }
        }

        if (!emendasLote.isEmpty()) {
            emendaRepository.saveAll(emendasLote);
        }
        return emendasLote.size();
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