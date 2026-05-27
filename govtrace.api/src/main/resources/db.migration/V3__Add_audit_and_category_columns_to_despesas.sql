ALTER TABLE tb_despesas
ADD COLUMN categoria VARCHAR(100) AFTER documento_origem,
ADD COLUMN metodo_cruzamento VARCHAR(100) AFTER categoria,
ADD COLUMN nexo_causal_confirmado BOOLEAN DEFAULT FALSE AFTER metodo_cruzamento;

CREATE INDEX idx_despesa_favorecido ON tb_despesas(nome_favorecido);
CREATE INDEX idx_emenda_autor ON tb_emendas(autor);