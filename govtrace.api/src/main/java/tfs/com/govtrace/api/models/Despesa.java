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

    @Column(name = "municipio")
    private String municipio;

    @JsonProperty("codigoEmenda")
    @Column(name = "codigo_emenda")
    private String codigoEmenda;

    @JsonProperty("nomeFavorecido")
    @Column(name = "nome_favorecido")
    private String nomeFavorecido;

    @JsonProperty("cnpjFavorecido")
    @Column(name = "cnpj_favorecido", length = 50)
    private String cnpjFavorecido;

    @JsonProperty("valorPago")
    @Column(name = "valor_pago")
    private String valorPago;

    @JsonProperty("dataPagamento")
    @Column(name = "data_pagamento")
    private String dataPagamento;

    @JsonProperty("documentoOrigem")
    @Column(name = "documento_origem", unique = true)
    private String documentoOrigem;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "emenda_id")
    private Emenda emenda;


    @Column(name = "veredito_ia", columnDefinition = "TEXT")
    private String vereditoIA;

    @Column(name = "score_risco")
    private Integer scoreRisco;

    @Column(name = "categoria")
    private String categoria;

    @Column(name = "metodo_cruzamento")
    private String metodoCruzamento;

    @Column(name = "nexo_causal_confirmado")
    private boolean nexoCausalConfirmado = false;
}