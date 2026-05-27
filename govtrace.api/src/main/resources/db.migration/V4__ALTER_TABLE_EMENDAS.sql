

ALTER TABLE tb_emendas
    MODIFY COLUMN codigo_emenda VARCHAR(50),
    ADD COLUMN IF NOT EXISTS partido VARCHAR(20),
    ADD COLUMN IF NOT EXISTS uf VARCHAR(5),
    ADD COLUMN IF NOT EXISTS fonte_dados VARCHAR(30),
    ADD COLUMN IF NOT EXISTS id_externo VARCHAR(50),
    ADD COLUMN IF NOT EXISTS favorecido_inidoneo TINYINT(1) DEFAULT 0;

-- Índices para buscas frequentes no cruzamento
CREATE INDEX IF NOT EXISTS idx_emenda_autor ON tb_emendas(autor);
CREATE INDEX IF NOT EXISTS idx_emenda_funcao ON tb_emendas(funcao);
CREATE INDEX IF NOT EXISTS idx_emenda_uf ON tb_emendas(uf);
CREATE INDEX IF NOT EXISTS idx_emenda_inidoneo ON tb_emendas(favorecido_inidoneo);