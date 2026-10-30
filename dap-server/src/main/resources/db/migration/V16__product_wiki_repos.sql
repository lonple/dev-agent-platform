-- 产品绑定 Wiki 根节点 + 多代码仓库（流水线启动校验依赖）

ALTER TABLE sys_product
    ADD COLUMN IF NOT EXISTS wiki_node_id BIGINT;

COMMENT ON COLUMN sys_product.wiki_node_id IS '产品 Wiki 上下文根目录节点（sys_wiki_node.id，DIR）';

CREATE TABLE IF NOT EXISTS sys_product_code_repo (
    id          BIGSERIAL PRIMARY KEY,
    product_id  BIGINT        NOT NULL REFERENCES sys_product (id) ON DELETE CASCADE,
    local_path  VARCHAR(1000) NOT NULL,
    label       VARCHAR(128),
    sort_no     INT           NOT NULL DEFAULT 0,
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_spcr_product ON sys_product_code_repo (product_id, sort_no);
