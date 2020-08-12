package org.broadinstitute.ddp.util;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.Storage;
import com.google.common.collect.Lists;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.lang3.StringUtils;
import org.broadinstitute.ddp.cache.LanguageStore;
import org.broadinstitute.ddp.constants.ConfigFile;
import org.broadinstitute.ddp.db.DBUtils;
import org.broadinstitute.ddp.db.TransactionWrapper;
import org.broadinstitute.ddp.db.dao.ActivityInstanceDao;
import org.broadinstitute.ddp.db.dao.ActivityInstanceStatusDao;
import org.broadinstitute.ddp.db.dao.AnswerDao;
import org.broadinstitute.ddp.db.dao.JdbiActivity;
import org.broadinstitute.ddp.db.dao.JdbiActivityInstance;
import org.broadinstitute.ddp.db.dao.JdbiClient;
import org.broadinstitute.ddp.db.dao.JdbiUmbrellaStudy;
import org.broadinstitute.ddp.db.dao.JdbiUser;
import org.broadinstitute.ddp.db.dto.ActivityInstanceDto;
import org.broadinstitute.ddp.db.dto.ClientDto;
import org.broadinstitute.ddp.db.dto.StudyDto;
import org.broadinstitute.ddp.db.dto.UserDto;
import org.broadinstitute.ddp.model.activity.types.InstanceStatusType;
import org.broadinstitute.ddp.model.address.MailAddress;
import org.broadinstitute.ddp.model.migration.StudyMigrationRun;
import org.broadinstitute.ddp.service.AddressService;
import org.broadinstitute.ddp.service.OLCService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class StudyDataLoaderMain {

    private static final Logger LOG = LoggerFactory.getLogger(StudyDataLoaderMain.class);
    private static final String DATA_GC_ID = "broad-ddp-mbc";
    private static final String DEFAULT_MIGRATION_REPORT_PATH = "/tmp/migration_reports";
    private static final String USAGE = "StudyDataLoaderMain [-h, --help] [OPTIONS] study-guid";
    private static List<String> altPidUserList;
    private List<StudyMigrationRun> migrationRunReport;
    private String reportFileName;
    private String dryRunEmail;
    private boolean isDeleteAuth0Email;
    private String mappingFileName = null;
    private String preProcessFileName = null;
    private String serviceAccountFile = null;
    private String googleBucketName = null;
    private String auth0ClientId = null;
    private Long auth0TenantId = null;
    private List<String> failedList = null;
    private List<String> skippedList = null;

    public static void main(String[] args) throws Exception {
        initDbConnection();
        Config cfg = ConfigManager.getInstance().getConfig();

        Options options = new Options();

        //todo .. take study guid from command and look for a conf file which has that study values
        //look for conf $mbc_migration_conf.json
        options.addOption("h", "help", false, "print help message");
        options.addOption("f", true, "Local File");
        options.addOption("g", false, "Use Google Bucket");
        options.addOption("m", false, "Also load mailing address");
        options.addOption("o", true, "Output/Report csv file");
        options.addOption("u", true, "Users to migrate (comma separated list of altpids)");
        options.addOption("c", true, "Auth0 client id");
        options.addOption("dryrun", false, "Test Run");
        options.addOption("prodrun", false, "Production Run");
        options.addOption("e", true, "Dry run test email");
        options.addOption("de", false, "Delete auth0 email");
        options.addOption("mf", true, "Mapping file");
        options.addOption("gsa", true, "google cloud service account file");
        options.addOption("gb", true, "google bucket file");
        options.addOption("p", false, "preprocess");
        options.addOption("pf", true, "preprocess file name");
        options.addOption("t", true, "Auth0 tenant id");
        options.addOption("psw", true, "User Hashed Passwords File");

        /**
         Command line options
         "f" : Passs localfile that you want to load into pepper using StudyDataLoaderMain
         example: -f src/test/resources/dm-survey-testdata.json

         "g" : Use files in google bucket to load into pepper.
         "m" : Include Mailing list files in the google bucket in the load
         "o" : Pass full name of file used to generate csv output/report file for current migration run
         example: -o /tmp/migrationrunreport09.csv

         "u" : Provide list of users (comma separated list of altpids") that need to be loaded/migrated
         "dryrun" : Autogenerate email ids for each participant/user during migration. Uses email set by option "e"
         sample email: foo29+1547662520564@broadinstitute.org
         "c" : auth0 client name

         "prodrun: : Use emailIds in the source data import files, no autogeneration of email ids.
         "e" : Specify email template for dryrun email generation
         example: -e foo29@broadinstitute.org
         Generated email ID will be foo29+1547662520564@broadinstitute.org

         "de" : Delete email(s) that already exist in Auth0 . Once migration run is complete, all participant/user emails that
         exist in Auth0 (marked in output csv report Auth0Collision column) will be deleted

         "mf" : Mapping file
         "gsa": google service account file
         "gb" : google bucket name
         "p" : pre-process email verification (Auth0 call) and address verification. Applies to only google bucket files
         "pf" : file name for(with) file name
         */

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        boolean hasFile = cmd.hasOption("f");
        boolean hasGoogleBucket = cmd.hasOption("g");
        boolean doMailingAddress = cmd.hasOption("m");
        boolean isDryRun = cmd.hasOption("dryrun");
        boolean isProdRun = cmd.hasOption("prodrun");
        boolean isPreProcess = cmd.hasOption("p");
        boolean hasTestEmail = cmd.hasOption("e");
        boolean hasUserList = cmd.hasOption("u");
        boolean hasServiceAccount = cmd.hasOption("gsa");
        boolean hasMappingFile = cmd.hasOption("mf");
        boolean hasBucketName = cmd.hasOption("gb");
        boolean hasHashedPasswordFile = cmd.hasOption("psw");

        if (cmd.hasOption("help")) {
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp(80, USAGE, "", options, "");
            return;
        }

        String[] positional = cmd.getArgs();
        if (positional == null || positional.length < 1) {
            System.out.println("[StudyDataLoaderMain] study guid is required");
            return;
        }

        if (!hasMappingFile) {
            throw new Exception("Please pass mapping file argument. ex: mf  src/test/resources/question_stableid_map.json");
        }

        if (hasFile == hasGoogleBucket) {
            throw new Exception("Please choose one of Local File or Google Bucket");
        }

        if (hasGoogleBucket) {
            if (!hasServiceAccount) {
                throw new Exception("Please pass service account file for Google Bucket option");
            }
            if (!hasBucketName) {
                throw new Exception("Please pass google bucket name for Google Bucket option");
            }
        }

        if (doMailingAddress && !hasGoogleBucket) {
            throw new Exception("Asking to load mailing address implies we are looking at the bucket!");
        }

        if (isDryRun == isProdRun) {
            throw new Exception("Please choose one of dryrun or prodrun");
        }

        if (isDryRun && !hasTestEmail) {
            throw new Exception("Please pass dry run test email.. ex: e foo1@broadinstitute.org");
        }

        StudyDataLoaderMain dataLoaderMain = new StudyDataLoaderMain();

        if (cmd.hasOption("o")) {
            dataLoaderMain.reportFileName = cmd.getOptionValue('o');
        }

        if (cmd.hasOption("c")) {
            dataLoaderMain.auth0ClientId = cmd.getOptionValue("c");
            if (StringUtils.isBlank(dataLoaderMain.auth0ClientId)) {
                throw new Exception("Invalid auth0 client id");
            }
        } else {
            throw new Exception("Please pass valid auth0 client id using option 'c' ");
        }

        if (cmd.hasOption("t")) {
            if (StringUtils.isBlank(cmd.getOptionValue("t"))) {
                throw new Exception("Invalid auth0 tenant id");
            }
            dataLoaderMain.auth0TenantId = Long.valueOf(cmd.getOptionValue("t"));
        } else {
            throw new Exception("Please pass valid auth0 tenant id using option 't' ");
        }

        dataLoaderMain.isDeleteAuth0Email = cmd.hasOption("de");

        if (isDryRun && hasTestEmail) {
            dataLoaderMain.dryRunEmail = cmd.getOptionValue('e');
            LOG.info("dry run email: {}", dataLoaderMain.dryRunEmail);
            if (dataLoaderMain.dryRunEmail == null || !dataLoaderMain.dryRunEmail.contains("@")
                    || !dataLoaderMain.dryRunEmail.contains(".")) {
                throw new Exception("Please pass valid dry run test email");
            }
        }

        if (hasUserList) {
            altPidUserList = Arrays.asList(cmd.getOptionValue('u').split(","));
            LOG.info("userList size: {}. users: {} ", altPidUserList.size(),
                    Arrays.toString(altPidUserList.toArray()));
        }

        dataLoaderMain.mappingFileName = cmd.getOptionValue("mf");
        if (!Paths.get(dataLoaderMain.mappingFileName).toFile().exists()) {
            throw new Exception("Invalid mapping file: " + dataLoaderMain.mappingFileName);
        }

        dataLoaderMain.preProcessFileName = cmd.getOptionValue("pf");
        if (!isPreProcess && StringUtils.isNotEmpty(dataLoaderMain.preProcessFileName)) {
            if (!Paths.get(dataLoaderMain.preProcessFileName).toFile().exists()) {
                throw new Exception("Invalid pre-processed data file: " + dataLoaderMain.preProcessFileName);
            }
        }

        if (isPreProcess) {
            LOG.info("Preprocessing Auth0 Email and Address verification");
            Instant now = Instant.now();
            dataLoaderMain.serviceAccountFile = cmd.getOptionValue("gsa");
            dataLoaderMain.googleBucketName = cmd.getOptionValue("gb");
            PreProcessedData userPreProcessedDto = dataLoaderMain.preProcessAddressAndEmailVerification(cfg, null);
            Gson gson = new GsonBuilder().serializeNulls().create();
            String preProcessedData = gson.toJson(userPreProcessedDto);
            if (StringUtils.isBlank(dataLoaderMain.preProcessFileName)) {
                String dateTime = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                String fileName = dateTime.concat("-preProcessedData.txt");
                dataLoaderMain.preProcessFileName = fileName;
                File directory = new File(DEFAULT_MIGRATION_REPORT_PATH);
                if (!directory.exists()) {
                    directory.mkdir();
                }
                Files.write(Paths.get(DEFAULT_MIGRATION_REPORT_PATH, fileName), preProcessedData.getBytes());
            } else {
                Files.write(Paths.get(".", dataLoaderMain.preProcessFileName), preProcessedData.getBytes());
            }
            LOG.info("Created pre-processed data file: {} ", dataLoaderMain.preProcessFileName);
            Instant now2 = Instant.now();
            Duration duration = Duration.between(now, now2);
            long secs = duration.getSeconds();
            long mins = duration.toMinutes();
            LOG.info("Completed preprocess of {} GB files: {} ", userPreProcessedDto.getAltpidBucketDataMap().size(), new Date());
            LOG.info("Google Bucket preprocess took : {} secs .. minutes : {} ", secs, mins);

            //Now try to read the file into memory just to make sure its good
            PreProcessedData preProcessedDataJson = new Gson().fromJson(new FileReader(dataLoaderMain.preProcessFileName),
                    PreProcessedData.class);
            LOG.info("Loaded pre-processed data from {}", dataLoaderMain.preProcessFileName);
            Map<String, Map> bucketData = preProcessedDataJson.getAltpidBucketDataMap();
            LOG.info("Loaded bucket Data altpid count: {} ", bucketData.size());
            String firstAltpid = bucketData.keySet().iterator().next();
            Map<String, JsonElement> surveyData = bucketData.get(firstAltpid);
            LOG.info("first altpid : {} .. surveyName: {} ",
                    firstAltpid, surveyData.keySet().iterator().next(), surveyData.values().size());
        } else if (hasGoogleBucket) {
            dataLoaderMain.serviceAccountFile = cmd.getOptionValue("gsa");
            dataLoaderMain.googleBucketName = cmd.getOptionValue("gb");

            try {
                if (doMailingAddress) {
                    dataLoaderMain.processGoogleBucketMailingListFiles(cfg, positional[0]);
                }
                if (hasHashedPasswordFile) {
                    dataLoaderMain.processGoogleBucketParticipantFiles(cfg, positional[0], isDryRun, cmd.getOptionValue("psw"));
                } else {
                    dataLoaderMain.processGoogleBucketParticipantFiles(cfg, positional[0], isDryRun);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else if (hasFile) {
            try {
                dataLoaderMain.processLocalFile(cfg, positional[0], cmd.getOptionValue('f'), isDryRun);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public static void initDbConnection() {
        Config cfg = ConfigManager.getInstance().getConfig();
        Config sqlConfig = ConfigFactory.parseResources(ConfigFile.SQL_CONF);

        String dbUrl = cfg.getString(ConfigFile.DB_URL);
        LOG.info("Initializing db pool for " + dbUrl);
        int maxConnections = cfg.getInt(ConfigFile.NUM_POOLED_CONNECTIONS);
        TransactionWrapper.reset();
        TransactionWrapper.init(new TransactionWrapper.DbConfiguration(TransactionWrapper.DB.APIS, maxConnections, dbUrl));
        TransactionWrapper.useTxn(TransactionWrapper.DB.APIS, LanguageStore::init);
        DBUtils.loadDaoSqlCommands(sqlConfig);
    }

    public Map<String, JsonElement> loadSourceDataFile(String participantData) {
        Map<String, JsonElement> surveyDataMap = new HashMap<>();
        JsonElement data;
        try {
            data = new Gson().fromJson(participantData, new TypeToken<JsonObject>() {
            }.getType());
        } catch (Exception e) {
            LOG.error("Failed to load file data as JSON ", e);
            return null;
        }
        JsonObject dataObj = data.getAsJsonObject();
        JsonElement user = dataObj.get("user");
        JsonElement datstatParticipantData = user.getAsJsonObject().get("datstatparticipantdata");

        JsonElement surveyData = user.getAsJsonObject().get("datstatsurveydata");
        JsonElement medicalHistorySurveyData = surveyData.getAsJsonObject().get("atcp_registry_questionnaire");
        JsonElement atConsentSurveyData = surveyData.getAsJsonObject().get("ConsentSurvey");
        JsonElement atRegistrationSurveyData = surveyData.getAsJsonObject().get("RegistrationSurvey");
        JsonElement atContactingPhysicianSurveyData = surveyData.getAsJsonObject().get("ContactingPhysicianSurvey");
        JsonElement atGenomeStudySurveyData = surveyData.getAsJsonObject().get("GenomeStudySurvey");
        JsonElement atAssentSurveyData = surveyData.getAsJsonObject().get("AssentSurvey");
        JsonElement releaseSurveyData = surveyData.getAsJsonObject().get("releasesurvey");
        JsonElement bdreleaseSurveyData = surveyData.getAsJsonObject().get("bdreleasesurvey");
        JsonElement aboutyouSurveyData = surveyData.getAsJsonObject().get("aboutyousurvey");
        JsonElement consentSurveyData = surveyData.getAsJsonObject().get("consentsurvey");
        JsonElement bdconsentSurveyData = surveyData.getAsJsonObject().get("bdconsentsurvey");
        JsonElement combinedConsentSurveyData = surveyData.getAsJsonObject().get("combinedconsentsurvey");
        JsonElement followupSurveyData = surveyData.getAsJsonObject().get("followupsurvey");

        surveyDataMap.put("datstatparticipantdata", datstatParticipantData);
        surveyDataMap.put("medicalhistorysurvey", medicalHistorySurveyData);
        surveyDataMap.put("atconsentsurvey", atConsentSurveyData);
        surveyDataMap.put("atregistrationsurvey", atRegistrationSurveyData);
        surveyDataMap.put("atcontactingphysiciansurvey", atContactingPhysicianSurveyData);
        surveyDataMap.put("atgenomestudysurvey", atGenomeStudySurveyData);
        surveyDataMap.put("atassentsurvey", atAssentSurveyData);
        surveyDataMap.put("releasesurvey", releaseSurveyData);
        surveyDataMap.put("bdreleasesurvey", bdreleaseSurveyData);
        surveyDataMap.put("aboutyousurvey", aboutyouSurveyData);
        surveyDataMap.put("consentsurvey", consentSurveyData);
        surveyDataMap.put("bdconsentsurvey", bdconsentSurveyData);
        surveyDataMap.put("combinedconsentsurvey", combinedConsentSurveyData);
        surveyDataMap.put("followupsurvey", followupSurveyData);
        return surveyDataMap;
    }

    public Map<String, JsonElement> loadDataMapping(String mappingFileName) {

        File mapFile = new File(mappingFileName);
        Map<String, JsonElement> mappingData = new HashMap<>();

        try {
            JsonElement data = new Gson().fromJson(new FileReader(mapFile), new TypeToken<JsonObject>() {
            }.getType());
            JsonObject dataObj = data.getAsJsonObject();
            JsonElement studyElement = dataObj.get("study");
            JsonElement surveyElement = studyElement.getAsJsonObject().get("survey");
            JsonArray surveys = surveyElement.getAsJsonArray();
            for (JsonElement thisElement : surveys) {
                JsonElement surveyName = thisElement.getAsJsonObject().get("name");
                mappingData.put(surveyName.getAsString(), thisElement);
            }
            //add datastatparticipantdata
            mappingData.put("datstatparticipantdata", studyElement.getAsJsonObject().get("datstatparticipantdata"));

        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        return mappingData;
    }

    public void processLocalFile(Config cfg, String studyGuid, String fileName, boolean dryRun) throws Exception {
        StudyDataLoader dataLoader = new StudyDataLoader(cfg);
        final OLCService olcService = new OLCService(cfg.getString(ConfigFile.GEOCODING_API_KEY));
        final AddressService addressService = new AddressService(cfg.getString(ConfigFile.EASY_POST_API_KEY),
                cfg.getString(ConfigFile.GEOCODING_API_KEY));

        //load mapping data
        Map<String, JsonElement> mappingData = loadDataMapping(mappingFileName);
        //load source survey data
        String data = new String(Files.readAllBytes(Paths.get(fileName)));
        Map<String, JsonElement> surveyDataMap = loadSourceDataFile(data);

        setRunEmail(dryRun, surveyDataMap.get("datstatparticipantdata"));
        migrationRunReport = new ArrayList<>();
        failedList = new ArrayList<>();
        skippedList = new ArrayList<>();
        processParticipant(studyGuid, surveyDataMap, mappingData, dataLoader, null, addressService, olcService);

        try {
            createReport(migrationRunReport);
        } catch (Exception e) {
            LOG.error("Failed to create migration run report. ", e);
        }

        if (isDeleteAuth0Email) {
            deleteAuth0Emails(cfg, migrationRunReport);
        }
    }

    private PreProcessedData preProcessAddressAndEmailVerification(Config cfg, StudyDataLoader dataLoader) throws Exception {
        Bucket bucket = getGoogleBucket();
        if (dataLoader == null) {
            dataLoader = new StudyDataLoader(cfg);
        }
        StudyDataLoader studyDataLoader = dataLoader;
        List<String> existingAuth0Emails = new ArrayList<>();
        Map<String, String> userEmailMap = new HashMap<>();
        Map<String, MailAddress> userAddressMap = new HashMap<>();
        final OLCService olcService = new OLCService(cfg.getString(ConfigFile.GEOCODING_API_KEY));
        final AddressService addressService = new AddressService(cfg.getString(ConfigFile.EASY_POST_API_KEY),
                cfg.getString(ConfigFile.GEOCODING_API_KEY));
        //load all Bucket data into memory
        Map<String, Map> altpidBucketDataMap = new HashMap<>();
        for (Blob file : bucket.list().iterateAll()) {
            if (!file.getName().startsWith("Participant_")) {
                LOG.info("Skipping bucket file: {}", file.getName());
                continue;
                //not a participant data .. continue to next file.
            }
            String data = new String(file.getContent());
            Map<String, JsonElement> surveyDataMap = null;
            try {
                //load source survey data
                surveyDataMap = loadSourceDataFile(data);
            } catch (JsonSyntaxException e) {
                e.printStackTrace();
                LOG.error("JSON Exception: while processing participant file: " + e.toString());
                continue;
            }

            JsonElement datstatData = surveyDataMap.get("datstatparticipantdata");
            String altpid = datstatData.getAsJsonObject().get("datstat_altpid").getAsString();
            if (altPidUserList != null && !altPidUserList.isEmpty() && !altPidUserList.contains(altpid)) {
                continue;
                //skip this one
            }

            if (StringUtils.isNotBlank(altpid)) {
                altpidBucketDataMap.put(altpid, surveyDataMap);
            } else {
                LOG.error("AltPid null for participant file: {} skipping the file ", file.getName());
                continue;
            }

            String email = !datstatData.getAsJsonObject().get("datstat_email").isJsonNull()
                    ? datstatData.getAsJsonObject().get("datstat_email").getAsString() : "";
            userEmailMap.put(altpid, email.toLowerCase());

            String phoneNumber = null;
            JsonElement releaseSurvey = surveyDataMap.get("releasesurvey");
            if (releaseSurvey != null && !releaseSurvey.isJsonNull()) {
                JsonElement phoneNumberEl = releaseSurvey.getAsJsonObject().get("phone_number");
                if (phoneNumberEl != null && !phoneNumberEl.isJsonNull()) {
                    phoneNumber = phoneNumberEl.getAsString();
                }
            }
            final String phoneNum = phoneNumber;
            TransactionWrapper.useTxn(handle -> {
                MailAddress address = studyDataLoader.getUserAddress(handle, datstatData, phoneNum, olcService, addressService);
                userAddressMap.put(altpid, address);
            });
        }

        //make auth0call to verify if User account already exists by Email
        Set<String> emails = new HashSet<>(userEmailMap.values());
        Map<String, String> existingEmails = studyDataLoader.verifyAuth0Users(emails);
        existingAuth0Emails.addAll(existingEmails.keySet());
        if (!existingAuth0Emails.isEmpty()) {
            LOG.warn("Found {} existing emails \n{} ", existingAuth0Emails.size(), Arrays.toString(existingAuth0Emails.toArray()));
        } else {
            LOG.info("NO existing emails found.");
        }
        LOG.info("Apltpid Map size: {} ", altpidBucketDataMap.size());

        return new PreProcessedData(userEmailMap, userAddressMap, existingAuth0Emails, altpidBucketDataMap);
    }

    private Bucket getGoogleBucket() throws Exception {
        GoogleCredentials credentials = GoogleCredentials.fromStream(
                new FileInputStream(serviceAccountFile))
                .createScoped(Lists.newArrayList("https://www.googleapis.com/auth/cloud-platform"));
        Storage storage = GoogleBucketUtil.getStorage(credentials, DATA_GC_ID);
        Bucket bucket = storage.get(googleBucketName);
        return bucket;
    }

    public void processGoogleBucketParticipantFiles(Config cfg, String studyGuid, boolean dryRun) throws Exception {

        LOG.info("Processing google bucket files. {} ", new Date());
        Instant now = Instant.now();
        //Download files from Google storage
        //iterate through All buckets
        StudyDataLoader dataLoader = new StudyDataLoader(cfg);
        migrationRunReport = new ArrayList<>();
        skippedList = new ArrayList<>();
        failedList = new ArrayList<>();

        PreProcessedData preProcessedData;
        if (StringUtils.isNotEmpty(preProcessFileName)) {
            //load pre-processed data from File
            preProcessedData = new Gson().fromJson(new FileReader(preProcessFileName), PreProcessedData.class);
            LOG.info("using pre-processed data from {}", preProcessFileName);
        } else {
            //load it now
            preProcessedData = preProcessAddressAndEmailVerification(cfg, dataLoader);
            LOG.info("loaded pre-processed data. {} ", new Date());
        }
        Instant nowPreprocessDone = Instant.now();
        Duration duration = Duration.between(now, nowPreprocessDone);
        long secs = duration.getSeconds();
        long mins = duration.toMinutes();
        LOG.info("Completed preprocess for GB files: {} .. {}", preProcessedData.getAltpidBucketDataMap().size(), new Date());
        LOG.info("Bucket preprocess load took : {} secs .. minutes : {} ", secs, mins);

        //load mapping data
        Map<String, JsonElement> mappingData = loadDataMapping(mappingFileName);
        final OLCService olcService = new OLCService(cfg.getString(ConfigFile.GEOCODING_API_KEY));
        final AddressService addressService = new AddressService(cfg.getString(ConfigFile.EASY_POST_API_KEY),
                cfg.getString(ConfigFile.GEOCODING_API_KEY));

        Map<String, Map> altpidBucketDataMap = preProcessedData.getAltpidBucketDataMap();
        for (String altpid : altpidBucketDataMap.keySet()) {
            Map<String, JsonElement> surveyDataMap = altpidBucketDataMap.get(altpid);

            JsonElement datstatData = surveyDataMap.get("datstatparticipantdata");
            if (datstatData.getAsJsonObject().get("datstat_email").isJsonNull()) {
                continue;
            }
            String email = datstatData.getAsJsonObject().get("datstat_email").getAsString().toLowerCase();
            setRunEmail(dryRun, datstatData);

            if (!dryRun && preProcessedData.getAuth0ExistingEmails().contains(email)) {
                LOG.error("Skipped altpid: {} . Email : {} already exists in Auth0. ", altpid, email);
                skippedList.add(altpid);
                continue;
            }
            if (altPidUserList == null || altPidUserList.isEmpty() || altPidUserList.contains(altpid)) {
                processParticipant(studyGuid, surveyDataMap, mappingData, dataLoader,
                        preProcessedData.getUserAddressData().get(altpid), addressService, olcService);
            }
        }
        try {
            createReport(migrationRunReport);
        } catch (Exception e) {
            LOG.error("Failed to create migration run report. ", e);
        }

        if (isDeleteAuth0Email) {
            deleteAuth0Emails(cfg, migrationRunReport);
        }
        LOG.info("completed processing google bucket files");
        Instant nowDone = Instant.now();
        duration = Duration.between(now, nowDone);
        secs = duration.getSeconds();
        mins = duration.toMinutes();
        LOG.info("Completed processing & loading GB files {}", preProcessedData.getAltpidBucketDataMap().size(), new Date());
        LOG.info("Bucket participant file load took : {} secs .. minutes : {} ", secs, mins);
        if (!failedList.isEmpty()) {
            LOG.error("Failed to load {} altpids: {} ", failedList.size(), Arrays.toString(failedList.toArray()));
        } else {
            LOG.info("NO failed load of Altpids...");
        }
        if (!skippedList.isEmpty()) {
            LOG.warn("Skipped {} altpids: {} ", skippedList.size(), Arrays.toString(skippedList.toArray()));
        } else {
            LOG.info("NO skipped Altpids...");
        }
    }

    public void processGoogleBucketParticipantFiles(Config cfg, String studyGuid, boolean dryRun, String pswPath) throws Exception {

        LOG.info("Processing google bucket files. {} ", new Date());
        Instant now = Instant.now();
        //Download files from Google storage
        //iterate through All buckets
        StudyDataLoader dataLoader = new StudyDataLoader(cfg);
        migrationRunReport = new ArrayList<>();
        skippedList = new ArrayList<>();
        failedList = new ArrayList<>();

        PreProcessedData preProcessedData;
        if (StringUtils.isNotEmpty(preProcessFileName)) {
            //load pre-processed data from File
            preProcessedData = new Gson().fromJson(new FileReader(preProcessFileName), PreProcessedData.class);
            LOG.info("using pre-processed data from {}", preProcessFileName);
        } else {
            //load it now
            preProcessedData = preProcessAddressAndEmailVerification(cfg, dataLoader);
            LOG.info("loaded pre-processed data. {} ", new Date());
        }
        Instant nowPreprocessDone = Instant.now();
        Duration duration = Duration.between(now, nowPreprocessDone);
        long secs = duration.getSeconds();
        long mins = duration.toMinutes();
        LOG.info("Completed preprocess for GB files: {} .. {}", preProcessedData.getAltpidBucketDataMap().size(), new Date());
        LOG.info("Bucket preprocess load took : {} secs .. minutes : {} ", secs, mins);

        //load mapping data
        Map<String, JsonElement> mappingData = loadDataMapping(mappingFileName);
        final OLCService olcService = new OLCService(cfg.getString(ConfigFile.GEOCODING_API_KEY));
        final AddressService addressService = new AddressService(cfg.getString(ConfigFile.EASY_POST_API_KEY),
                cfg.getString(ConfigFile.GEOCODING_API_KEY));

        Map<String, Map> altpidBucketDataMap = preProcessedData.getAltpidBucketDataMap();
        String hashedPasswordsData = new String(Files.readAllBytes(Paths.get(pswPath)));
        JsonElement hashedPasswordsJson;
        try {
            hashedPasswordsJson = new Gson().fromJson(hashedPasswordsData, new TypeToken<JsonArray>() {
            }.getType());
        } catch (Exception e) {
            LOG.error("Failed to load file data as JSON ", e);
            return;
        }
        JsonArray hashedPasswordsJsonArray = hashedPasswordsJson.getAsJsonArray();
        for (String altpid : altpidBucketDataMap.keySet()) {
            Map<String, JsonElement> surveyDataMap = altpidBucketDataMap.get(altpid);

            JsonElement datstatData = surveyDataMap.get("datstatparticipantdata");
            for (JsonElement item: hashedPasswordsJsonArray) {
                if (item.getAsJsonObject().has(altpid)
                        && item.getAsJsonObject().get(altpid) != null
                        && !item.getAsJsonObject().get(altpid).isJsonNull()) {
                    surveyDataMap.get("datstatparticipantdata").getAsJsonObject()
                            .add("password", item.getAsJsonObject().get(altpid));
                }
            }
            if (datstatData.getAsJsonObject().get("datstat_email").isJsonNull()) {
                continue;
            }
            String email = datstatData.getAsJsonObject().get("datstat_email").getAsString().toLowerCase();
            setRunEmail(dryRun, datstatData);

            if (!dryRun && preProcessedData.getAuth0ExistingEmails().contains(email)) {
                LOG.error("Skipped altpid: {} . Email : {} already exists in Auth0. ", altpid, email);
                skippedList.add(altpid);
                continue;
            }
            if (altPidUserList == null || altPidUserList.isEmpty() || altPidUserList.contains(altpid)) {
                processParticipant(studyGuid, surveyDataMap, mappingData, dataLoader,
                        preProcessedData.getUserAddressData().get(altpid), addressService, olcService);
            }
        }
        try {
            createReport(migrationRunReport);
        } catch (Exception e) {
            LOG.error("Failed to create migration run report. ", e);
        }

        if (isDeleteAuth0Email) {
            deleteAuth0Emails(cfg, migrationRunReport);
        }
        LOG.info("completed processing google bucket files");
        Instant nowDone = Instant.now();
        duration = Duration.between(now, nowDone);
        secs = duration.getSeconds();
        mins = duration.toMinutes();
        LOG.info("Completed processing & loading GB files {}", preProcessedData.getAltpidBucketDataMap().size(), new Date());
        LOG.info("Bucket participant file load took : {} secs .. minutes : {} ", secs, mins);
        if (!failedList.isEmpty()) {
            LOG.error("Failed to load {} altpids: {} ", failedList.size(), Arrays.toString(failedList.toArray()));
        } else {
            LOG.info("NO failed load of Altpids...");
        }
        if (!skippedList.isEmpty()) {
            LOG.warn("Skipped {} altpids: {} ", skippedList.size(), Arrays.toString(skippedList.toArray()));
        } else {
            LOG.info("NO skipped Altpids...");
        }
    }

    @SuppressWarnings("checkstyle:WhitespaceAfter")
    private void processParticipant(String studyGuid, Map<String, JsonElement> sourceData,
                                    Map<String, JsonElement> mappingData, StudyDataLoader dataLoader,
                                    MailAddress address, AddressService addressService, OLCService olcService) {

        JsonElement datstatParticipantData = sourceData.get("datstatparticipantdata");
        JsonElement datstatParticipantMappingData = mappingData.get("datstatparticipantdata");

        String altpid = datstatParticipantData.getAsJsonObject().get("datstat_altpid").getAsString();
        String emailAddress = datstatParticipantData.getAsJsonObject().get("datstat_email").getAsString().toLowerCase();
        String createdAt = datstatParticipantData.getAsJsonObject().get("datstat_created").getAsString();
        LOG.info("loading participant: {} email: {} ", altpid, emailAddress);

        TransactionWrapper.useTxn(handle -> {
            String userGuid = null;
            Boolean hasAboutYou = false;
            Boolean hasConsent = false;
            Boolean hasTissueConsent = false;
            Boolean hasBloodConsent = false;
            Boolean hasRelease = false;
            Boolean hasBloodRelease = false;
            Boolean hasFollowup = false;
            Boolean isSuccess = false;
            Boolean previousRun = false;
            Boolean hasMedicalHistory = false;
            Boolean hasATConsent = false;
            Boolean hasATRegistration = false;
            Boolean hasATContactingPhysician = false;
            Boolean hasATGenomeStudy = false;
            Boolean hasATAssent = false;
            StudyMigrationRun migrationRun;

            boolean auth0Collision = false;
            try {
                //verify if participant is already loaded..
                JdbiUser jdbiUser = handle.attach(JdbiUser.class);
                userGuid = jdbiUser.getUserGuidByAltpid(altpid);
                if (userGuid == null) {
                    dataLoader.deleteExistingAuth0User(emailAddress);
                    JdbiActivity jdbiActivity = handle.attach(JdbiActivity.class);
                    ActivityInstanceDao activityInstanceDao = handle.attach(ActivityInstanceDao.class);
                    ActivityInstanceStatusDao activityInstanceStatusDao = handle.attach(ActivityInstanceStatusDao.class);
                    JdbiUmbrellaStudy jdbiUmbrellaStudy = handle.attach(JdbiUmbrellaStudy.class);
                    JdbiActivityInstance jdbiActivityInstance = handle.attach(JdbiActivityInstance.class);

                    String phoneNumber = null;
                    JsonElement releaseSurvey = sourceData.get("releasesurvey");
                    if (releaseSurvey != null && !releaseSurvey.isJsonNull()) {
                        JsonElement phoneNumberEl = releaseSurvey.getAsJsonObject().get("phone_number");
                        if (phoneNumberEl != null && !phoneNumberEl.isJsonNull()) {
                            phoneNumber = phoneNumberEl.getAsString();
                        }
                    }
                    StudyDto studyDto = jdbiUmbrellaStudy.findByStudyGuid(studyGuid);
                    Optional<ClientDto> clientDtoOpt = handle.attach(JdbiClient.class)
                            .findByAuth0ClientIdAndAuth0TenantId(auth0ClientId, auth0TenantId);
                    if (clientDtoOpt.isEmpty()) {
                        throw new Exception(
                                String.format("No client found for auth0 client %s, auth0 tenant %d", auth0ClientId, auth0TenantId)
                        );
                    }
                    ClientDto clientDto = clientDtoOpt.get();

                    long studyId = studyDto.getId();
                    userGuid = dataLoader.loadParticipantData(handle, datstatParticipantData, datstatParticipantMappingData,
                            phoneNumber, studyDto, clientDto, address, olcService, addressService);
                    UserDto userDto = jdbiUser.findByUserGuid(userGuid);

                    hasAboutYou = (sourceData.get("aboutyousurvey") != null && !sourceData.get("aboutyousurvey").isJsonNull());
                    hasTissueConsent = (sourceData.get("consentsurvey") != null && !sourceData.get("consentsurvey").isJsonNull());
                    hasBloodConsent = (sourceData.get("bdconsentsurvey") != null && !sourceData.get("bdconsentsurvey").isJsonNull());
                    hasConsent = (sourceData.get("combinedconsentsurvey") != null && !sourceData.get("combinedconsentsurvey").isJsonNull());
                    hasRelease = (sourceData.get("releasesurvey") != null && !sourceData.get("releasesurvey").isJsonNull());
                    hasBloodRelease = (sourceData.get("bdreleasesurvey") != null && !sourceData.get("bdreleasesurvey").isJsonNull());
                    hasFollowup = (sourceData.get("followupsurvey") != null && !sourceData.get("followupsurvey").isJsonNull());
                    hasMedicalHistory = (sourceData.get("medicalhistorysurvey") != null && !sourceData
                            .get("medicalhistorysurvey").isJsonNull());
                    hasATConsent = (sourceData.get("atconsentsurvey")) != null && !sourceData
                            .get("atconsentsurvey").isJsonNull();
                    hasATRegistration = (sourceData.get("atregistrationsurvey")) != null && !sourceData
                            .get("atregistrationsurvey").isJsonNull();
                    hasATContactingPhysician = (sourceData.get("atcontactingphysiciansurvey")) != null && !sourceData
                            .get("atcontactingphysiciansurvey").isJsonNull();
                    hasATGenomeStudy = (sourceData.get("atgenomestudysurvey")) != null && !sourceData
                            .get("atgenomestudysurvey").isJsonNull();
                    hasATAssent = (sourceData.get("atassentsurvey")) != null && !sourceData
                            .get("atassentsurvey").isJsonNull();

                    var answerDao = handle.attach(AnswerDao.class);
                    //create prequal
                    dataLoader.createPrequal(handle,
                            userGuid, studyId,
                            createdAt,
                            jdbiActivity,
                            activityInstanceDao,
                            activityInstanceStatusDao,
                            answerDao);


                    if (hasATRegistration) {
                        String activityCode = mappingData.get("RegistrationSurvey").getAsJsonObject()
                                .get("activity_code").getAsString();
                        List<ActivityInstanceDto> activityInstanceDtoList = jdbiActivityInstance
                                .findAllByUserGuidAndActivityCode(userGuid, activityCode, studyId);
                        activityInstanceDao.deleteByInstanceGuid(activityInstanceDtoList.get(0).getGuid());
                        ActivityInstanceDto instanceDto = dataLoader.createActivityInstance(sourceData.get("atregistrationsurvey"),
                                userGuid, studyId,
                                activityCode, createdAt,
                                jdbiActivity,
                                activityInstanceDao,
                                activityInstanceStatusDao);
                        dataLoader.loadATRegistrationSurveyData(handle, sourceData.get("atregistrationsurvey"),
                                mappingData.get("RegistrationSurvey"),
                                studyDto, userDto, instanceDto,
                                answerDao);
                    }

                    if (hasATConsent) {
                        String activityCode = mappingData.get("ConsentSurvey").getAsJsonObject()
                                .get("activity_code").getAsString();
                        List<ActivityInstanceDto> activityInstanceDtoList = jdbiActivityInstance
                                .findAllByUserGuidAndActivityCode(userGuid, activityCode, studyId);
                        activityInstanceDao.deleteByInstanceGuid(activityInstanceDtoList.get(0).getGuid());
                        ActivityInstanceDto instanceDto = dataLoader.createActivityInstance(sourceData.get("atconsentsurvey"),
                                userGuid, studyId,
                                activityCode, createdAt,
                                jdbiActivity,
                                activityInstanceDao,
                                activityInstanceStatusDao);
                        dataLoader.loadATConsentSurveyData(handle, sourceData.get("atconsentsurvey"),
                                mappingData.get("ConsentSurvey"),
                                studyDto, userDto, instanceDto,
                                answerDao);
                    }

                    if (hasATAssent) {
                        String activityCode = mappingData.get("AssentSurvey").getAsJsonObject()
                                .get("activity_code").getAsString();
                        ActivityInstanceDto instanceDto = dataLoader.createActivityInstance(sourceData.get("atassentsurvey"),
                                userGuid, studyId,
                                activityCode, createdAt,
                                jdbiActivity,
                                activityInstanceDao,
                                activityInstanceStatusDao);
                        dataLoader.loadATAssentSurveyData(handle, sourceData.get("atassentsurvey"),
                                mappingData.get("AssentSurvey"),
                                studyDto, userDto, instanceDto,
                                answerDao);
                    }

                    if (hasATContactingPhysician) {
                        String activityCode = mappingData.get("ContactingPhysicianSurvey").getAsJsonObject()
                                .get("activity_code").getAsString();
                        List<ActivityInstanceDto> activityInstanceDtoList = jdbiActivityInstance
                                .findAllByUserGuidAndActivityCode(userGuid, activityCode, studyId);
                        activityInstanceDao.deleteByInstanceGuid(activityInstanceDtoList.get(0).getGuid());
                        ActivityInstanceDto instanceDto = dataLoader.createActivityInstance(sourceData.get("atcontactingphysiciansurvey"),
                                userGuid, studyId,
                                activityCode, createdAt,
                                jdbiActivity,
                                activityInstanceDao,
                                activityInstanceStatusDao);
                        dataLoader.loadATContactingPhysicianSurveyData(handle, sourceData.get("atcontactingphysiciansurvey"),
                                mappingData.get("ContactingPhysicianSurvey"),
                                studyDto, userDto, instanceDto,
                                answerDao);
                    }

                    if (hasMedicalHistory) {
                        String activityCode = mappingData.get("atcp_registry_questionnaire").getAsJsonObject()
                                .get("activity_code").getAsString();
                        List<ActivityInstanceDto> activityInstanceDtoList = jdbiActivityInstance
                                .findAllByUserGuidAndActivityCode(userGuid, activityCode, studyId);
                        activityInstanceDao.deleteByInstanceGuid(activityInstanceDtoList.get(0).getGuid());
                        ActivityInstanceDto instanceDto = dataLoader.createActivityInstance(sourceData.get("medicalhistorysurvey"),
                                userGuid, studyId,
                                activityCode, createdAt,
                                jdbiActivity,
                                activityInstanceDao,
                                activityInstanceStatusDao);
                        dataLoader.loadMedicalHistorySurveyData(handle, sourceData.get("medicalhistorysurvey"),
                                mappingData.get("atcp_registry_questionnaire"),
                                studyDto, userDto, instanceDto,
                                answerDao);
                    }

                    if (hasATGenomeStudy) {
                        String activityCode = mappingData.get("GenomeStudySurvey").getAsJsonObject()
                                .get("activity_code").getAsString();
                        ActivityInstanceDto instanceDto = dataLoader.createActivityInstance(sourceData.get("atgenomestudysurvey"),
                                userGuid, studyId,
                                activityCode, createdAt,
                                jdbiActivity,
                                activityInstanceDao,
                                activityInstanceStatusDao);
                        dataLoader.loadATGenomeStudySurveyData(handle, sourceData.get("atgenomestudysurvey"),
                                mappingData.get("GenomeStudySurvey"),
                                studyDto, userDto, instanceDto,
                                answerDao);
                    }

                    int registrationStatus = datstatParticipantData.getAsJsonObject().get("registration_status").getAsInt();
                    LocalDateTime lastSubmitedDateTime = LocalDateTime.parse(datstatParticipantData.getAsJsonObject()
                            .get("datstat_lastmodified").getAsString(), dataLoader.formatter);
                    Instant lastSubmitedInstant = lastSubmitedDateTime.toInstant(ZoneOffset.UTC);
                    long lastSubmitedToMillis = lastSubmitedInstant.toEpochMilli();

                    //if registration status is equal or more than 7 we submit review and submission activity
                    if (registrationStatus >= 7) {
                        List<ActivityInstanceDto> activityInstanceDtoList = jdbiActivityInstance
                                .findAllByUserGuidAndActivityCode(userGuid, "REVIEW_AND_SUBMISSION", studyId);
                        activityInstanceStatusDao.deleteAllByInstanceGuid(activityInstanceDtoList.get(0).getGuid());
                        activityInstanceStatusDao.insertStatus(activityInstanceDtoList.get(0).getId(), InstanceStatusType.COMPLETE,
                                lastSubmitedToMillis, userGuid);
                    }


                    if (hasAboutYou) {
                        String activityCode = mappingData.get("aboutyousurvey").getAsJsonObject().get("activity_code").getAsString();
                        ActivityInstanceDto instanceDto = dataLoader.createActivityInstance(sourceData.get("aboutyousurvey"),
                                userGuid, studyId,
                                activityCode, createdAt,
                                jdbiActivity,
                                activityInstanceDao,
                                activityInstanceStatusDao);
                        dataLoader.loadAboutYouSurveyData(handle, sourceData.get("aboutyousurvey"),
                                mappingData.get("aboutyousurvey"),
                                studyDto, userDto, instanceDto,
                                answerDao);
                    }

                    if (hasConsent) {
                        //For MBC just create activity
                        //String activityCode = mappingData.get("consentsurvey").getAsJsonObject().get("activity_code").getAsString();
                        String activityCode = "CONSENT";

                        ActivityInstanceDto instanceDto = dataLoader.createActivityInstance(sourceData.get("combinedconsentsurvey"),
                                userGuid, studyId,
                                activityCode, createdAt,
                                jdbiActivity,
                                activityInstanceDao,
                                activityInstanceStatusDao);

                        /*dataLoader.loadConsentSurveyData(handle, sourceData.get("combinedconsentsurvey"),
                                mappingData.get("consentsurvey"),
                                studyDto, userDto, instanceDto,
                                answerDao);*/
                    }

                    if (hasTissueConsent) {
                        String activityCode = mappingData.get("tissueconsentsurvey").getAsJsonObject().get("activity_code").getAsString();
                        ActivityInstanceDto instanceDto = dataLoader.createActivityInstance(sourceData.get("consentsurvey"),
                                userGuid, studyId,
                                activityCode, createdAt,
                                jdbiActivity,
                                activityInstanceDao,
                                activityInstanceStatusDao);
                        dataLoader.loadTissueConsentSurveyData(handle, sourceData.get("consentsurvey"),
                                mappingData.get("tissueconsentsurvey"),
                                studyDto, userDto, instanceDto,
                                answerDao);
                    }

                    if (hasBloodConsent) {
                        String activityCode = mappingData.get("bdconsentsurvey").getAsJsonObject().get("activity_code").getAsString();
                        ActivityInstanceDto instanceDto = dataLoader.createActivityInstance(sourceData.get("bdconsentsurvey"),
                                userGuid, studyId,
                                activityCode, createdAt,
                                jdbiActivity,
                                activityInstanceDao,
                                activityInstanceStatusDao);
                        dataLoader.loadBloodConsentSurveyData(handle, sourceData.get("bdconsentsurvey"),
                                mappingData.get("bdconsentsurvey"),
                                studyDto, userDto, instanceDto, answerDao);
                    }

                    if (hasRelease) {
                        String activityCode = mappingData.get("releasesurvey").getAsJsonObject().get("activity_code").getAsString();
                        ActivityInstanceDto instanceDto = dataLoader.createActivityInstance(sourceData.get("releasesurvey"),
                                userGuid, studyId,
                                activityCode, createdAt,
                                jdbiActivity,
                                activityInstanceDao,
                                activityInstanceStatusDao);
                        dataLoader.loadReleaseSurveyData(handle, //sourceData.get("datstatparticipantdata"),
                                sourceData.get("releasesurvey"), mappingData.get("releasesurvey"),
                                studyDto, userDto, instanceDto, answerDao);
                    }

                    if (hasBloodRelease) {
                        String activityCode = mappingData.get("bdreleasesurvey").getAsJsonObject().get("activity_code").getAsString();
                        ActivityInstanceDto instanceDto = dataLoader.createActivityInstance(sourceData.get("bdreleasesurvey"),
                                userGuid, studyId,
                                activityCode, createdAt,
                                jdbiActivity,
                                activityInstanceDao,
                                activityInstanceStatusDao);
                        dataLoader.loadBloodReleaseSurveyData(handle,
                                sourceData.get("bdreleasesurvey"), mappingData.get("bdreleasesurvey"),
                                studyDto, userDto, instanceDto, answerDao);
                    }

                    if (hasFollowup) {
                        String activityCode = mappingData.get("followupsurvey").getAsJsonObject().get("activity_code").getAsString();
                        ActivityInstanceDto instanceDto = dataLoader.createActivityInstance(sourceData.get("followupsurvey"),
                                userGuid, studyId,
                                activityCode, createdAt,
                                jdbiActivity,
                                activityInstanceDao,
                                activityInstanceStatusDao);
                        dataLoader.loadFollowupSurveyData(handle, sourceData.get("followupsurvey"),
                                mappingData.get("followupsurvey"),
                                studyDto, userDto, instanceDto, activityInstanceDao.getJdbiActivityInstance(), answerDao);
                    }
                    JsonElement ddpExitedDt = datstatParticipantData.getAsJsonObject().get("ddp_exited_dt");
                    if (ddpExitedDt != null && !ddpExitedDt.isJsonNull()) {
                        dataLoader.addUserStudyExit(handle, ddpExitedDt.getAsString(), userGuid, studyGuid);
                    }

                    isSuccess = true;
                } else {
                    skippedList.add(altpid);
                    LOG.warn("Participant: {} already loaded into pepper. skipping ", userGuid);
                    previousRun = true;
                }
            } catch (StudyDataLoader.UserExistsException e) {
                failedList.add(altpid);
                auth0Collision = true;
                LOG.error("Failed to load Participant: " + e.getMessage());
                e.printStackTrace();
                handle.rollback();
                isSuccess = false;
                LOG.error("Rolled back...");
            } catch (Exception e) {
                failedList.add(altpid);
                LOG.error("Failed to load Participant: " + e.getMessage());
                e.printStackTrace();
                handle.rollback();
                isSuccess = false;
                LOG.error("Rolled back...");
            }

            if (previousRun) {
                migrationRun = new StudyMigrationRun(altpid, userGuid, previousRun, emailAddress);
            } else {
                migrationRun = new StudyMigrationRun(altpid, userGuid, hasAboutYou, hasConsent, hasBloodConsent, hasTissueConsent,
                        hasRelease, hasBloodRelease, false, hasFollowup, hasMedicalHistory,
                        isSuccess, previousRun, emailAddress, auth0Collision);
            }

            migrationRunReport.add(migrationRun);

            /*
            dataLoader.verifySourceQsLookedAt("aboutyousurvey", surveyDataMap.get("aboutyousurvey"));
            dataLoader.verifySourceQsLookedAt("consentsurvey", surveyDataMap.get("consentsurvey"));
            dataLoader.verifySourceQsLookedAt("bdconsentsurvey", surveyDataMap.get("bdconsentsurvey"));
            dataLoader.verifySourceQsLookedAt("releasesurvey", surveyDataMap.get("releasesurvey"));
            dataLoader.verifySourceQsLookedAt("bdreleasesurvey", surveyDataMap.get("bdreleasesurvey"));
            dataLoader.verifySourceQsLookedAt("followupsurvey", surveyDataMap.get("followupsurvey"));
            */
        });

    }

    private void setRunEmail(boolean dryRun, JsonElement datstatData) {
        if (dryRun) {
            //update email to generated dry run test email
            String altPid = datstatData.getAsJsonObject().get("datstat_altpid").getAsString();
            String updatedEmail = generateDryRunEmail();
            datstatData.getAsJsonObject().addProperty("datstat_email", updatedEmail);
        }
    }

    private String generateDryRunEmail() {
        String[] emailSplit = dryRunEmail.split("\\@");
        StringBuilder generatedEmail = new StringBuilder(emailSplit[0]);
        generatedEmail.append("+");
        generatedEmail.append(System.currentTimeMillis());
        generatedEmail.append("@");
        generatedEmail.append(emailSplit[1]);
        return generatedEmail.toString().toLowerCase();
    }

    public void processGoogleBucketMailingListFiles(Config cfg, String studyGuid) throws Exception {
        //Download files from Google storage
        //iterate through All buckets
        GoogleCredentials credentials = GoogleCredentials.fromStream(
                new FileInputStream(serviceAccountFile))
                .createScoped(Lists.newArrayList("https://www.googleapis.com/auth/cloud-platform"));
        Storage storage = GoogleBucketUtil.getStorage(credentials, DATA_GC_ID);
        Bucket bucket = storage.get(googleBucketName);
        StudyDataLoader dataLoader = new StudyDataLoader(cfg);

        for (Blob file : bucket.list().iterateAll()) {
            if (!file.getName().startsWith("MailingList")) {
                continue;
                //not a mailing data .. continue to next file.
            }
            String data = new String(file.getContent());
            TransactionWrapper.withTxn(handle -> {
                try {
                    JsonElement dataEl = new Gson().fromJson(data, new TypeToken<JsonObject>() {
                    }.getType());
                    dataLoader.loadMailingListData(handle, dataEl.getAsJsonObject().get("mailing_list_data"), studyGuid);
                } catch (Exception e) {
                    LOG.error("Failed to load Mailing List data: " + e.getMessage());
                    e.printStackTrace();
                    handle.rollback();
                }
                return null;
            });
        }
    }

    private void deleteAuth0Emails(Config cfg, List<StudyMigrationRun> migrationRunReport) {
        for (StudyMigrationRun run : migrationRunReport) {
            if (run.getAuth0Collision() != null && run.getAuth0Collision()) {
                deleteEmailAccount(cfg, run.getEmailAddress());
            }
        }
    }

    private void deleteEmailAccount(Config cfg, String email) {
        TransactionWrapper.useTxn(handle -> {
            try {
                Auth0Util.deleteUserFromAuth0(handle, cfg, email);
                LOG.info("Deleted auth0 email : " + email);
            } catch (Exception e) {
                LOG.error("Failed to delete auth0 email : " + email, e);
            }
        });
    }


    private void createReport(List<StudyMigrationRun> migrationRunReport) throws Exception {

        BufferedWriter writer;
        if (StringUtils.isBlank(reportFileName)) {
            String dateTime = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            String fileName = dateTime.concat("-MBCprojectPepperMigration.csv");
            File directory = new File(DEFAULT_MIGRATION_REPORT_PATH);
            if (!directory.exists()) {
                directory.mkdir();
            }
            writer = Files.newBufferedWriter(Paths.get(DEFAULT_MIGRATION_REPORT_PATH, fileName));
        } else {
            writer = Files.newBufferedWriter(Paths.get(".", reportFileName));
        }
        CSVPrinter csvPrinter = new CSVPrinter(writer, CSVFormat.DEFAULT
                .withNullString("")
                .withHeader("AltPid", "Pepper User GUID", "Has About You", "Has Consent", "Has Blood Consent", "Has Tissue Consent",
                        "Has Release", "Has Blood Release",
                        "Has Followup", "Has Medical History", "Email", "Previous Run", "Success/Failure", "Auth0 Collision"));

        for (StudyMigrationRun run : migrationRunReport) {
            addRunValues(run, csvPrinter);
        }
        csvPrinter.close();

        LOG.info("Generated migration run report file: {} ", reportFileName);
    }

    private void addRunValues(StudyMigrationRun run, CSVPrinter printer) throws IOException {
        printer.printRecord(
                run.getAltPid(),
                run.getPepperUserGuid(),
                run.getHasAboutYou(),
                run.getHasConsent(),
                run.getHasBloodConsent(),
                run.getHasTissueConsent(),
                run.getHasRelease(),
                run.getHasBloodRelease(),
                run.getHasFollowup(),
                run.getHasMedicalHistory(),
                run.getEmailAddress(),
                run.getPreviousRun(),
                run.getSuccess(),
                run.getAuth0Collision()
        );
    }

    private class PreProcessedData {
        private Map<String, String> userEmailData;
        private Map<String, MailAddress> userAddressData;
        private List<String> auth0ExistingEmails;
        private Map<String, Map> altpidBucketDataMap = new HashMap<>();

        public PreProcessedData(Map<String, String> userEmailData, Map<String, MailAddress> userAddressData,
                                List<String> auth0ExistingEmails, Map<String, Map> bucketDataMap) {
            this.userEmailData = userEmailData;
            this.userAddressData = userAddressData;
            this.auth0ExistingEmails = auth0ExistingEmails;
            this.altpidBucketDataMap = bucketDataMap;
        }

        public Map<String, String> getUserEmailData() {
            return userEmailData;
        }

        public Map<String, MailAddress> getUserAddressData() {
            return userAddressData;
        }

        public List<String> getAuth0ExistingEmails() {
            return auth0ExistingEmails;
        }

        public Map<String, Map> getAltpidBucketDataMap() {
            return altpidBucketDataMap;
        }

    }
}
