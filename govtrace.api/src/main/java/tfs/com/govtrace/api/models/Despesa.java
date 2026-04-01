package tfs.com.govtrace.api.models;

import jakarta.persistence.*;
import lombok.*;
import com.fasterxml.jackson.annotation.JsonProperty;

@Entity
@Table(name = "tb_despesas")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Despesa {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonProperty("codigoEmenda")
    private String codigoEmenda;

    @JsonProperty("nomeFavorecido")
    private String nomeFavorecido;

    @JsonProperty("cnpjFavorecido")
    @Column(length = 50)
    private String cnpjFavorecido;

    @JsonProperty("valorPago")
    private String valorPago;

    @JsonProperty("dataPagamento")
    private String dataPagamento;

    @JsonProperty("documentoOrigem")
    private String documentoOrigem;

    // Relacionamento opcional para facilitar buscas internas
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "emenda_id")
    private Emenda emenda;

    // Campo de auditoria para a IA
    @Column(columnDefinition = "TEXT")
    private String vereditoIA;

    private Integer scoreRisco; // 0 a 100
}