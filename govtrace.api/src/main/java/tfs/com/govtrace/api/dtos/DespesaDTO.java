package tfs.com.govtrace.api.dtos;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import tfs.com.govtrace.api.utils.DinheiroBrasileiroDeserializer;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DespesaDTO {

    @JsonProperty("codigoEmenda")
    private String codigoEmenda;

    @JsonProperty("nomeFavorecido")
    private String nomeFavorecido;

    @JsonProperty("cnpjFavorecido")
    private String cnpjFavorecido;

    @JsonProperty("valorPago")
    @JsonDeserialize(using = DinheiroBrasileiroDeserializer.class) //aqui a gente usa um util para tratar isso, basicamente neca
    //antes dele mandar pra neca do modal e virar uma coluna, a funcao que criamos la transformar em decimal certinho
    private BigDecimal valorPago; // Recebido como String para tratar a vírgula brasileira

    @JsonProperty("dataPagamento")
    private String dataPagamento;

    @JsonProperty("documentoOrigem")
    private String documentoOrigem;

    @JsonProperty("faseDespesa")
    private String faseDespesa;

    @JsonProperty("ug")
    private String unidadeGestora;
}