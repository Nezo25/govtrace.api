package tfs.com.govtrace.api.models;

import jakarta.persistence.*;
import lombok.*;
import com.fasterxml.jackson.annotation.JsonProperty;

@Entity
@Table(name = "tb_emendas")
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Emenda {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "codigo_emenda", unique = true, length = 50)
    @JsonProperty("codigoEmenda")
    private String codigoEmenda;

    @Column(name = "ano")
    private Integer ano;

    @Column(name = "tipo_emenda")
    @JsonProperty("tipoEmenda")
    private String tipoEmenda;

    @Column(name = "autor")
    @JsonProperty("autor")
    private String autor;

    @Column(name = "nome_autor")
    @JsonProperty("nomeAutor")
    private String nomeAutor;

    @Column(name = "partido")
    private String partido;

    @Column(name = "uf")
    private String uf;

    @Column(name = "localidade_do_gasto")
    @JsonProperty("localidadeDoGasto")
    private String localidade;

    // === NOVA COLUNA PARA O JOIN COM O TCE-SP ===
    @Column(name = "codigo_favorecido", length = 50)
    @JsonProperty("codigoFavorecido")
    private String codigoFavorecido;

    @Column(name = "funcao")
    @JsonProperty("funcao")
    private String funcao;

    @Column(name = "subfuncao")
    @JsonProperty("subfuncao")
    private String subfuncao;

    @Column(name = "valor_empenhado")
    @JsonProperty("valorEmpenhado")
    private String valorEmpenhado;

    @Column(name = "valor_pago")
    @JsonProperty("valorPago")
    private String valorPago;

    @Column(name = "fonte_dados")
    private String fonteDados;

    @Column(name = "id_externo")
    private String idExterno;

    @Builder.Default
    @Column(name = "favorecido_inidoneo")
    private Boolean favorecidoInidoneo = false;

    @Column(name = "veredito_ia", columnDefinition = "TEXT")
    private String vereditoIA;

    @Column(name = "score_risco")
    private Integer scoreRisco;

    // ==========================================
    // GETTERS E SETTERS EXPLÍCITOS (Blindagem)
    // ==========================================

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCodigoEmenda() { return codigoEmenda; }
    public void setCodigoEmenda(String codigoEmenda) { this.codigoEmenda = codigoEmenda; }

    public Integer getAno() { return ano; }
    public void setAno(Integer ano) { this.ano = ano; }

    public String getTipoEmenda() { return tipoEmenda; }
    public void setTipoEmenda(String tipoEmenda) { this.tipoEmenda = tipoEmenda; }

    public String getAutor() { return autor; }
    public void setAutor(String autor) { this.autor = autor; }

    public String getNomeAutor() { return nomeAutor; }
    public void setNomeAutor(String nomeAutor) { this.nomeAutor = nomeAutor; }

    public String getPartido() { return partido; }
    public void setPartido(String partido) { this.partido = partido; }

    public String getUf() { return uf; }
    public void setUf(String uf) { this.uf = uf; }

    public String getLocalidade() { return localidade; }
    public void setLocalidade(String localidade) { this.localidade = localidade; }

    public String getCodigoFavorecido() { return codigoFavorecido; }
    public void setCodigoFavorecido(String codigoFavorecido) { this.codigoFavorecido = codigoFavorecido; }

    public String getFuncao() { return funcao; }
    public void setFuncao(String funcao) { this.funcao = funcao; }

    public String getSubfuncao() { return subfuncao; }
    public void setSubfuncao(String subfuncao) { this.subfuncao = subfuncao; }

    public String getValorEmpenhado() { return valorEmpenhado; }
    public void setValorEmpenhado(String valorEmpenhado) { this.valorEmpenhado = valorEmpenhado; }

    public String getValorPago() { return valorPago; }
    public void setValorPago(String valorPago) { this.valorPago = valorPago; }

    public String getFonteDados() { return fonteDados; }
    public void setFonteDados(String fonteDados) { this.fonteDados = fonteDados; }

    public String getIdExterno() { return idExterno; }
    public void setIdExterno(String idExterno) { this.idExterno = idExterno; }

    public Boolean getFavorecidoInidoneo() { return favorecidoInidoneo; }
    public void setFavorecidoInidoneo(Boolean favorecidoInidoneo) { this.favorecidoInidoneo = favorecidoInidoneo; }

    public String getVereditoIA() { return vereditoIA; }
    public void setVereditoIA(String vereditoIA) { this.vereditoIA = vereditoIA; }

    public Integer getScoreRisco() { return scoreRisco; }
    public void setScoreRisco(Integer scoreRisco) { this.scoreRisco = scoreRisco; }
}