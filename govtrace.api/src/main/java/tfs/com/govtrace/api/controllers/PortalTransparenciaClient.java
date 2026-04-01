package tfs.com.govtrace.api.controllers;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import tfs.com.govtrace.api.config.FeignConfig;
import tfs.com.govtrace.api.dtos.DespesaDTO;
import java.util.List;
import java.util.Map;

@FeignClient(name = "portalTransparencia",
        url = "https://api.portaldatransparencia.gov.br/api-de-dados",
        configuration = FeignConfig.class) // Vincula sua config aqui
public interface PortalTransparenciaClient {

    @GetMapping("/emendas/despesas-detalhadas")
    List<Map<String, Object>> buscarDespesasPorEmendaManual(
            @RequestParam("codigoEmenda") String codigoEmenda,
            @RequestParam("pagina") int pagina,
            @RequestParam("t") long timestamp
    );

    @GetMapping("/despesas/itens-de-empenho")
    List<Map<String, Object>> buscarItensDeEmpenho(@RequestParam("codigo") String codigoDocumento);

    @GetMapping("/licitacoes/participantes")
    List<Map<String, Object>> buscarParticipantesLicitacao(@RequestParam("id") String idLicitacao);

    @GetMapping("/despesas/por-emenda")
    List<DespesaDTO> buscarDespesasPorEmenda(
            @RequestParam("codigoEmenda") String codigoEmenda,
            @RequestParam("pagina") int pagina
    );

    @GetMapping("/despesas/recursos-recebidos")
    List<Map<String, Object>> buscarRecursosPorFavorecido(
            @RequestParam("mesAnoInicio") String mesAnoInicio,
            @RequestParam("mesAnoFim") String mesAnoFim,
            @RequestParam("pagina") int pagina
    );
}