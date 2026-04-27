package tfs.com.govtrace.api.models;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDate;

@Data
@Entity
@Table(name = "tb_transporte_auditoria")
public class TransporteAuditoria {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "placa_veiculo")
    private String placaVeiculo;

    private String modelo;

    @Column(name = "data_abastecimento")
    private LocalDate dataAbastecimento;

    private Double litros;

    @Column(name = "valor_total")
    private Double valorTotal;

    @Column(name = "km_atual")
    private Integer kmAtual;

    private String combustivel;
}