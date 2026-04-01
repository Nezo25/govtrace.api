package tfs.com.govtrace.api.services;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import tfs.com.govtrace.api.controllers.PortalTransparenciaClient;
import tfs.com.govtrace.api.models.Despesa;
import tfs.com.govtrace.api.repositories.DespesaRepository;
import java.util.List;
import java.util.Map;

@Service
public class CargaDadosService {

    @Autowired
    private DespesaRepository repository;

    @Autowired
    private PortalTransparenciaClient portalClient;

    public String carregarMassaDados(int qtdPaginas) {
        int totalInserido = 0;

        for (int i = 1; i <= qtdPaginas; i++) {
            try {
                // Chamada com o timestamp para "furar" o cache de erro do CloudFront
                // Isso gera uma URL única a cada tentativa: &t=17429...
                List<Map<String, Object>> lista = portalClient.buscarDespesasPorEmendaManual(
                        "202441290003",
                        i,
                        System.currentTimeMillis()
                );

                if (lista != null && !lista.isEmpty()) {
                    for (Map<String, Object> item : lista) {
                        Despesa d = new Despesa();

                        // Mapeamento defensivo: o portal costuma retornar nomes de campos variados
                        d.setCodigoEmenda(String.valueOf(item.getOrDefault("codigoEmenda", "202441290003")));
                        d.setNomeFavorecido(String.valueOf(item.getOrDefault("nomeFavorecido", "N/A")));
                        d.setCnpjFavorecido(String.valueOf(item.getOrDefault("cnpjFavorecido", "N/A")));
                        d.setValorPago(String.valueOf(item.getOrDefault("valorPago", "0")));
                        d.setDataPagamento(String.valueOf(item.getOrDefault("dataPagamento", "")));
                        d.setDocumentoOrigem(String.valueOf(item.getOrDefault("documento", "")));

                        repository.save(d);
                        totalInserido++;
                    }
                    System.out.println("Página " + i + " salva com sucesso. Total acumulado: " + totalInserido);
                } else {
                    System.out.println("Página " + i + " retornou vazia ou nula.");
                }

                // O delay de 5 segundos é vital para manter a saúde do seu IP
                Thread.sleep(5000);

            } catch (Exception e) {
                System.err.println("Falha crítica na página " + i + ": " + e.getMessage());

                // Se o erro for 403 (mesmo com o cache buster), o IP realmente precisa de um "descanso"
                if (e.getMessage().contains("403")) {
                    System.err.println("Bloqueio persistente detectado (403). Abortando carga para preservar IP.");
                    break;
                }
            }
        }
        return "Carga finalizada. Total de novos registros: " + totalInserido;
    }
}