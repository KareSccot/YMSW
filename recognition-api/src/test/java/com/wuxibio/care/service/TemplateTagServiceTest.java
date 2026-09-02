package com.wuxibio.care.service;

import com.wuxibio.care.common.BizException;
import com.wuxibio.care.entity.ApprovalWorkflowDef;
import com.wuxibio.care.entity.TaskTagDef;
import com.wuxibio.care.entity.TaskWorkflowBinding;
import com.wuxibio.care.entity.TemplateTagAssignment;
import com.wuxibio.care.mapper.TaskTagDefMapper;
import com.wuxibio.care.mapper.TaskWorkflowBindingMapper;
import com.wuxibio.care.mapper.TemplateTagAssignmentMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TemplateTagServiceTest {

    @Mock private TemplateTagAssignmentMapper assignmentMapper;
    @Mock private TaskTagDefMapper tagDefMapper;
    @Mock private TaskWorkflowBindingMapper workflowBindingMapper;
    @Mock private ApprovalWorkflowService approvalWorkflowService;
    @Mock private TimeDependentService timeDependentService;
    @Mock private AuditLogService auditLogService;

    @Test
    void listAssignableOptionsOnlyReturnsTagsWithUsableWorkflow() {
        TemplateTagService service = service();
        TaskTagDef governed = tag("GOVERNED", "受治理");
        TaskTagDef unbound = tag("UNBOUND", "未绑定");
        when(tagDefMapper.selectList(any())).thenReturn(List.of(governed, unbound));
        when(timeDependentService.isEffective(any(), any(), any())).thenReturn(true);
        when(workflowBindingMapper.selectList(any())).thenReturn(
                List.of(binding("GOVERNED", "WF_APPROVAL")),
                List.of());
        when(approvalWorkflowService.getByCode("WF_APPROVAL")).thenReturn(workflow("WF_APPROVAL"));

        List<TemplateTagService.TemplateTagOption> result = service.listAssignableOptions();

        assertThat(result).singleElement().satisfies(option -> {
            assertThat(option.tagCode()).isEqualTo("GOVERNED");
            assertThat(option.workflowCode()).isEqualTo("WF_APPROVAL");
        });
    }

    @Test
    void replaceTagsRejectsTagWithoutActiveWorkflow() {
        TemplateTagService service = service();
        when(tagDefMapper.selectOne(any())).thenReturn(tag("UNBOUND", "未绑定"));
        when(timeDependentService.isEffective(any(), any(), any())).thenReturn(true);
        when(workflowBindingMapper.selectList(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.replaceTags(10L, List.of("UNBOUND")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未配置生效中的审批流");
    }

    @Test
    void runtimeResolutionRejectsInactiveTemplateTag() {
        TemplateTagService service = service();
        TaskTagDef inactive = tag("INACTIVE", "已停用");
        inactive.setStatus("Inactive");
        when(tagDefMapper.selectOne(any())).thenReturn(inactive);

        assertThatThrownBy(() -> service.requireActiveWorkflow("INACTIVE"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("Tag 不可用");
    }

    @Test
    void replaceTagsPersistsTemplateOwnedAssociations() {
        TemplateTagService service = service();
        when(tagDefMapper.selectOne(any())).thenReturn(tag("GOVERNED", "受治理"));
        when(timeDependentService.isEffective(any(), any(), any())).thenReturn(true);
        when(workflowBindingMapper.selectList(any())).thenReturn(List.of(binding("GOVERNED", "WF_APPROVAL")));
        when(approvalWorkflowService.getByCode("WF_APPROVAL")).thenReturn(workflow("WF_APPROVAL"));

        service.replaceTags(10L, List.of("GOVERNED", "GOVERNED"));

        ArgumentCaptor<TemplateTagAssignment> row = ArgumentCaptor.forClass(TemplateTagAssignment.class);
        verify(assignmentMapper).insert(row.capture());
        assertThat(row.getValue().getTemplateId()).isEqualTo(10L);
        assertThat(row.getValue().getTagCode()).isEqualTo("GOVERNED");
    }

    @Test
    void replaceTagsRejectsTagsResolvingToDifferentWorkflows() {
        TemplateTagService service = service();
        when(tagDefMapper.selectOne(any())).thenReturn(
                tag("TAG_A", "Tag A"),
                tag("TAG_B", "Tag B"));
        when(timeDependentService.isEffective(any(), any(), any())).thenReturn(true);
        when(workflowBindingMapper.selectList(any())).thenReturn(
                List.of(binding("TAG_A", "WF_A")),
                List.of(binding("TAG_B", "WF_B")));
        when(approvalWorkflowService.getByCode("WF_A")).thenReturn(workflow("WF_A"));
        when(approvalWorkflowService.getByCode("WF_B")).thenReturn(workflow("WF_B"));

        assertThatThrownBy(() -> service.replaceTags(10L, List.of("TAG_A", "TAG_B")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("必须指向同一审批流")
                .hasMessageContaining("WF_A")
                .hasMessageContaining("WF_B");
    }

    private TemplateTagService service() {
        return new TemplateTagService(
                assignmentMapper,
                tagDefMapper,
                workflowBindingMapper,
                approvalWorkflowService,
                timeDependentService,
                auditLogService);
    }

    private TaskTagDef tag(String code, String name) {
        TaskTagDef row = new TaskTagDef();
        row.setTagCode(code);
        row.setTagName(name);
        row.setStatus("Active");
        return row;
    }

    private TaskWorkflowBinding binding(String tagCode, String workflowCode) {
        TaskWorkflowBinding row = new TaskWorkflowBinding();
        row.setTagCode(tagCode);
        row.setWorkflowCode(workflowCode);
        row.setStatus("Active");
        return row;
    }

    private ApprovalWorkflowDef workflow(String code) {
        ApprovalWorkflowDef row = new ApprovalWorkflowDef();
        row.setWorkflowCode(code);
        row.setWorkflowName(code);
        row.setStatus("Active");
        return row;
    }
}
