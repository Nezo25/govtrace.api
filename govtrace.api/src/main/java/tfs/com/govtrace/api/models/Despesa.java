package tfs.com.govtrace.api.models;

import jakarta.persistence.*;
import lombok.*;
import com.fasterxml.jackson.annotation.JsonProperty;

@Entity
@Table(name = "tb_despesas")
@Builder
@NoArgsConstructor
@AllArgsConstructor
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

    @Builder.Default
    @Column(name = "nexo_causal_confirmado")
    private boolean nexoCausalConfirmado = false;

    // ==========================================
    // GETTERS E SETTERS EXPLÍCITOS (Blindagem)
    // ==========================================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getMunicipio() { return municipio; }
    public void setMunicipio(String municipio) { this.municipio = municipio; }

    public String getCodigoEmenda() { return codigoEmenda; }
    public void setCodigoEmenda(String codigoEmenda) { this.codigoEmenda = codigoEmenda; }

    public String getNomeFavorecido() { return nomeFavorecido; }
    public void setNomeFavorecido(String nomeFavorecido) { this.nomeFavorecido = nomeFavorecido; }

    public String getCnpjFavorecido() { return cnpjFavorecido; }
    public void setCnpjFavorecido(String cnpjFavorecido) { this.cnpjFavorecido = cnpjFavorecido; }

    public String getValorPago() { return valorPago; }
    public void setValorPago(String valorPago) { this.valorPago = valorPago; }

    public String getDataPagamento() { return dataPagamento; }
    public void setDataPagamento(String dataPagamento) { this.dataPagamento = dataPagamento; }

    public String getDocumentoOrigem() { return documentoOrigem; }
    public void setDocumentoOrigem(String documentoOrigem) { this.documentoOrigem = documentoOrigem; }

    public Emenda getEmenda() { return emenda; }
    public void setEmenda(Emenda emenda) { this.emenda = emenda; }

    public String getVereditoIA() { return vereditoIA; }
    public void setVereditoIA(String vereditoIA) { this.vereditoIA = vereditoIA; }

    public Integer getScoreRisco() { return scoreRisco; }
    public void setScoreRisco(Integer scoreRisco) { this.scoreRisco = scoreRisco; }

    public String getCategoria() { return categoria; }
    public void setCategoria(String categoria) { this.categoria = categoria; }

    public String getMetodoCruzamento() { return metodoCruzamento; }
    public void setMetodoCruzamento(String metodoCruzamento) { this.metodoCruzamento = metodoCruzamento; }

    public boolean isNexoCausalConfirmado() { return nexoCausalConfirmado; }
    public void setNexoCausalConfirmado(boolean nexoCausalConfirmado) { this.nexoCausalConfirmado = nexoCausalConfirmado; }

}