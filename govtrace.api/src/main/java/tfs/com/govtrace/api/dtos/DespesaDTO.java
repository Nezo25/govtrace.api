package tfs.com.govtrace.api.dtos;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    private String valorPago; // Recebido como String para tratar a vírgula brasileira

    @JsonProperty("dataPagamento")
    private String dataPagamento;

    @JsonProperty("documentoOrigem")
    private String documentoOrigem;

    @JsonProperty("faseDespesa")
    private String faseDespesa;

    @JsonProperty("ug")
    private String unidadeGestora;
}