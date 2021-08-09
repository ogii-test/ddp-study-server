package org.broadinstitute.ddp.db.dto;

import java.io.Serializable;
import java.util.Set;

import org.broadinstitute.ddp.model.activity.types.OptionCollationStyle;
import org.broadinstitute.ddp.model.activity.types.PicklistRenderMode;
import org.broadinstitute.ddp.model.activity.types.PicklistSelectMode;
import org.jdbi.v3.core.mapper.Nested;
import org.jdbi.v3.core.mapper.reflect.ColumnName;
import org.jdbi.v3.core.mapper.reflect.JdbiConstructor;

/**
 * DTO class to represent picklist question that includes all base question data.
 */
public final class PicklistQuestionDto extends QuestionDto {

    private PicklistSelectMode selectMode;
    private PicklistRenderMode renderMode;
    private OptionCollationStyle collationStyle;
    private Long labelTemplateId;

    @JdbiConstructor
    public PicklistQuestionDto(@Nested QuestionDto questionDto,
                               @ColumnName("picklist_select_mode") PicklistSelectMode selectMode,
                               @ColumnName("picklist_render_mode") PicklistRenderMode renderMode,
                               @ColumnName("option_collation") OptionCollationStyle collationStyle,
                               @ColumnName("picklist_label_template_id") Long labelTemplateId) {
        super(questionDto);
        this.selectMode = selectMode;
        this.renderMode = renderMode;
        this.collationStyle = collationStyle;
        this.labelTemplateId = labelTemplateId;
    }

    public PicklistSelectMode getSelectMode() {
        return selectMode;
    }

    public PicklistRenderMode getRenderMode() {
        return renderMode;
    }

    public OptionCollationStyle getCollationStyle() {
        return collationStyle;
    }

    public Long getLabelTemplateId() {
        return labelTemplateId;
    }

    @Override
    public Set<Long> getTemplateIds() {
        var ids = super.getTemplateIds();
        if (labelTemplateId != null) {
            ids.add(labelTemplateId);
        }
        return ids;
    }
}
