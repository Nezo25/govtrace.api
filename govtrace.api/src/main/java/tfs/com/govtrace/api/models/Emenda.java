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

    @Column(name = "codigo_emenda", unique = true, length = 50)
    @JsonProperty("codigoEmenda")
    private String codigoEmenda;

    @Column(name = "ano")
    private Integer ano;

    @Column(name = "tipo_emenda")
    @JsonProperty("tipoEmenda")
    private String tipoEmenda;

    // Autor da emenda (deputado/senador)
    @Column(name = "autor")
    @JsonProperty("autor")
    private String autor;

    @Column(name = "nome_autor")
    @JsonProperty("nomeAutor")
    private String nomeAutor;

    // Partido e UF — vindos da Câmara via despesas_deputado
    @Column(name = "partido")
    private String partido;

    @Column(name = "uf")
    private String uf;

    @Column(name = "localidade_do_gasto")
    @JsonProperty("localidadeDoGasto")
    private String localidade;

    // Função/área do gasto (ex: SAÚDE, EDUCAÇÃO, TRANSPORTE)
    @Column(name = "funcao")
    @JsonProperty("funcao")
    private String funcao;

    @Column(name = "subfuncao")
    @JsonProperty("subfuncao")
    private String subfuncao;

    // Valores
    @Column(name = "valor_empenhado")
    @JsonProperty("valorEmpenhado")
    private String valorEmpenhado;

    @Column(name = "valor_pago")
    @JsonProperty("valorPago")
    private String valorPago;

    // Fonte de onde veio o dado (CAMARA, DIARIO_OFICIAL, MANUAL)
    @Column(name = "fonte_dados")
    private String fonteDados;

    // ID externo — deputado_id da Câmara ou número do diário
    @Column(name = "id_externo")
    private String idExterno;

    // Flag: empresa favorecida está na lista de inidôneos do TCU?
    @Column(name = "favorecido_inidoneo")
    private Boolean favorecidoInidoneo = false;

    @Column(name = "veredito_ia", columnDefinition = "TEXT")
    private String vereditoIA;

    @Column(name = "score_risco")
    private Integer scoreRisco;
}