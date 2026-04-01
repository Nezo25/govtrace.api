package tfs.com.govtrace.api.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import tfs.com.govtrace.api.models.Emenda;

import java.util.List;
import java.util.Optional;

@Repository
public interface EmendaRepository extends JpaRepository<Emenda, Long> {

    // Busca emendas de um autor específico (ex: "CHICO ALENCAR")
    List<Emenda> findByNomeAutorContainingIgnoreCase(String nomeAutor);

    // Busca emendas por localidade (ex: "SÃO PAULO (UF)")
    List<Emenda> findByLocalidade(String localidade);

    // Busca emendas por função (ex: "Saúde", "Educação")
    List<Emenda> findByFuncao(String funcao);

    // Busca uma emenda pelo código único do governo
    Optional<Emenda> findByCodigoEmenda(String codigoEmenda);
}