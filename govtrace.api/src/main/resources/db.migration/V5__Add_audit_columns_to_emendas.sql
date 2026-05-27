ALTER TABLE tb_emendas
    ADD COLUMN IF NOT EXISTS veredito_ia TEXT,
    ADD COLUMN IF NOT EXISTS score_risco INT;
