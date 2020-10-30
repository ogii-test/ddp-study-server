package org.broadinstitute.ddp.db;

import static org.broadinstitute.ddp.model.activity.types.ActivityType.FORMS;

import java.sql.Blob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.typesafe.config.Config;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.broadinstitute.ddp.constants.ConfigFile.SqlQuery;
import org.broadinstitute.ddp.constants.SqlConstants;
import org.broadinstitute.ddp.constants.SqlConstants.ActivityInstanceTable;
import org.broadinstitute.ddp.constants.SqlConstants.ActivityTypeTable;
import org.broadinstitute.ddp.constants.SqlConstants.LanguageCodeTable;
import org.broadinstitute.ddp.constants.SqlFile.ActivityInstanceSql;
import org.broadinstitute.ddp.content.ContentStyle;
import org.broadinstitute.ddp.content.I18nContentRenderer;
import org.broadinstitute.ddp.content.I18nTemplateConstants;
import org.broadinstitute.ddp.content.RenderValueProvider;
import org.broadinstitute.ddp.db.dao.FormActivityDao;
import org.broadinstitute.ddp.db.dao.JdbiActivity;
import org.broadinstitute.ddp.db.dao.JdbiActivityInstance;
import org.broadinstitute.ddp.db.dto.ActivityDto;
import org.broadinstitute.ddp.exception.DDPException;
import org.broadinstitute.ddp.json.UserActivity;
import org.broadinstitute.ddp.json.activity.ActivityInstanceSummary;
import org.broadinstitute.ddp.model.activity.definition.FormActivityDef;
import org.broadinstitute.ddp.model.activity.instance.ActivityInstance;
import org.broadinstitute.ddp.model.activity.instance.ActivityResponse;
import org.broadinstitute.ddp.model.activity.instance.FormResponse;
import org.broadinstitute.ddp.model.activity.types.ActivityType;
import org.broadinstitute.ddp.model.activity.types.FormType;
import org.broadinstitute.ddp.service.ActivityInstanceCreationValidation;
import org.broadinstitute.ddp.util.ActivityInstanceUtil;
import org.broadinstitute.ddp.util.I18nUtil;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.JdbiException;
import org.jdbi.v3.core.generic.GenericType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ActivityInstanceDao {

    private static final Logger LOG = LoggerFactory.getLogger(ActivityInstanceDao.class);

    private static String TRANSLATED_SUMMARY_BY_GUID_QUERY;
    private static String INSTANCE_ID_BY_GUID_QUERY;
    private static String INSTANCE_SUMMARIES_FOR_USER;
    private static String INSTANCE_CREATION_VALIDATION_QUERY;
    private static String SECTIONS_SIZE_FOR_ACTIVITY_INSTANCE;

    private final FormInstanceDao formInstanceDao;
    private final I18nContentRenderer renderer;

    /**
     * Instantiate ActivityInstanceDao object.
     */
    public ActivityInstanceDao(
            FormInstanceDao formInstanceDao
    ) {
        this.formInstanceDao = formInstanceDao;
        this.renderer = new I18nContentRenderer();
    }

    /**
     * Given {@code sqlConfig}, load relevant SQL queries.
     */
    public static void loadSqlCommands(Config sqlConfig) {
        TRANSLATED_SUMMARY_BY_GUID_QUERY = sqlConfig.getString(ActivityInstanceSql.TRANSLATED_SUMMARY_BY_GUID_QUERY);
        INSTANCE_ID_BY_GUID_QUERY = sqlConfig.getString(ActivityInstanceSql.INSTANCE_ID_BY_GUID_QUERY);
        INSTANCE_SUMMARIES_FOR_USER = sqlConfig.getString(ActivityInstanceSql.INSTANCE_SUMMARIES_FOR_USER_QUERY);
        INSTANCE_CREATION_VALIDATION_QUERY =
                sqlConfig.getString(ActivityInstanceSql.INSTANCE_CREATION_VALIDATION_QUERY);
        SECTIONS_SIZE_FOR_ACTIVITY_INSTANCE = sqlConfig.getString(ActivityInstanceSql.SECTIONS_SIZE_FOR_ACTIVITY_INSTANCE);
    }

    /**
     * Checks whether or not a new instance of activityCode
     * can be created for userGuid by looking at counts
     * of instances and pex precondition.
     */
    public ActivityInstanceCreationValidation checkSuitabilityForActivityInstanceCreation(Handle handle,
                                                                                          String activityCode,
                                                                                          String userGuid,
                                                                                          long studyId) {
        try {
            try (PreparedStatement stmt = handle.getConnection().prepareStatement(INSTANCE_CREATION_VALIDATION_QUERY)) {
                stmt.setString(1, userGuid);
                stmt.setString(2, activityCode);
                stmt.setLong(3, studyId);

                ResultSet rs = stmt.executeQuery();

                if (!rs.next()) {
                    throw new DaoException("Could not retrieve validation information for activity code "
                            + activityCode + " for user " + userGuid);
                }
                Number maxInstancesAllowed = (Number) rs.getObject(SqlQuery.MAX_INSTANCES_PER_USER);
                Number instancesForUser = (Number) rs.getObject(SqlQuery.NUM_INSTANCES_FOR_USER);

                if (rs.next()) {
                    throw new DaoException("Too many validation rows for activity code "
                            + activityCode + " for user " + userGuid);
                }

                boolean hasTooManyInstances = false;
                boolean hasUnmetPrecondition = false;

                if (maxInstancesAllowed != null) {
                    hasTooManyInstances = (maxInstancesAllowed.intValue() - instancesForUser.intValue()) <= 0;
                }
                return new ActivityInstanceCreationValidation(hasTooManyInstances, hasUnmetPrecondition);
            }
        } catch (SQLException e) {
            throw new DaoException("Could not retrieve validation data before creating activity "
                    + activityCode + " for user " + userGuid, e);
        }
    }

    /**
     * Given a list of study activities, get the guid of the latest user's activity instance
     *
     * @param handle       the jdbi handle
     * @param userGuid     the participant user guid
     * @param activityCode the study activity stable code
     * @return instance guid, if found
     */
    public Optional<String> getGuidOfLatestInstanceForUserAndActivity(Handle handle, String userGuid, String activityCode,
                                                                      long studyId) {
        try {
            return handle.attach(JdbiActivityInstance.class)
                    .findLatestInstanceGuidByUserGuidAndCodesOfActivities(userGuid, Arrays.asList(activityCode), studyId);
        } catch (IllegalStateException e) {
            throw new DaoException("Multiple rows found for user guid " + userGuid
                    + " and activity code " + activityCode, e);
        } catch (JdbiException e) {
            throw new DaoException("Could not find instance guid for user guid " + userGuid
                    + " and activity code " + activityCode, e);
        }
    }

    /**
     * Get summary representation of specific activity instance translated into the preferred language
     *
     * @param handle               the jdbi handle
     * @param activityInstanceGuid the activity instance guid
     * @return activity summary, if found
     */
    public UserActivity getTranslatedSummaryByGuid(
            Handle handle,
            String activityInstanceGuid,
            String preferredLanguageCode
    ) {
        try {
            List<UserActivity> userActivities = handle.createQuery(TRANSLATED_SUMMARY_BY_GUID_QUERY)
                    .bind(0, activityInstanceGuid)
                    .registerRowMapper(UserActivity.class, (rs, ctx) -> {
                        String title = rs.getString(ActivityInstanceTable.TITLE);
                        String subtitle = rs.getString(ActivityInstanceTable.SUBTITLE);
                        String type = rs.getString(ActivityInstanceTable.TYPE_NAME);
                        String status = rs.getString(ActivityInstanceTable.STATUS_TYPE_NAME);
                        String isoLangCode = rs.getString(LanguageCodeTable.CODE);
                        return new UserActivity(activityInstanceGuid, title, subtitle, type, status, isoLangCode);
                    })
                    .collectInto(new GenericType<List<UserActivity>>() {
                    });
            // Picking the appropriate language for each activity instance
            List<UserActivity> userActivitiesTranslated = I18nUtil.getActivityInstanceTranslation(
                    userActivities, preferredLanguageCode
            );
            Optional<UserActivity> userActivityTranslated = Optional.ofNullable(
                    userActivitiesTranslated.isEmpty() ? null : userActivitiesTranslated.get(0)
            );
            return userActivityTranslated.orElseThrow(
                    () -> new DaoException("Unable to find translated summary for prequalifier instance " + activityInstanceGuid)
            );
        } catch (IllegalStateException e) {
            throw new DaoException("Multiple rows found for instance guid " + activityInstanceGuid
                    + " iso lang " + preferredLanguageCode, e);
        } catch (JdbiException e) {
            throw new DaoException("Could not find summary for instance guid " + activityInstanceGuid
                    + " iso lang " + preferredLanguageCode, e);
        }
    }

    /**
     * Get specific activity instance, translated to given language.
     *
     * @param handle               the jdbi handle
     * @param activityType         the corresponding activity type
     * @param activityInstanceGuid the activity instance guid
     * @param isoLangCode          the language iso code
     * @param style                the content style to use for converting content
     * @return activity instance, or null if not found
     */
    public ActivityInstance getTranslatedActivityByTypeAndGuid(Handle handle, ActivityType activityType, String activityInstanceGuid,
                                                               String isoLangCode, ContentStyle style) {
        return getTranslatedActivityByTypeAndGuid(handle, activityType, activityInstanceGuid, isoLangCode, style, false);
    }

    // This allows fetching activity with deprecated questions, i.e. for data export purposes. Prefer the other method that excludes them.
    public ActivityInstance getTranslatedActivityByTypeAndGuid(Handle handle, ActivityType activityType, String activityInstanceGuid,
                                                               String isoLangCode, ContentStyle style, boolean includeDeprecated) {
        if (activityType == FORMS) {
            return formInstanceDao.getTranslatedFormByGuid(handle, activityInstanceGuid, isoLangCode, style, includeDeprecated);
        }
        throw new DDPException("Unhandled activity type " + activityType);
    }

    /**
     * Given an activity instance GUID, returns a activity instance id.
     *
     * @param handle JDBC connection
     * @param guid   GUID of the activity instance to get an id for
     * @return Id of the activity instance
     */
    public Long getActivityInstanceIdByGuid(Handle handle, String guid) {
        Long activityInstanceId = null;
        try (PreparedStatement stmt = handle.getConnection().prepareStatement(INSTANCE_ID_BY_GUID_QUERY)) {
            stmt.setString(1, guid);
            ResultSet rs = stmt.executeQuery();

            if (!rs.next()) {
                throw new NoSuchElementException("Could not fetch an activity instance with GUID " + guid);
            }

            activityInstanceId = rs.getLong(SqlConstants.ACTIVITY_INSTANCE_ID);

            if (rs.next()) {
                throw new RuntimeException("The number of returned records for the activity instance with GUID "
                        + guid + " > 1");
            }

        } catch (SQLException e) {
            throw new RuntimeException("Could not fetch an activity instance", e);
        }
        return activityInstanceId;
    }

    /**
     * Given a user, return a collection of activity instance summaries.
     *
     * @param handle          JDBC handle
     * @param userGuid        GUID of the user to get activity instance summaries for
     * @param studyGuid       GUID of the study to get activity instance summaries for
     * @param isoLanguageCode The desired translation language (with English as the fallback one)
     * @return A collection of activity instance summaries for the user/study
     */
    public List<ActivityInstanceSummary> listActivityInstancesForUser(
            Handle handle,
            String userGuid,
            String studyGuid,
            String isoLanguageCode
    ) {
        ActivityDefStore activityDefStore = ActivityDefStore.getInstance();
        Map<String, Blob> formTypeAndStatusTypeToIcon = activityDefStore.findActivityStatusIcons(handle, studyGuid);
        List<ActivityInstanceSummary> activitySummaries = new ArrayList<>();
        try {
            try (PreparedStatement stmt = handle.getConnection().prepareStatement(INSTANCE_SUMMARIES_FOR_USER)) {
                stmt.setString(1, studyGuid);
                stmt.setString(2, userGuid);

                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    String activityCode = rs.getString(SqlConstants.StudyActivityTable.CODE);
                    String activityName = rs.getString(SqlConstants.StudyActivityTable.NAME_TRANS);
                    String activitySecondName = rs.getString(SqlConstants.StudyActivityTable.SECOND_NAME_TRANS);
                    String activityTitle = rs.getString(SqlConstants.StudyActivityTable.TITLE_TRANS);
                    String activitySubtitle = rs.getString(SqlConstants.StudyActivityTable.SUBTITLE_TRANS);
                    String activityDescription = rs.getString(SqlConstants.StudyActivityTable.DESCRIPTION_TRANS);
                    String activitySummary = rs.getString(SqlConstants.StudyActivityTable.SUMMARY_TRANS);

                    // If there's no title, leave it empty.
                    activityTitle = (activityTitle == null ? "" : activityTitle);

                    boolean isActivityWriteOnce = rs.getBoolean(SqlConstants.StudyActivityTable.IS_WRITE_ONCE);
                    long activityInstanceId = rs.getLong(ActivityInstanceTable.ID);
                    String activityInstanceGuid = rs.getString(ActivityInstanceTable.GUID);
                    String activityTypeCode = rs.getString(ActivityTypeTable.TYPE_CODE);
                    String formTypeCode = rs.getString(SqlConstants.FormTypeTable.CODE);
                    String statusTypeCode =
                            rs.getString(SqlConstants.ActivityInstanceStatusTypeTable.ACTIVITY_STATUS_TYPE_CODE);
                    FormType formType = formTypeCode != null ? FormType.valueOf(formTypeCode) : null;
                    boolean excludeStatusIconFromDisplay = rs.getBoolean(SqlConstants.StudyActivityTable.EXCLUDE_STATUS_ICON_FROM_DISPLAY);
                    Blob iconBlob = excludeStatusIconFromDisplay ? null : formTypeAndStatusTypeToIcon.get(formType + "-" + statusTypeCode);
                    String iconBase64 = iconBlob != null
                            ? Base64.getEncoder().encodeToString(iconBlob.getBytes(1, (int) iconBlob.length())) : null;

                    Long editTimeoutSec = (Long) rs.getObject(SqlConstants.StudyActivityTable.EDIT_TIMEOUT_SEC);
                    long createdAtMillis = rs.getLong(SqlConstants.ActivityInstanceTable.CREATED_AT);
                    Boolean isActivityInstanceReadonly = (Boolean) rs.getObject(SqlConstants.ActivityInstanceTable.IS_READONLY);
                    String languageCode = rs.getString(SqlConstants.LanguageCodeTable.CODE);
                    String activityTypeName = rs.getString(SqlConstants.ActivityInstanceTable.TYPE_NAME);

                    boolean excludeFromDisplay = rs.getBoolean(SqlConstants.StudyActivityTable.EXCLUDE_FROM_DISPLAY);
                    boolean readonly = ActivityInstanceUtil.isReadonly(editTimeoutSec,
                            createdAtMillis, statusTypeCode, isActivityWriteOnce, isActivityInstanceReadonly);
                    long createdAt = rs.getLong(SqlConstants.ActivityInstanceTable.CREATED_AT);
                    boolean isFollowup = rs.getBoolean(SqlConstants.StudyActivityTable.IS_FOLLOWUP);
                    boolean isHidden = rs.getBoolean(SqlConstants.ActivityInstanceTable.IS_HIDDEN);

                    String versionTag = rs.getString(SqlConstants.ActivityVersionTable.TAG);
                    long versionId = rs.getLong(SqlConstants.ActivityVersionTable.REVISION_ID);
                    long revisionStart = rs.getLong(SqlConstants.RevisionTable.START_DATE);

                    ActivityInstanceSummary activityInstanceSummary = new ActivityInstanceSummary(
                            activityCode,
                            activityInstanceId,
                            activityInstanceGuid,
                            activityName,
                            activitySecondName,
                            activityTitle,
                            activitySubtitle,
                            activityDescription,
                            activitySummary,
                            activityTypeCode,
                            formTypeCode,
                            statusTypeCode,
                            iconBase64,
                            readonly,
                            languageCode,
                            activityTypeName,
                            excludeFromDisplay,
                            isHidden,
                            createdAt,
                            isFollowup,
                            versionTag,
                            versionId,
                            revisionStart
                    );

                    activitySummaries.add(activityInstanceSummary);
                }
            }
        } catch (SQLException e) {
            throw new DaoException("Could not lookup activity instances for user " + userGuid, e);
        }

        // Picking the appropriate language for each activity instance
        return I18nUtil.getActivityInstanceTranslation(
                activitySummaries, isoLanguageCode
        );
    }

    /**
     * Iterate through list of activity summaries and count up how many questions are answered.
     *
     * @param handle            the database handle
     * @param userGuid          the user guid
     * @param studyGuid         the study guid
     * @param activitySummaries the list of activity summaries
     */
    public void countActivitySummaryQuestionsAndAnswers(Handle handle, String userGuid, String operatorGuid, String studyGuid,
                                                        List<ActivityInstanceSummary> activitySummaries) {
        ActivityDefStore activityDefStore = ActivityDefStore.getInstance();

        Set<String> instanceGuids = activitySummaries.stream()
                .map(ActivityInstanceSummary::getActivityInstanceGuid)
                .collect(Collectors.toSet());
        Map<String, FormResponse> instanceResponses;
        try (Stream<FormResponse> responseStream = handle
                .attach(org.broadinstitute.ddp.db.dao.ActivityInstanceDao.class)
                .findFormResponsesWithAnswersByInstanceGuids(instanceGuids)) {
            instanceResponses = responseStream.collect(Collectors.toMap(ActivityResponse::getGuid, Function.identity()));
        }

        for (var summary : activitySummaries) {
            if (ActivityType.valueOf(summary.getActivityType()) == FORMS) {
                String activityCode = summary.getActivityCode();
                String versionTag = summary.getVersionTag();
                long versionId = summary.getVersionId();
                long revisionStart = summary.getRevisionStart();

                FormActivityDef formActivityDef = activityDefStore.getActivityDef(studyGuid, activityCode, versionTag);
                if (formActivityDef == null) {
                    ActivityDto activityDto = handle.attach(JdbiActivity.class)
                            .findActivityByStudyGuidAndCode(studyGuid, activityCode).get();
                    formActivityDef = handle.attach(FormActivityDao.class).findDefByDtoAndVersion(
                            activityDto, versionTag, versionId, revisionStart);
                    activityDefStore.setActivityDef(studyGuid, activityCode, versionTag, formActivityDef);
                }

                Pair<Integer, Integer> questionAndAnswerCounts = activityDefStore.countQuestionsAndAnswers(
                        handle, userGuid, operatorGuid, formActivityDef, summary.getActivityInstanceGuid(), instanceResponses);

                summary.setNumQuestions(questionAndAnswerCounts.getLeft());
                summary.setNumQuestionsAnswered(questionAndAnswerCounts.getRight());
            }
        }
    }

    /**
     * Iterate through activity instance summaries and render the naming details and summary texts, as necessary. For
     * activity instance name, the first instance may leverage content substitutions to render in it's activity number
     * (which is 1). For activity instance with number greater than 1, if there is a "second name" then the second name
     * will be used to render the name, otherwise " #N" will be appended to the original name (where N is the instance
     * number).
     *
     * @param handle    the database handle
     * @param userId    the user who owns the activity instances
     * @param summaries the list of summaries
     */
    public void renderActivitySummary(Handle handle, long userId, List<ActivityInstanceSummary> summaries) {
        if (summaries.isEmpty()) {
            return;
        }

        Set<Long> instanceIds = summaries.stream().map(ActivityInstanceSummary::getActivityInstanceId).collect(Collectors.toSet());
        var instanceDao = handle.attach(org.broadinstitute.ddp.db.dao.ActivityInstanceDao.class);
        Map<Long, Map<String, String>> substitutions;
        try (var substitionStream = instanceDao.bulkFindSubstitutions(instanceIds)) {
            substitutions = substitionStream
                    .collect(Collectors.toMap(wrapper -> wrapper.getActivityInstanceId(), wrapper -> wrapper.unwrap()));
        }
        var sharedSnapshot = I18nContentRenderer
                .newValueProviderBuilder(handle, userId)
                .build().getSnapshot();

        for (var summary : summaries) {
            if (StringUtils.isBlank(summary.getActivitySummary())) {
                continue;
            }

            Map<String, String> subs = substitutions.getOrDefault(summary.getActivityInstanceId(), new HashMap<>());
            var provider = new RenderValueProvider.Builder()
                    .setActivityInstanceNumber(summary.getInstanceNumber())
                    .withSnapshot(sharedSnapshot)
                    .withSnapshot(subs)
                    .build();
            Map<String, Object> context = new HashMap<>();
            context.put(I18nTemplateConstants.DDP, provider);

            // Render the name.
            final String nameText;
            if (summary.getInstanceNumber() > 1) {
                if (StringUtils.isNotBlank(summary.getActivitySecondName())) {
                    nameText = renderer.renderToString(summary.getActivitySecondName(), context);
                } else {
                    String originalName = renderer.renderToString(summary.getActivityName(), context);
                    nameText = originalName + " #" + summary.getInstanceNumber();
                }
            } else {
                nameText = renderer.renderToString(summary.getActivityName(), context);
            }
            summary.setActivityName(nameText);

            // Render the summary.
            String summaryText = renderer.renderToString(summary.getActivitySummary(), context);
            summary.setActivitySummary(summaryText);
        }
    }

    /**
     * Given a user, return a collection of activity instance summaries.
     *
     * @param handle       JDBC handle
     * @param userGuid     GUID of the user
     * @param studyGuid    GUID of the study
     * @param instanceGuid GUID of the activity instance
     * @return A size of sections for activity instance
     */
    public int getActivityInstanceSectionsSize(Handle handle,
                                               String userGuid,
                                               String studyGuid,
                                               String instanceGuid) {
        int result = 0;
        try {
            try (PreparedStatement stmt = handle.getConnection().prepareStatement(SECTIONS_SIZE_FOR_ACTIVITY_INSTANCE)) {
                stmt.setString(1, studyGuid);
                stmt.setString(2, userGuid);
                stmt.setString(3, instanceGuid);
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    String activityCode = rs.getString(SqlConstants.StudyActivityTable.CODE);
                    String versionTag = rs.getString(SqlConstants.ActivityVersionTable.TAG);
                    ActivityDefStore activityDefStore = ActivityDefStore.getInstance();
                    FormActivityDef formActivityDef = activityDefStore.getActivityDef(studyGuid, activityCode, versionTag);
                    if (formActivityDef == null) {
                        long versionId = rs.getLong(SqlConstants.ActivityVersionTable.REVISION_ID);
                        long revisionStart = rs.getLong(SqlConstants.RevisionTable.START_DATE);
                        Optional<ActivityDto> activityDto = handle.attach(JdbiActivity.class)
                                .findActivityByStudyGuidAndCode(studyGuid, activityCode);
                        formActivityDef = handle.attach(FormActivityDao.class).findDefByDtoAndVersion(
                                activityDto.get(), versionTag, versionId, revisionStart);
                        activityDefStore.setActivityDef(studyGuid, activityCode, versionTag, formActivityDef);
                    }
                    result = formActivityDef.getAllSections().size();
                }
            }
        } catch (SQLException e) {
            throw new DaoException("Could not lookup activity instances for user " + userGuid, e);
        }
        return result;
    }

}
