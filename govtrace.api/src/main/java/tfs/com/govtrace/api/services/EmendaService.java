package tfs.com.govtrace.api.services;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tfs.com.govtrace.api.models.Emenda;
import tfs.com.govtrace.api.repositories.DespesaRepository;
import tfs.com.govtrace.api.repositories.EmendaRepository;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serviço responsável por carregar e enriquecer emendas parlamentares.
 *
 * FONTES:
 * 1. Portal da Transparência / CGU (transparencia_buscar_emendas) — emendas reais repassadas ao município
 * 2. TCU Inidôneos (tcu_consultar_inidoneos) — empresas punidas para cruzamento de risco
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EmendaService {

    private final McpBrasilClient mcpClient;
    private final EmendaRepository emendaRepository;
    private final DespesaRepository despesaRepository;
    private final TransactionTemplate transactionTemplate;

    @Value("${govtrace.emendas.limite-carga:1200}")
    private int limiteCargaEmendas;

    @Value("${govtrace.emendas.escopo:ESTADO}")
    private String escopoEmendas;

    @Value("${govtrace.emendas.uf:SP}")
    private String ufEmendas;

    @Value("${govtrace.emendas.codigo-ibge:3507605}")
    private int codigoIbgePadrao;

    @Value("${govtrace.emendas.priorizar-municipio:true}")
    private boolean priorizarMunicipio;

    @Value("${govtrace.emendas.max-paginas-busca:300}")
    private int maxPaginasBusca;

    // =====================================================
    // 1. CARGA VIA PORTAL DA TRANSPARÊNCIA (CGU)
    // =====================================================

    public void carregarEmendasMunicipio(String nomeMunicipio, int ano) {
        carregarEmendasMunicipio(nomeMunicipio, ano, escopoEmendas);
    }

    public void carregarEmendasMunicipio(String nomeMunicipio, int ano, String escopo) {
        int alvo = calcularQuantidadeAlvo();
        String escopoEfetivo = (escopo != null && !escopo.isBlank()) ? escopo.toUpperCase() : escopoEmendas;
        log.info("[Emendas] Carga {} / {} — alvo {} | escopo={} | UF={} | prioriza Bragança={} | maxPaginas={}",
                nomeMunicipio, ano, alvo, escopoEfetivo, ufEmendas, priorizarMunicipio, maxPaginasBusca);
        log.info("[Emendas] A busca no Portal da Transparência pode levar 5–30 min. Veja o terminal do run_mcp.py.");

        mcpClient.resetSession();

        try {
            Map<String, Object> args = new HashMap<>();
            args.put("ano", ano);
            args.put("municipio", nomeMunicipio);
            args.put("codigo_ibge", codigoIbgePadrao);
            args.put("buscar_todas_paginas", true);
            args.put("limite_registros", alvo);
            args.put("escopo", escopoEfetivo);
            args.put("priorizar_municipio", priorizarMunicipio);
            args.put("max_paginas", maxPaginasBusca);

            String texto = mcpClient.callTool("govtrace_buscar_emendas", args);

            if (texto != null && texto.contains("|")) {
                transactionTemplate.executeWithoutResult(status -> {
                    int salvas = parsearEmendasCGU(texto, nomeMunicipio, ano, escopoEfetivo, alvo);
                    log.info("[Emendas] {} emendas persistidas (alvo={}, escopo={}).",
                            salvas, alvo, escopoEfetivo);
                });
            } else {
                log.warn("[Emendas] Resposta MCP sem tabela. Trecho: {}",
                        texto != null ? texto.substring(0, Math.min(300, texto.length())) : "null");
                log.warn("[Emendas] Confira: (1) python run_mcp.py ativo (2) TRANSPARENCIA_API_KEY definida");
            }
        } catch (Exception e) {
            log.error("[Emendas] Falha na carga", e);
        }
    }

    /** Alinha volume de emendas ao de despesas, com teto configurável (padrão 1200). */
    private int calcularQuantidadeAlvo() {
        long totalDespesas = despesaRepository.count();
        if (totalDespesas <= 0) {
            return limiteCargaEmendas;
        }
        return (int) Math.min(limiteCargaEmendas, totalDespesas);
    }

    public void diagnosticarTool() throws Exception {
        // Isso vai listar as ferramentas e, se o seu cliente MCP for bem feito,
        // ele vai exibir a descrição técnica que define se a paginação é suportada
        String manual = mcpClient.callTool("help", Collections.singletonMap("tool", "transparencia_buscar_emendas"));
        log.info("[DEBUG_MANUAL]: {}", manual);
    }

    private String extrairCodigoIbge(String texto) {
        // Regex para buscar o código de 7 dígitos que o IBGE costuma retornar
        Pattern p = Pattern.compile("(\\d{7})");
        Matcher m = p.matcher(texto);
        if (m.find()) return m.group(1);
        throw new RuntimeException("Não foi possível encontrar o código IBGE para o município informado.");
    }

    private int parsearEmendasCGU(
            String texto,
            String municipioFiltro,
            int ano,
            String escopo,
            int limiteSalvar) {

        String municipioAlvo = municipioFiltro.replace("-", " ").toUpperCase().trim();
        List<Emenda> emendasLote = new ArrayList<>();

        String[] linhas = texto.split("\\r?\\n");
        log.info("[Parser CGU] Analisando linhas (limite={}, escopo={})...", limiteSalvar, escopo);

        for (String linha : linhas) {
            if (emendasLote.size() >= limiteSalvar) break;
            // Ignora cabeçalhos da tabela
            if (linha.contains("---") || linha.contains("Número")) continue;

            // Divide a linha pelas barras da tabela Markdown
            String[] colunas = linha.split("\\|");

            // Uma linha de tabela válida tem várias colunas.
            // Ex: | ID | Autor | Tipo | Localidade | Valor |
            if (colunas.length >= 6) {
                try {
                    String numero = colunas[1].trim();
                    String autor = colunas[2].trim().toUpperCase();
                    String tipo = colunas[3].trim();
                    String localidade = colunas[4].trim().toUpperCase();
                    String valorPago = colunas[6].trim().replaceAll("[^\\d.,]", "");
                    String valorEmpenhado = colunas[5].trim().replaceAll("[^\\d.,]", "");

                    if (!localidadeAtendeEscopo(localidade, municipioAlvo, escopo)) continue;

                    String codigo = numero.isBlank() || "—".equals(numero)
                            ? "CGU-" + ano + "-" + UUID.randomUUID().toString().substring(0, 8)
                            : numero;

                    if (emendaRepository.existsByCodigoEmenda(codigo)) continue;

                    boolean emBraganca = localidadeAtendeEscopo(localidade, municipioAlvo, "MUNICIPIO");

                    emendasLote.add(Emenda.builder()
                            .codigoEmenda(codigo)
                            .autor(autor)
                            .nomeAutor(autor)
                            .tipoEmenda(tipo)
                            .localidade(localidade)
                            .uf(ufEmendas)
                            .valorPago(valorPago.isBlank() ? valorEmpenhado : valorPago)
                            .valorEmpenhado(valorEmpenhado.isBlank() ? valorPago : valorEmpenhado)
                            .funcao(emBraganca ? "BRAGANCA_PAULISTA" : "INVESTIMENTO_SP")
                            .ano(ano)
                            .fonteDados("CGU-" + escopo)
                            .favorecidoInidoneo(false)
                            .build());
                    log.info("[Parser CGU] Emenda capturada: {} | Valor: {}", autor, valorPago);
                } catch (Exception ex) {
                    // Ignora linhas que não são dados (como rodapés)
                }
            }
        }

        if (!emendasLote.isEmpty()) {
            emendaRepository.saveAll(emendasLote);
            log.info("[Emendas] Sucesso! {} emendas salvas.", emendasLote.size());
            return emendasLote.size();
        }
        log.warn("[Emendas] Nenhuma emenda parseada. Confira MCP (run_mcp.py) e TRANSPARENCIA_API_KEY.");
        return 0;
    }
    // =====================================================
    // 2. VERIFICAÇÃO TCU — INIDÔNEOS
    // =====================================================

    public void verificarInidôneosTCU() {
        log.info("[TCU] Iniciando verificação de inidôneos...");
        mcpClient.resetSession();

        try {
            String texto = mcpClient.callTool("tcu_consultar_inidoneos", new HashMap<>());
            Set<String> inidoneos = extrairNomesLista(texto);

            log.info("[TCU] {} empresas inidôneas encontradas.", inidoneos.size());

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
                        log.warn("[TCU] ALERTA DE INIDÔNEO: {} matched {}", e.getAutor(), inidoneo);
                        break;
                    }
                }
            }
            log.info("[TCU] Verificação concluída. {} emendas marcadas como suspeitas.", marcados);

        } catch (Exception e) {
            log.error("[TCU] Erro ao verificar inidôneos: {}", e.getMessage());
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

    private boolean localidadeAtendeEscopo(String localidade, String municipioAlvo, String escopo) {
        if (escopo == null || escopo.isBlank() || "FEDERAL".equalsIgnoreCase(escopo)) {
            return true;
        }
        String loc = localidade.toUpperCase();
        if ("MUNICIPIO".equalsIgnoreCase(escopo)) {
            return loc.contains("BRAGANCA") || loc.contains("BRAGANÇA")
                    || (!municipioAlvo.isEmpty() && loc.contains(municipioAlvo));
        }
        if ("ESTADO".equalsIgnoreCase(escopo)) {
            return loc.contains("SAO PAULO") || loc.contains("SÃO PAULO")
                    || loc.contains("BRAGANCA") || loc.contains("BRAGANÇA")
                    || loc.contains("/SP") || loc.contains(" SP")
                    || (!municipioAlvo.isEmpty() && loc.contains(municipioAlvo));
        }
        return true;
    }
}