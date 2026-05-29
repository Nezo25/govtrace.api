package tfs.com.govtrace.api.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import tfs.com.govtrace.api.models.Despesa;

import java.util.List;

@Repository
public interface DespesaRepository extends JpaRepository<Despesa, Long> {

    /** Ranking decrescente de risco — usado pelo endpoint principal. */
    List<Despesa> findAllByOrderByScoreRiscoDesc();

    /** Impede duplicatas entre recargas de dados. */
    boolean existsByDocumentoOrigem(String documentoOrigem);

    /** Casos críticos: score acima do threshold. */
    @Query("SELECT d FROM Despesa d WHERE d.scoreRisco > :threshold ORDER BY d.scoreRisco DESC")
    List<Despesa> findCasosCriticos(int threshold);

    /** Pendentes de auditoria pela IA. */
    @Query("SELECT d FROM Despesa d WHERE d.vereditoIA IS NULL OR d.vereditoIA = ''")
    List<Despesa> findPendentesDeAuditoria();

    /** Pendentes de cruzamento de emendas e despesas */
    @Query("SELECT d FROM Despesa d WHERE d.emenda IS NULL")
    List<Despesa> findDespesasSemEmenda();
    @Modifying
    @Query("UPDATE Despesa d SET d.emenda = null, d.nexoCausalConfirmado = false, d.metodoCruzamento = null, d.vereditoIA = null, d.scoreRisco = null")
    int resetarCruzamentos();
}