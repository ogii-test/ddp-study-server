package org.broadinstitute.ddp.studybuilder.task;

import java.io.File;
import java.nio.file.Path;
import java.time.Instant;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.broadinstitute.ddp.db.dao.ActivityDao;
import org.broadinstitute.ddp.db.dao.JdbiRevision;
import org.broadinstitute.ddp.db.dao.JdbiUmbrellaStudy;
import org.broadinstitute.ddp.db.dao.UserDao;
import org.broadinstitute.ddp.db.dto.ActivityDto;
import org.broadinstitute.ddp.db.dto.StudyDto;
import org.broadinstitute.ddp.exception.DDPException;
import org.broadinstitute.ddp.model.activity.revision.RevisionMetadata;
import org.broadinstitute.ddp.model.user.User;
import org.broadinstitute.ddp.studybuilder.ActivityBuilder;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.sqlobject.SqlObject;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class PrionMedicalV2 implements CustomTask {

    private static final Logger LOG = LoggerFactory.getLogger(PrionMedicalV2.class);
    private static final String MEDICAL_V2_FILE = "patches/medical2.conf";
    private static final String STUDY_GUID = "PRION";

    private Instant timestamp;
    private Config studyCfg;
    private Config dataCfg;
    private String versionTag;

    @Override
    public void init(Path cfgPath, Config studyCfg, Config varsCfg) {
        if (!studyCfg.getString("study.guid").equals(STUDY_GUID)) {
            throw new DDPException("This task is only for the " + STUDY_GUID + " study!");
        }
        File file = cfgPath.getParent().resolve(MEDICAL_V2_FILE).toFile();
        if (!file.exists()) {
            throw new DDPException("Data file is missing: " + file);
        }
        this.studyCfg = studyCfg;
        this.dataCfg = ConfigFactory.parseFile(file).resolveWith(varsCfg);
        this.versionTag = dataCfg.getString("versionTag");
        this.timestamp = Instant.now();
    }

    @Override
    public void run(Handle handle) {
        // Creates version 2 of Prion medical questionnaire, which just affects how the
        // longitudinal feature is implemented

        User adminUser = handle.attach(UserDao.class).findUserByGuid(studyCfg.getString("adminUser.guid")).get();
        StudyDto studyDto = handle.attach(JdbiUmbrellaStudy.class).findByStudyGuid(studyCfg.getString("study.guid"));
        var activityDao = handle.attach(ActivityDao.class);
        String activityCode = dataCfg.getString("activityCode");
        long studyId = studyDto.getId();
        long activityId = ActivityBuilder.findActivityId(handle, studyId, activityCode);
        String reason = String.format("Update activity with studyGuid=%s activityCode=%s to versionTag=%s",
                STUDY_GUID, activityCode, versionTag);
        RevisionMetadata metadata = new RevisionMetadata(timestamp.toEpochMilli(), adminUser.getId(), reason);
        JdbiRevision jdbiRevision = handle.attach(JdbiRevision.class);

        // Change version
        activityDao.changeVersion(activityId, versionTag, metadata);
        ActivityDto activityDto = activityDao.getJdbiActivity().queryActivityById(activityId);
        SqlHelper helper = handle.attach(SqlHelper.class);
        boolean hideExisting = dataCfg.getBoolean("hideExistingInstancesOnCreation");
        boolean writeOnce = dataCfg.getBoolean("writeOnce");
        int hidden = helper.updateHideExistingInstancesOnCreation(activityId, hideExisting, writeOnce);
        if (hidden != 1) {
            LOG.error("Updating hide existing instances on creation for activity {} to true returned {} instead of 1",
                    activityId, hidden); // TODO
        }
    }

    private interface SqlHelper extends SqlObject {
        @SqlUpdate("update study_activity set hide_existing_instances_on_creation = :hide, is_write_once = :writeOnce where "
                + "study_activity_id = :activityId")
        int updateHideExistingInstancesOnCreation(@Bind("activityId") long activityId, @Bind("hide") boolean hide,
                                                  @Bind("writeOnce") boolean writeOnce);
    }
}
