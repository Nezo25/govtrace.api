package tfs.com.govtrace.api.models;

import jakarta.persistence.*;
import lombok.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

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

    private String municipio;

    @JsonProperty("codigoEmenda")
    private String codigoEmenda;

    @JsonProperty("nomeFavorecido")
    private String nomeFavorecido;

    @JsonProperty("cnpjFavorecido")
    @Column(length = 50)
    private String cnpjFavorecido;

    @JsonProperty("valorPago")
    @Column(precision = 15, scale = 2) // Fundamental para o MySQL entender que é DINHEIRO ENZO DINHEIRO NAO TEXTOO
    private BigDecimal valorPago;

    @JsonProperty("dataPagamento")
    private String dataPagamento;

    @JsonProperty("documentoOrigem")
    @Column(unique = true)
    private String documentoOrigem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "emenda_id")
    private Emenda emenda;

    @Column(columnDefinition = "TEXT")
    private String vereditoIA;

    private Integer scoreRisco;
}