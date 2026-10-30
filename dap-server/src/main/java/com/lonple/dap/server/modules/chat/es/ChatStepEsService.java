package com.lonple.dap.server.modules.chat.es;

import com.lonple.dap.server.common.BizException;
import com.lonple.dap.server.common.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.data.elasticsearch.core.query.FetchSourceFilterBuilder;
import org.springframework.data.elasticsearch.core.query.IndexQuery;
import org.springframework.data.elasticsearch.core.query.IndexQueryBuilder;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatStepEsService {

    private final ElasticsearchOperations elasticsearchOperations;
    private final ChatStepIndexNames indexNames;

    public void index(ChatStepDocument doc) {
        if (doc == null || !StringUtils.hasText(doc.getEventId())) {
            return;
        }
        Instant when = parseOccurredAt(doc.getOccurredAt());
        String index = indexNames.index(when);
        IndexQuery query = new IndexQueryBuilder()
                .withId(doc.getEventId())
                .withObject(doc)
                .build();
        try {
            elasticsearchOperations.index(query, IndexCoordinates.of(index));
        } catch (Exception ex) {
            log.warn("index chat step failed eventId={}: {}", doc.getEventId(), ex.getMessage());
        }
    }

    public ChatStepDocument getByEventId(String eventId) {
        if (!StringUtils.hasText(eventId)) {
            throw new BizException("eventId 不能为空");
        }
        Criteria criteria = new Criteria("eventId").is(eventId.trim());
        CriteriaQuery query = new CriteriaQuery(criteria);
        query.setPageable(PageRequest.of(0, 1));
        try {
            SearchHits<ChatStepDocument> hits = elasticsearchOperations.search(
                    query, ChatStepDocument.class, IndexCoordinates.of(indexNames.searchPattern()));
            if (hits.isEmpty()) {
                throw new BizException(404, "步骤日志不存在");
            }
            return hits.getSearchHit(0).getContent();
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            if (isIndexMissing(ex)) {
                throw new BizException(404, "步骤日志不存在");
            }
            throw new BizException(503, "审计存储暂不可用: " + ex.getMessage());
        }
    }

    public List<ChatStepDocument> listBySession(Long tenantId, Long sessionId, String runId, boolean includeDetail) {
        Criteria criteria = new Criteria("category").is("CHAT_STEP")
                .and(new Criteria("tenantId").is(tenantId))
                .and(new Criteria("sessionId").is(sessionId));
        if (StringUtils.hasText(runId)) {
            criteria = criteria.and(new Criteria("runId").is(runId.trim()));
        }
        CriteriaQuery query = new CriteriaQuery(criteria);
        query.setPageable(PageRequest.of(0, 500, Sort.by(Sort.Direction.ASC, "seq")));
        if (!includeDetail) {
            query.addSourceFilter(new FetchSourceFilterBuilder().withExcludes("detail").build());
        }
        try {
            SearchHits<ChatStepDocument> hits = elasticsearchOperations.search(
                    query, ChatStepDocument.class, IndexCoordinates.of(indexNames.searchPattern()));
            List<ChatStepDocument> out = new ArrayList<>();
            for (SearchHit<ChatStepDocument> hit : hits) {
                out.add(hit.getContent());
            }
            return out;
        } catch (Exception ex) {
            if (isIndexMissing(ex)) {
                return List.of();
            }
            log.warn("list chat steps failed sessionId={}: {}", sessionId, ex.getMessage());
            return List.of();
        }
    }

    public PageResult<ChatStepDocument> search(
            Long tenantId,
            int page,
            int size,
            Instant from,
            Instant to,
            Long sessionId,
            String runId,
            Long messageId,
            Long pipeInstanceId,
            Long pipeStepInstanceId,
            Long requirementId,
            Long userId,
            String taskTypeCode,
            String keyword,
            boolean includeDetail) {
        Criteria criteria = new Criteria("category").is("CHAT_STEP")
                .and(new Criteria("tenantId").is(tenantId));
        if (from != null && to != null) {
            criteria = criteria.and(new Criteria("occurredAt").between(from.toString(), to.toString()));
        } else if (from != null) {
            criteria = criteria.and(new Criteria("occurredAt").greaterThanEqual(from.toString()));
        } else if (to != null) {
            criteria = criteria.and(new Criteria("occurredAt").lessThanEqual(to.toString()));
        }
        if (sessionId != null) {
            criteria = criteria.and(new Criteria("sessionId").is(sessionId));
        }
        if (StringUtils.hasText(runId)) {
            criteria = criteria.and(new Criteria("runId").is(runId.trim()));
        }
        if (messageId != null) {
            criteria = criteria.and(new Criteria("messageId").is(messageId));
        }
        if (pipeInstanceId != null) {
            criteria = criteria.and(new Criteria("pipeInstanceId").is(pipeInstanceId));
        }
        if (pipeStepInstanceId != null) {
            criteria = criteria.and(new Criteria("pipeStepInstanceId").is(pipeStepInstanceId));
        }
        if (requirementId != null) {
            criteria = criteria.and(new Criteria("requirementId").is(requirementId));
        }
        if (userId != null) {
            criteria = criteria.and(new Criteria("userId").is(userId));
        }
        if (StringUtils.hasText(taskTypeCode)) {
            criteria = criteria.and(new Criteria("taskTypeCode").is(taskTypeCode.trim()));
        }
        if (StringUtils.hasText(keyword)) {
            criteria = criteria.and(new Criteria("message").matches(keyword.trim()));
        }

        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 200);
        CriteriaQuery query = new CriteriaQuery(criteria);
        query.setPageable(PageRequest.of(p - 1, s, Sort.by(Sort.Direction.DESC, "occurredAt")));
        if (!includeDetail) {
            query.addSourceFilter(new FetchSourceFilterBuilder().withExcludes("detail").build());
        }
        try {
            SearchHits<ChatStepDocument> hits = elasticsearchOperations.search(
                    (Query) query, ChatStepDocument.class, IndexCoordinates.of(indexNames.searchPattern()));
            List<ChatStepDocument> records = new ArrayList<>();
            for (SearchHit<ChatStepDocument> hit : hits) {
                records.add(hit.getContent());
            }
            return new PageResult<>(hits.getTotalHits(), records);
        } catch (Exception ex) {
            if (isIndexMissing(ex)) {
                return new PageResult<>(0, List.of());
            }
            throw new BizException(503, "审计存储暂不可用: " + ex.getMessage());
        }
    }

    private static Instant parseOccurredAt(String raw) {
        if (raw == null || raw.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.parse(raw.trim());
        } catch (Exception e) {
            return Instant.now();
        }
    }

    private static boolean isIndexMissing(Throwable ex) {
        Throwable cur = ex;
        while (cur != null) {
            String msg = cur.getMessage();
            String name = cur.getClass().getSimpleName();
            if (name.contains("index_not_found") || name.contains("IndexNotFound")
                    || (msg != null && (msg.contains("index_not_found") || msg.contains("no such index")))) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }
}
