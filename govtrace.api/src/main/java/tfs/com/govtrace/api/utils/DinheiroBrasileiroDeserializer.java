package tfs.com.govtrace.api.utils;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import java.io.IOException;
import java.math.BigDecimal;

public class DinheiroBrasileiroDeserializer extends JsonDeserializer<BigDecimal> {
    @Override
    public BigDecimal deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String valorString = p.getText();
        if (valorString == null || valorString.isBlank() || valorString.equals("N/A")) {
            return BigDecimal.ZERO;
        }

        // Remove os pontos de milhar e troca a vírgula decimal por ponto
        String valorLimpo = valorString.replace(".", "").replace(",", ".");

        try {
            return new BigDecimal(valorLimpo);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO; // Ou lance uma exceção dependendo da sua regra de negócio
        }
    }
}