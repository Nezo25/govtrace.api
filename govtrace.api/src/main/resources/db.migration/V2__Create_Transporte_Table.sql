CREATE TABLE tb_transporte_auditoria (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    placa_veiculo VARCHAR(20),
    modelo VARCHAR(100),
    data_abastecimento DATE,
    litros DOUBLE,
    valor_total DOUBLE,
    km_atual INT,
    combustivel VARCHAR(50)
);