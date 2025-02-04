package org.broadinstitute.ddp.db.dao;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.broadinstitute.ddp.TxnAwareBaseTest;
import org.broadinstitute.ddp.cache.DaoBuilder;
import org.broadinstitute.ddp.db.TransactionWrapper;
import org.broadinstitute.ddp.db.dto.CompositeQuestionDto;
import org.broadinstitute.ddp.db.dto.QuestionDto;
import org.broadinstitute.ddp.model.activity.definition.FormActivityDef;
import org.broadinstitute.ddp.model.activity.definition.FormSectionDef;
import org.broadinstitute.ddp.model.activity.definition.QuestionBlockDef;
import org.broadinstitute.ddp.model.activity.definition.i18n.Translation;
import org.broadinstitute.ddp.model.activity.definition.question.CompositeQuestionDef;
import org.broadinstitute.ddp.model.activity.definition.question.DateQuestionDef;
import org.broadinstitute.ddp.model.activity.definition.question.QuestionDef;
import org.broadinstitute.ddp.model.activity.definition.question.TextQuestionDef;
import org.broadinstitute.ddp.model.activity.definition.template.Template;
import org.broadinstitute.ddp.model.activity.revision.RevisionMetadata;
import org.broadinstitute.ddp.model.activity.types.BlockType;
import org.broadinstitute.ddp.model.activity.types.DateFieldType;
import org.broadinstitute.ddp.model.activity.types.DateRenderMode;
import org.broadinstitute.ddp.model.activity.types.TemplateType;
import org.broadinstitute.ddp.model.activity.types.TextInputType;
import org.broadinstitute.ddp.util.TestDataSetupUtil;
import org.broadinstitute.ddp.util.TestUtil;
import org.jdbi.v3.core.Handle;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class JdbiCompositeQuestionTest extends TxnAwareBaseTest {
    private static final String SID_TEXT1 = "TESTSIDTEXT1";
    private static final String SID_DATE1 = "TESTSIDDATE1";
    private static final String SID_COMP1 = "TESTSIDCOMP1";

    private static TestDataSetupUtil.GeneratedTestData testData;
    private DaoBuilder<JdbiQuestion> jdbiQuestionBuilder;
    private boolean isCachedDao;

    public JdbiCompositeQuestionTest(DaoBuilder jdbiQuestionBuilder, boolean isCachedDao) {
        this.jdbiQuestionBuilder = jdbiQuestionBuilder;
        this.isCachedDao = isCachedDao;
    }

    @Parameterized.Parameters
    public static Collection<Object[]> data() {
        Object[] uncached = {(DaoBuilder<JdbiQuestion>) (handle) -> handle.attach(JdbiQuestion.class), false};
        Object[] cached = {(DaoBuilder<JdbiQuestion>) JdbiQuestionCached::new, true};
        return List.of(uncached, cached);
    }

    @BeforeClass
    public static void setup() {
        TransactionWrapper.useTxn(handle -> testData = TestDataSetupUtil.generateBasicUserTestData(handle));
    }

    @Test
    public void testGetCompositeQuestionDtoByQuestionId() {
        TransactionWrapper.useTxn(handle -> {
            QuestionDef[] childQuestionDefs = {buildTextQuestionDef(), buildDateQuestionDef()};
            FormActivityDef act = insertCompositeActivity(handle, testData.getUserGuid(), testData.getStudyGuid(), childQuestionDefs);
            QuestionBlockDef firstQuestionInFormDef = extractFirstQuestion(act);
            Long firstQuestionInFormId = firstQuestionInFormDef.getQuestion().getQuestionId();
            var jdbiQuestion = jdbiQuestionBuilder.buildDao(handle);
            CompositeQuestionDto compositeQuestionDto = (CompositeQuestionDto) jdbiQuestion
                    .findQuestionDtoById(firstQuestionInFormId)
                    .orElse(null);
            assertNotNull(compositeQuestionDto);
            assertNotNull(compositeQuestionDto.getAddButtonTemplateId());
            assertNotNull(compositeQuestionDto.getAdditionalItemTemplateId());
            assertNotEquals(compositeQuestionDto.getAddButtonTemplateId(), compositeQuestionDto
                    .getAdditionalItemTemplateId());
            assertTrue(compositeQuestionDto.isAllowMultiple());

            List<Long> childIds = jdbiQuestion
                    .collectOrderedCompositeChildIdsByParentIds(Set.of(compositeQuestionDto.getId()))
                    .get(compositeQuestionDto.getId());
            assertEquals(childQuestionDefs.length, childIds.size());

            Map<Long, QuestionDto> childDtos;
            try (var stream = jdbiQuestion.findQuestionDtosByIds(childIds)) {
                childDtos = stream.collect(Collectors.toMap(QuestionDto::getId, Function.identity()));
            }
            for (int i = 0; i < childQuestionDefs.length; i++) {
                QuestionDef childDef = childQuestionDefs[i];
                QuestionDto childDto = childDtos.get(childIds.get(i));
                assertEquals(childDef.getQuestionId(), (Long) childDto.getId());
                assertEquals(childDef.getStableId(), childDto.getStableId());
                assertEquals(childDef.getQuestionType(), childDto.getType());
            }

            handle.rollback();
        });
    }

    @Test
    public void testGetCompositeQuestionDtoByQuestionIdWithoutChildren() {
        TransactionWrapper.useTxn(handle -> {
            FormActivityDef act = insertCompositeActivity(handle, testData.getUserGuid(), testData.getStudyGuid());
            QuestionBlockDef firstQuestionInFormDef = extractFirstQuestion(act);
            Long firstQuestionInFormId = firstQuestionInFormDef.getQuestion().getQuestionId();
            CompositeQuestionDto compositeQuestionDto = (CompositeQuestionDto) jdbiQuestionBuilder.buildDao(handle)
                    .findQuestionDtoById(firstQuestionInFormId)
                    .orElse(null);
            assertNotNull(compositeQuestionDto);
            assertNotNull(compositeQuestionDto.getAddButtonTemplateId());
            assertNotNull(compositeQuestionDto.getAdditionalItemTemplateId());
            assertNotEquals(compositeQuestionDto.getAddButtonTemplateId(), compositeQuestionDto
                    .getAdditionalItemTemplateId());
            assertTrue(compositeQuestionDto.isAllowMultiple());

            handle.rollback();
        });
    }

    private QuestionBlockDef extractFirstQuestion(FormActivityDef activity) {
        return activity.getSections().get(0).getBlocks().stream()
                .filter(block -> block.getBlockType() == BlockType.QUESTION)
                .map(block -> (QuestionBlockDef) block)
                .findFirst().get();
    }

    private FormActivityDef insertCompositeActivity(Handle handle, String userGuid, String studyGuid,
                                                    QuestionDef... childQuestionDefs) {
        CompositeQuestionDef compQ = CompositeQuestionDef.builder()
                .setStableId(SID_COMP1)
                .setPrompt(new Template(TemplateType.TEXT, null, "Comp1"))
                .addChildrenQuestions(Arrays.asList(childQuestionDefs))
                .setAllowMultiple(true)
                .setAddButtonTemplate(new Template(TemplateType.TEXT, null, "Add Button Text"))
                .setAdditionalItemTemplate(new Template(TemplateType.TEXT, null, "The additional template"))
                .build();


        FormActivityDef form = FormActivityDef.generalFormBuilder("act1", "v1", studyGuid)
                .addName(new Translation("en", "date test activity"))
                .addSection(new FormSectionDef(null, TestUtil.wrapQuestions(compQ)))
                .build();
        long userId = handle.attach(JdbiUser.class).getUserIdByGuid(userGuid);
        handle.attach(ActivityDao.class).insertActivity(form, RevisionMetadata.now(userId, "add composite question "
                + "activity"));
        assertNotNull(form.getActivityId());
        return form;
    }

    private DateQuestionDef buildDateQuestionDef() {
        return DateQuestionDef.builder().setStableId(SID_DATE1)
                .setPrompt(new Template(TemplateType.TEXT, null, "d1"))
                .setRenderMode(DateRenderMode.TEXT).addFields(DateFieldType.YEAR)
                .build();
    }

    private TextQuestionDef buildTextQuestionDef() {
        return TextQuestionDef.builder()
                .setStableId(SID_TEXT1)
                .setInputType(TextInputType.TEXT)
                .setPrompt(new Template(TemplateType.TEXT, null, "text"))
                .build();
    }
}
