package com.lonple.dap.server.modules.chat.es;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatStepDocument {

    @Id
    @Field(type = FieldType.Keyword)
    private String eventId;

    @Field(type = FieldType.Keyword)
    private String category;

    @Field(type = FieldType.Long)
    private Long tenantId;

    @Field(type = FieldType.Long)
    private Long userId;

    @Field(type = FieldType.Keyword)
    private String username;

    @Field(type = FieldType.Long)
    private Long workspaceId;

    @Field(type = FieldType.Long)
    private Long sessionId;

    @Field(type = FieldType.Keyword)
    private String sessionTitle;

    @Field(type = FieldType.Long)
    private Long requirementId;

    @Field(type = FieldType.Keyword)
    private String taskTypeCode;

    @Field(type = FieldType.Long)
    private Long positionId;

    @Field(type = FieldType.Long)
    private Long messageId;

    @Field(type = FieldType.Keyword)
    private String runId;

    @Field(type = FieldType.Integer)
    private Integer seq;

    @Field(type = FieldType.Integer)
    private Integer llmRound;

    @Field(type = FieldType.Keyword)
    private String step;

    @Field(type = FieldType.Text)
    private String message;

    /** 全量细节，只存 _source */
    @Field(type = FieldType.Object, enabled = false)
    private Map<String, Object> detail;

    @Field(type = FieldType.Keyword)
    private String status;

    @Field(type = FieldType.Long)
    private Long durationMs;

    /** ISO-8601 毫秒字符串 */
    @Field(type = FieldType.Keyword)
    private String occurredAt;
}
