package com.lonple.dap.server.modules.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lonple.dap.server.modules.audit.dto.TokenUsageAggRow;
import com.lonple.dap.server.modules.audit.dto.TokenUsageStatVO;
import com.lonple.dap.server.modules.audit.entity.LlmTokenUsage;
import com.lonple.dap.server.modules.audit.mapper.LlmTokenUsageMapper;
import com.lonple.dap.server.modules.chat.entity.WbChatSession;
import com.lonple.dap.server.modules.issue.entity.IssIssue;
import com.lonple.dap.server.modules.issue.mapper.IssueMapper;
import com.lonple.dap.server.modules.requirement.entity.ReqRequirement;
import com.lonple.dap.server.modules.requirement.mapper.RequirementMapper;
import com.lonple.dap.server.modules.sysuser.entity.SysUser;
import com.lonple.dap.server.modules.sysuser.mapper.SysUserMapper;
import com.lonple.dap.server.security.TenantSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenUsageService {

    private final LlmTokenUsageMapper tokenUsageMapper;
    private final SysUserMapper userMapper;
    private final RequirementMapper requirementMapper;
    private final IssueMapper issueMapper;
    private final ObjectMapper objectMapper;

    /**
     * 按 run 落库/更新累计 token（Agent 多次 usage 事件取最新累计值）。
     */
    @Transactional
    public void recordRunUsage(WbChatSession session, String runId, Long messageId, String usageJson) {
        if (session == null || !StringUtils.hasText(runId) || !StringUtils.hasText(usageJson)) {
            return;
        }
        try {
            JsonNode node = objectMapper.readTree(usageJson);
            long prompt = longVal(node, "prompt_tokens", "promptTokens");
            long completion = longVal(node, "completion_tokens", "completionTokens");
            long total = longVal(node, "total_tokens", "totalTokens");
            if (total <= 0 && prompt <= 0 && completion <= 0) {
                return;
            }
            if (total <= 0) {
                total = prompt + completion;
            }
            Long tenantId = session.getTenantId();
            Long userId = session.getUserId();
            if (tenantId == null || userId == null) {
                return;
            }
            LlmTokenUsage row = tokenUsageMapper.selectOne(new LambdaQueryWrapper<LlmTokenUsage>()
                    .eq(LlmTokenUsage::getTenantId, tenantId)
                    .eq(LlmTokenUsage::getRunId, runId.trim())
                    .last("LIMIT 1"));
            LocalDateTime now = LocalDateTime.now();
            if (row == null) {
                row = new LlmTokenUsage();
                row.setTenantId(tenantId);
                row.setUserId(userId);
                row.setSessionId(session.getId());
                row.setMessageId(messageId);
                row.setRunId(runId.trim());
                row.setRequirementId(session.getRequirementId());
                row.setIssueId(session.getIssueId());
                row.setTaskTypeCode(session.getTaskTypeCode());
                row.setPromptTokens(prompt);
                row.setCompletionTokens(completion);
                row.setTotalTokens(total);
                row.setOccurredAt(now);
                row.setCreatedAt(now);
                row.setUpdatedAt(now);
                tokenUsageMapper.insert(row);
            } else {
                row.setMessageId(messageId != null ? messageId : row.getMessageId());
                row.setPromptTokens(prompt);
                row.setCompletionTokens(completion);
                row.setTotalTokens(total);
                row.setOccurredAt(now);
                row.setUpdatedAt(now);
                tokenUsageMapper.updateById(row);
            }
        } catch (Exception e) {
            log.warn("record token usage failed runId={}: {}", runId, e.getMessage());
        }
    }

    public List<TokenUsageStatVO> statsByUser() {
        Long tenantId = TenantSupport.requireTenantId();
        return enrich(tokenUsageMapper.sumByUser(tenantId));
    }

    public List<TokenUsageStatVO> statsByRequirementUser() {
        Long tenantId = TenantSupport.requireTenantId();
        return enrich(tokenUsageMapper.sumByRequirementUser(tenantId));
    }

    public List<TokenUsageStatVO> statsByIssueUser() {
        Long tenantId = TenantSupport.requireTenantId();
        return enrich(tokenUsageMapper.sumByIssueUser(tenantId));
    }

    private List<TokenUsageStatVO> enrich(List<TokenUsageAggRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Set<Long> userIds = rows.stream().map(TokenUsageAggRow::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> reqIds = rows.stream().map(TokenUsageAggRow::getRequirementId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> issueIds = rows.stream().map(TokenUsageAggRow::getIssueId).filter(Objects::nonNull).collect(Collectors.toSet());

        Map<Long, String> usernames = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (SysUser u : userMapper.selectList(new LambdaQueryWrapper<SysUser>().in(SysUser::getId, userIds))) {
                usernames.put(u.getId(), u.getUsername());
            }
        }
        Map<Long, ReqRequirement> reqs = new HashMap<>();
        if (!reqIds.isEmpty()) {
            for (ReqRequirement r : requirementMapper.selectList(
                    new LambdaQueryWrapper<ReqRequirement>().in(ReqRequirement::getId, reqIds))) {
                reqs.put(r.getId(), r);
            }
        }
        Map<Long, IssIssue> issues = new HashMap<>();
        if (!issueIds.isEmpty()) {
            for (IssIssue i : issueMapper.selectList(new LambdaQueryWrapper<IssIssue>().in(IssIssue::getId, issueIds))) {
                issues.put(i.getId(), i);
            }
        }

        List<TokenUsageStatVO> out = new ArrayList<>(rows.size());
        for (TokenUsageAggRow row : rows) {
            TokenUsageStatVO vo = new TokenUsageStatVO();
            vo.setUserId(row.getUserId());
            vo.setUsername(usernames.getOrDefault(row.getUserId(), row.getUserId() == null ? "—" : "#" + row.getUserId()));
            vo.setRequirementId(row.getRequirementId());
            if (row.getRequirementId() != null) {
                ReqRequirement r = reqs.get(row.getRequirementId());
                if (r != null) {
                    vo.setRequirementNo(r.getReqNo());
                    vo.setRequirementName(r.getName());
                }
            }
            vo.setIssueId(row.getIssueId());
            if (row.getIssueId() != null) {
                IssIssue issue = issues.get(row.getIssueId());
                if (issue != null) {
                    vo.setIssueNo(issue.getIssueNo());
                }
            }
            vo.setPromptTokens(nz(row.getPromptTokens()));
            vo.setCompletionTokens(nz(row.getCompletionTokens()));
            vo.setTotalTokens(nz(row.getTotalTokens()));
            out.add(vo);
        }
        return out;
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static long longVal(JsonNode node, String... fields) {
        for (String f : fields) {
            JsonNode n = node.path(f);
            if (!n.isMissingNode() && !n.isNull() && n.isNumber()) {
                return n.asLong();
            }
        }
        return 0L;
    }
}
