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

    /** Top 10 riscos recentes. */
    List<Despesa> findTop10ByOrderByScoreRiscoDesc();

    /** Impede duplicatas entre recargas de dados. */
    boolean existsByDocumentoOrigem(String documentoOrigem);

    /** Casos críticos: score acima do threshold. */
    @Query("SELECT d FROM Despesa d WHERE d.scoreRisco > :threshold ORDER BY d.scoreRisco DESC")
    List<Despesa> findCasosCriticos(int threshold);

    /** Pendentes de auditoria pela IA (apenas cruzadas). */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"emenda"})
    List<Despesa> findByMetodoCruzamentoIsNotNullAndVereditoIAIsNull();

    /** Todas as despesas que possuem cruzamento (para exportacao). */
    @org.springframework.data.jpa.repository.EntityGraph(attributePaths = {"emenda"})
    List<Despesa> findByMetodoCruzamentoIsNotNull();

    /** Pendentes de cruzamento de emendas e despesas */
    @Query("SELECT d FROM Despesa d WHERE d.emenda IS NULL")
    List<Despesa> findDespesasSemEmenda();

    // Métodos de Agregação para o Dashboard (Scale-out)
    long countByMetodoCruzamento(String metodoCruzamento);
    
    long countByEmendaIsNull();
    
    @Query("SELECT COUNT(d) FROM Despesa d WHERE d.scoreRisco > :threshold OR UPPER(d.vereditoIA) LIKE UPPER(CONCAT('%', :keyword, '%'))")
    long countByScoreRiscoGreaterThanOrVereditoIAContainsIgnoreCase(int threshold, String keyword);
    @Modifying
    @Query("UPDATE Despesa d SET d.emenda = null, d.nexoCausalConfirmado = false, d.metodoCruzamento = null, d.vereditoIA = null, d.scoreRisco = null")
    int resetarCruzamentos();
}