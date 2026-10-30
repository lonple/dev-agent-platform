package com.lonple.dap.server.modules.pipeline;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lonple.dap.server.modules.pipeline.entity.PipeArtifact;
import com.lonple.dap.server.modules.pipeline.mapper.PipeArtifactMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PipeArtifactService {

    private final PipeArtifactMapper artifactMapper;

    public PipeArtifact findCurrent(Long requirementId, String kind) {
        if (requirementId == null || kind == null) {
            return null;
        }
        return artifactMapper.selectOne(new LambdaQueryWrapper<PipeArtifact>()
                .eq(PipeArtifact::getRequirementId, requirementId)
                .eq(PipeArtifact::getKind, kind)
                .eq(PipeArtifact::getIsCurrent, true)
                .orderByDesc(PipeArtifact::getVersion)
                .last("LIMIT 1"));
    }

    public List<PipeArtifact> listCurrent(Long requirementId) {
        return artifactMapper.selectList(new LambdaQueryWrapper<PipeArtifact>()
                .eq(PipeArtifact::getRequirementId, requirementId)
                .eq(PipeArtifact::getIsCurrent, true)
                .orderByAsc(PipeArtifact::getKind));
    }

    @Transactional
    public PipeArtifact register(Long requirementId, String kind, Long wikiNodeId,
                                 Long stepInstanceId, String title) {
        PipeArtifact latest = artifactMapper.selectOne(new LambdaQueryWrapper<PipeArtifact>()
                .eq(PipeArtifact::getRequirementId, requirementId)
                .eq(PipeArtifact::getKind, kind)
                .orderByDesc(PipeArtifact::getVersion)
                .last("LIMIT 1"));
        int nextVer = latest == null || latest.getVersion() == null ? 1 : latest.getVersion() + 1;

        artifactMapper.update(null, new LambdaUpdateWrapper<PipeArtifact>()
                .eq(PipeArtifact::getRequirementId, requirementId)
                .eq(PipeArtifact::getKind, kind)
                .eq(PipeArtifact::getIsCurrent, true)
                .set(PipeArtifact::getIsCurrent, false));

        PipeArtifact row = new PipeArtifact();
        row.setRequirementId(requirementId);
        row.setKind(kind);
        row.setVersion(nextVer);
        row.setWikiNodeId(wikiNodeId);
        row.setStepInstanceId(stepInstanceId);
        row.setTitle(title);
        row.setIsCurrent(true);
        row.setCreatedAt(LocalDateTime.now());
        artifactMapper.insert(row);
        return row;
    }
}
