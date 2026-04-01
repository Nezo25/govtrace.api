package tfs.com.govtrace.api.models;

import jakarta.persistence.*;
import lombok.*;
import com.fasterxml.jackson.annotation.JsonProperty;

@Entity
@Table(name = "tb_emendas")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Emenda {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonProperty("codigoEmenda")
    @Column(unique = true, length = 20)
    private String codigoEmenda;

    @JsonProperty("ano")
    private Integer ano;

    @JsonProperty("tipoEmenda")
    private String tipoEmenda;

    @JsonProperty("autor")
    private String autor;

    @JsonProperty("nomeAutor")
    private String nomeAutor;

    @JsonProperty("localidadeDoGasto")
    private String localidade;

    @JsonProperty("funcao")
    private String funcao;

    @JsonProperty("subfuncao")
    private String subfuncao;

    @JsonProperty("valorEmpenhado")
    private String valorEmpenhado; // Recebemos String devido à formatação brasileira (vírgula)

    @JsonProperty("valorPago")
    private String valorPago;
}