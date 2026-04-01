package tfs.com.govtrace.api.services;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tfs.com.govtrace.api.controllers.PortalTransparenciaClient;
import tfs.com.govtrace.api.dtos.DespesaDTO;
import tfs.com.govtrace.api.models.Despesa;
import tfs.com.govtrace.api.repositories.DespesaRepository;

import java.util.List;
import java.util.Map;

@Service
public class AuditoriaService {

    @Autowired
    private PortalTransparenciaClient client;

    @Autowired
    private DespesaRepository repository;

    /**
     *
     * MÉTODO 1: Busca por código de emenda
     *
     **/
    public String executarAuditoria(String codigoEmenda) {
        try {
            // CORREÇÃO: Agora o client só recebe codigo e pagina
            List<DespesaDTO> dtos = client.buscarDespesasPorEmenda(codigoEmenda, 1);

            if (dtos == null || dtos.isEmpty()) return "Nenhuma despesa encontrada.";

            for (DespesaDTO dto : dtos) {
                Despesa despesa = Despesa.builder()
                        .codigoEmenda(dto.getCodigoEmenda())
                        .nomeFavorecido(dto.getNomeFavorecido())
                        .cnpjFavorecido(dto.getCnpjFavorecido())
                        .valorPago(dto.getValorPago())
                        .dataPagamento(dto.getDataPagamento())
                        .vereditoIA("ANALISE PENDENTE...")
                        .build();
                repository.save(despesa);
            }
            return "Sucesso! " + dtos.size() + " registros salvos.";
        } catch (Exception e) {
            return "Erro: " + e.getMessage();
        }
    }

    /**
     *
     * MÉTODO 2: Investigar Irregularidades (Triagem por Mês)
     *
     **/
    public String auditarRecursosPorMes(String mesAno) {
        try {
            String dataFormatada = mesAno.trim();
            System.out.println("Iniciando triagem para " + dataFormatada);

            // CORREÇÃO: Removido TOKEN e UA. O Interceptor cuida disso.
            List<Map<String, Object>> dadosBrutos = client.buscarRecursosPorFavorecido(
                    dataFormatada, dataFormatada, 1
            );

            if (dadosBrutos == null || dadosBrutos.isEmpty()) {
                return "Aviso: Nenhum dado encontrado para " + dataFormatada;
            }

            int processados = 0;
            for (Map<String, Object> item : dadosBrutos) {
                String nome = item.get("nomePessoa") != null ? String.valueOf(item.get("nomePessoa")) : "NOME NÃO INFORMADO";
                String cnpj = item.get("codigoPessoa") != null ? String.valueOf(item.get("codigoPessoa")) : "00000000000";
                String valorStr = item.get("valor") != null ? String.valueOf(item.get("valor")) : "0.00";

                String veredito = "BAIXO RISCO: Analisado pelo sistema.";

                if (String.valueOf(item.get("tipoPessoa")).equalsIgnoreCase("Pessoa Física")) {
                    veredito = "ALTA PRIORIDADE: Pessoa Física detectada (CPF mascarado).";
                }

                Despesa despesa = Despesa.builder()
                        .nomeFavorecido(nome)
                        .cnpjFavorecido(cnpj)
                        .valorPago(valorStr)
                        .dataPagamento(dataFormatada)
                        .vereditoIA(veredito)
                        .build();

                repository.save(despesa);
                processados++;
            }

            return "Auditoria concluída! " + processados + " registros salvos no MySQL.";

        } catch (Exception e) {
            System.err.println("Erro detalhado: " + e.getMessage());
            return "Erro na auditoria: " + e.getMessage();
        }
    }
}