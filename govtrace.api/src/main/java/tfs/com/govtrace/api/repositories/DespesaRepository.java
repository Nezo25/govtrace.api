package tfs.com.govtrace.api.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tfs.com.govtrace.api.models.Despesa;

import java.util.List;
import java.util.Optional;

@Repository
public interface DespesaRepository extends JpaRepository<Despesa, Long> {

    // Busca todas as despesas de uma emenda específica
    List<Despesa> findByCodigoEmenda(String codigoEmenda);

    // Busca por CNPJ para ver quanto uma empresa recebeu no total
    List<Despesa> findByCnpjFavorecido(String cnpjFavorecido);

    // Busca despesas com valor pago acima de um limite (para auditoria de risco)
    List<Despesa> findByValorPagoGreaterThan(String valorMinimo);

    // Verifica se uma despesa já foi importada para não duplicar no MySQL
    Optional<Despesa> findByDocumentoOrigem(String documentoOrigem);

    List<Despesa> findAllByOrderByScoreRiscoDesc();
}