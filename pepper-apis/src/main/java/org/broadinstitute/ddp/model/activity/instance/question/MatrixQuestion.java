package org.broadinstitute.ddp.model.activity.instance.question;

import com.google.gson.annotations.SerializedName;
import org.broadinstitute.ddp.content.ContentStyle;
import org.broadinstitute.ddp.model.activity.instance.answer.MatrixAnswer;
import org.broadinstitute.ddp.model.activity.instance.validation.Rule;
import org.broadinstitute.ddp.model.activity.types.MatrixSelectMode;
import org.broadinstitute.ddp.model.activity.types.QuestionType;
import org.broadinstitute.ddp.util.MiscUtil;

import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import java.util.List;
import java.util.function.Consumer;

public final class MatrixQuestion extends Question<MatrixAnswer> {

    @NotNull
    @SerializedName("selectMode")
    private final MatrixSelectMode selectMode;

    @SerializedName("groups")
    private final List<MatrixGroup> groups;

    @NotEmpty
    @SerializedName("options")
    private final List<MatrixOption> matrixOptions;

    @NotEmpty
    @SerializedName("questions")
    private final List<MatrixRow> matrixQuestionRows;

    public MatrixQuestion(String stableId, long promptTemplateId, boolean isRestricted,
                          boolean isDeprecated, Boolean readonly, Long tooltipTemplateId, Long additionalInfoHeaderTemplateId,
                          Long additionalInfoFooterTemplateId, List<MatrixAnswer> answers, List<Rule<MatrixAnswer>> validations,
                          MatrixSelectMode selectMode, List<MatrixGroup> groups, List<MatrixOption> matrixOptions,
                          List<MatrixRow> matrixQuestionRows) {
        super(QuestionType.MATRIX, stableId, promptTemplateId, isRestricted, isDeprecated, readonly, tooltipTemplateId,
                additionalInfoHeaderTemplateId, additionalInfoFooterTemplateId, answers, validations);

        this.selectMode = MiscUtil.checkNonNull(selectMode, "selectMode");
        this.groups = groups;

        if (matrixOptions == null || matrixOptions.isEmpty()) {
            throw new IllegalArgumentException("options list needs to be non-empty");
        } else {
            this.matrixOptions = matrixOptions;
        }

        if (matrixQuestionRows == null || matrixQuestionRows.isEmpty()) {
            throw new IllegalArgumentException("rows list needs to be non-empty");
        } else {
            this.matrixQuestionRows = matrixQuestionRows;
        }
    }

    /**
     * Construct an instance view of picklist question, where list of picklist options must be non-empty.
     */
    public MatrixQuestion(String stableId, long promptTemplateId, MatrixSelectMode selectMode,
                          List<MatrixAnswer> answers, List<Rule<MatrixAnswer>> validations,
                          List<MatrixOption> options, List<MatrixRow> rows, List<MatrixGroup> groups) {
        this(stableId,
                promptTemplateId,
                false,
                false,
                false,
                null,
                null,
                null,
                answers,
                validations,
                selectMode,
                groups,
                options,
                rows);
    }

    public MatrixSelectMode getSelectMode() {
        return selectMode;
    }

    public List<MatrixGroup> getGroups() {
        return groups;
    }

    public List<MatrixOption> getMatrixOptions() {
        return matrixOptions;
    }

    public List<MatrixRow> getMatrixQuestionRows() {
        return matrixQuestionRows;
    }

    @Override
    public void registerTemplateIds(Consumer<Long> registry) {
        super.registerTemplateIds(registry);

        for (MatrixGroup group : groups) {
            group.registerTemplateIds(registry);
        }

        for (MatrixOption option : matrixOptions) {
            option.registerTemplateIds(registry);
        }

        for (MatrixRow question : matrixQuestionRows) {
            question.registerTemplateIds(registry);
        }
    }

    @Override
    public void applyRenderedTemplates(Provider<String> rendered, ContentStyle style) {
        super.applyRenderedTemplates(rendered, style);

        if (groups != null) {
            for (MatrixGroup group : groups) {
                group.applyRenderedTemplates(rendered, style);
            }
        }

        for (MatrixOption option : matrixOptions) {
            option.applyRenderedTemplates(rendered, style);
        }

        for (MatrixRow question : matrixQuestionRows) {
            question.applyRenderedTemplates(rendered, style);
        }
    }
}
