package tfs.com.govtrace.api.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import tfs.com.govtrace.api.models.Emenda;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmendaRepository extends JpaRepository<Emenda, Long> {

    // Busca por autor — usado no cruzamento com favorecido da despesa
    List<Emenda> findByAutorContainingIgnoreCase(String autor);

    // Busca por função — Saúde, Educação, Transporte
    List<Emenda> findByFuncaoIgnoreCase(String funcao);

    // Busca por partido
    List<Emenda> findByPartidoIgnoreCase(String partido);

    // Busca por UF
    List<Emenda> findByUfIgnoreCase(String uf);

    // Busca por código único
    Optional<Emenda> findByCodigoEmenda(String codigoEmenda);

    // Emendas com favorecido inidôneo (cruzamento TCU)
    List<Emenda> findByFavorecidoInidoneoTrue();

    // Emendas de um ano específico
    List<Emenda> findByAno(Integer ano);

    // Emendas por fonte de dados
    List<Emenda> findByFonteDados(String fonteDados);

    // Verifica duplicata antes de salvar
    boolean existsByCodigoEmenda(String codigoEmenda);

    // Total por função — para o dashboard
    @Query("SELECT e.funcao, COUNT(e) FROM Emenda e GROUP BY e.funcao ORDER BY COUNT(e) DESC")
    List<Object[]> contarPorFuncao();

    // Emendas com valor acima de threshold — para análise de risco
    @Query("SELECT e FROM Emenda e WHERE " +
            "CAST(REPLACE(REPLACE(e.valorPago, '.', ''), ',', '.') AS double) > :threshold " +
            "ORDER BY CAST(REPLACE(REPLACE(e.valorPago, '.', ''), ',', '.') AS double) DESC")
    List<Emenda> findEmendasAcimaDeValor(double threshold);

    @Query("SELECT e FROM Emenda e WHERE e.vereditoIA IS NULL OR e.vereditoIA = ''")
    List<Emenda> findPendentesDeAuditoria();
    @Modifying
    @Query("UPDATE Emenda e SET e.vereditoIA = null, e.scoreRisco = null")
    void resetarVereditos();
}