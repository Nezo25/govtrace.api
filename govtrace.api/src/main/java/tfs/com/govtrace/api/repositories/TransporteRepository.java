package tfs.com.govtrace.api.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import tfs.com.govtrace.api.models.TransporteAuditoria;

import java.util.List;

@Repository
public interface TransporteRepository extends JpaRepository<TransporteAuditoria, Long> {

    /**
     * Query de Veracidade:
     * Busca abastecimentos no CSV que batem exatamente com o valor de uma despesa do MCP.
     * Isso prova para o Stein que o gasto de combustível é real e documentado.
     */
    @Query("SELECT t FROM TransporteAuditoria t WHERE t.valorTotal = :valorDespesa")
    List<TransporteAuditoria> findAbastecimentosVerificados(Double valorDespesa);

    /**
     * Busca por placa para auditoria de consumo por veículo.
     */
    List<TransporteAuditoria> findAllByPlacaVeiculo(String placa);
}