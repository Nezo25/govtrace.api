CREATE TABLE tb_emendas (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    codigo_emenda VARCHAR(20) UNIQUE,
    ano INT,
    tipo_emenda VARCHAR(100),
    autor VARCHAR(255),
    nome_autor VARCHAR(255),
    localidade_do_gasto VARCHAR(255),
    funcao VARCHAR(100),
    subfuncao VARCHAR(100),
    valor_empenhado VARCHAR(50),
    valor_pago VARCHAR(50)
);

CREATE TABLE tb_despesas (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    municipio VARCHAR(255),
    codigo_emenda VARCHAR(50),
    nome_favorecido VARCHAR(255),
    cnpj_favorecido VARCHAR(50),
    valor_pago VARCHAR(50),
    data_pagamento VARCHAR(20),
    documento_origem VARCHAR(100) UNIQUE,
    veredito_ia TEXT,
    score_risco INT,
    emenda_id BIGINT,
    FOREIGN KEY (emenda_id) REFERENCES tb_emendas(id)
);