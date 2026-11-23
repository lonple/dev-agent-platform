-- 问题单管理

CREATE TABLE iss_issue (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT       NOT NULL,
    issue_no         VARCHAR(64)  NOT NULL,
    product_id       BIGINT       NOT NULL,
    description      TEXT         NOT NULL,
    steps            TEXT         NOT NULL DEFAULT '',
    expected_result  TEXT         NOT NULL DEFAULT '',
    solution         TEXT         NOT NULL DEFAULT '',
    status           VARCHAR(32)  NOT NULL DEFAULT 'OPEN',
    handler_id       BIGINT,
    created_by       BIGINT       NOT NULL,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_iss_issue_no UNIQUE (issue_no),
    CONSTRAINT fk_iss_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_iss_product FOREIGN KEY (product_id) REFERENCES sys_product (id),
    CONSTRAINT fk_iss_handler FOREIGN KEY (handler_id) REFERENCES sys_user (id),
    CONSTRAINT fk_iss_creator FOREIGN KEY (created_by) REFERENCES sys_user (id)
);

CREATE INDEX idx_iss_issue_tenant ON iss_issue (tenant_id, created_at DESC);
CREATE INDEX idx_iss_issue_product ON iss_issue (product_id);
CREATE INDEX idx_iss_issue_status ON iss_issue (tenant_id, status);
CREATE INDEX idx_iss_issue_handler ON iss_issue (tenant_id, handler_id, status);

COMMENT ON TABLE iss_issue IS '问题单';
COMMENT ON COLUMN iss_issue.issue_no IS '问题单编码：产品编码-自增号';
COMMENT ON COLUMN iss_issue.steps IS '实现步骤（Markdown，可含图片）';
COMMENT ON COLUMN iss_issue.status IS 'OPEN=未处理 DONE=已处理';

CREATE TABLE iss_issue_no_seq (
    tenant_id   BIGINT NOT NULL,
    product_id  BIGINT NOT NULL,
    last_seq    INT    NOT NULL DEFAULT -1,
    PRIMARY KEY (tenant_id, product_id),
    CONSTRAINT fk_iss_no_seq_tenant FOREIGN KEY (tenant_id) REFERENCES sys_tenant (id),
    CONSTRAINT fk_iss_no_seq_product FOREIGN KEY (product_id) REFERENCES sys_product (id) ON DELETE CASCADE
);

COMMENT ON TABLE iss_issue_no_seq IS '问题单编号序列（按租户+产品自增）';

INSERT INTO sys_permission (code, name, module, description) VALUES
('issue:view', '问题单查看', '问题单管理', '查看问题单列表与详情'),
('issue:operate', '问题单操作', '问题单管理', '问题单增删改');
