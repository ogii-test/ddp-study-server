package org.broadinstitute.ddp;

import static com.google.common.net.HttpHeaders.X_FORWARDED_FOR;
import static org.broadinstitute.ddp.constants.ConfigFile.Auth0LogEvents.AUTH0_LOG_EVENTS_TOKEN;
import static org.broadinstitute.ddp.constants.ConfigFile.Sendgrid.EVENTS_VERIFICATION_KEY;
import static org.broadinstitute.ddp.filter.AllowListFilter.allowlist;
import static org.broadinstitute.ddp.filter.Exclusions.afterWithExclusion;
import static org.broadinstitute.ddp.filter.Exclusions.beforeWithExclusion;
import static spark.Spark.after;
import static spark.Spark.afterAfter;
import static spark.Spark.awaitInitialization;
import static spark.Spark.before;
import static spark.Spark.delete;
import static spark.Spark.internalServerError;
import static spark.Spark.notFound;
import static spark.Spark.options;
import static spark.Spark.port;
import static spark.Spark.stop;
import static spark.Spark.threadPool;

import java.net.MalformedURLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.http.HttpStatus;
import org.apache.http.entity.ContentType;
import org.broadinstitute.ddp.analytics.GoogleAnalyticsMetricsTracker;
import org.broadinstitute.ddp.cache.CacheService;
import org.broadinstitute.ddp.cache.LanguageStore;
import org.broadinstitute.ddp.constants.ConfigFile;
import org.broadinstitute.ddp.constants.ErrorCodes;
import org.broadinstitute.ddp.constants.RouteConstants;
import org.broadinstitute.ddp.constants.RouteConstants.API;
import org.broadinstitute.ddp.content.I18nContentRenderer;
import org.broadinstitute.ddp.db.ActivityInstanceDao;
import org.broadinstitute.ddp.db.CancerStore;
import org.broadinstitute.ddp.db.ConsentElectionDao;
import org.broadinstitute.ddp.db.DBUtils;
import org.broadinstitute.ddp.db.StudyActivityDao;
import org.broadinstitute.ddp.db.TransactionWrapper;
import org.broadinstitute.ddp.elastic.participantslookup.ESParticipantsLookupService;
import org.broadinstitute.ddp.event.publish.pubsub.TaskPubSubPublisher;
import org.broadinstitute.ddp.filter.AddDDPAuthLoggingFilter;
import org.broadinstitute.ddp.filter.Auth0LogEventCheckTokenFilter;
import org.broadinstitute.ddp.filter.DsmAuthFilter;
import org.broadinstitute.ddp.filter.HttpHeaderMDCFilter;
import org.broadinstitute.ddp.filter.MDCAttributeRemovalFilter;
import org.broadinstitute.ddp.filter.MDCLogBreadCrumbFilter;
import org.broadinstitute.ddp.filter.RateLimitFilter;
import org.broadinstitute.ddp.filter.SendGridEventVerificationFilter;
import org.broadinstitute.ddp.filter.StudyAdminAuthFilter;
import org.broadinstitute.ddp.filter.StudyLanguageContentLanguageSettingFilter;
import org.broadinstitute.ddp.filter.StudyLanguageResolutionFilter;
import org.broadinstitute.ddp.filter.TokenConverterFilter;
import org.broadinstitute.ddp.filter.UserAuthCheckFilter;
import org.broadinstitute.ddp.jetty.JettyConfig;
import org.broadinstitute.ddp.json.errors.ApiError;
import org.broadinstitute.ddp.model.dsm.DrugStore;
import org.broadinstitute.ddp.monitoring.PointsReducerFactory;
import org.broadinstitute.ddp.monitoring.StackdriverCustomMetric;
import org.broadinstitute.ddp.monitoring.StackdriverMetricsTracker;
import org.broadinstitute.ddp.pex.PexInterpreter;
import org.broadinstitute.ddp.pex.TreeWalkInterpreter;
import org.broadinstitute.ddp.route.AddProfileRoute;
import org.broadinstitute.ddp.route.AdminCreateStudyParticipantRoute;
import org.broadinstitute.ddp.route.AdminCreateUserLoginAccountRoute;
import org.broadinstitute.ddp.route.AdminLookupInvitationRoute;
import org.broadinstitute.ddp.route.AdminParticipantLookupByGuidRoute;
import org.broadinstitute.ddp.route.AdminParticipantsLookupRoute;
import org.broadinstitute.ddp.route.AdminUpdateInvitationDetailsRoute;
import org.broadinstitute.ddp.route.Auth0LogEventRoute;
import org.broadinstitute.ddp.route.CheckIrbPasswordRoute;
import org.broadinstitute.ddp.route.CreateActivityInstanceRoute;
import org.broadinstitute.ddp.route.CreateMailAddressRoute;
import org.broadinstitute.ddp.route.CreateTemporaryUserRoute;
import org.broadinstitute.ddp.route.CreateUserActivityUploadRoute;
import org.broadinstitute.ddp.route.DeleteActivityInstanceRoute;
import org.broadinstitute.ddp.route.DeleteMailAddressRoute;
import org.broadinstitute.ddp.route.DeleteMedicalProviderRoute;
import org.broadinstitute.ddp.route.DeleteTempMailingAddressRoute;
import org.broadinstitute.ddp.route.DeleteUserRoute;
import org.broadinstitute.ddp.route.DsmExitUserRoute;
import org.broadinstitute.ddp.route.DsmTriggerOnDemandActivityRoute;
import org.broadinstitute.ddp.route.ErrorRoute;
import org.broadinstitute.ddp.route.GetActivityInstanceRoute;
import org.broadinstitute.ddp.route.GetActivityInstanceStatusTypeListRoute;
import org.broadinstitute.ddp.route.GetActivityInstanceSummaryRoute;
import org.broadinstitute.ddp.route.GetCancerSuggestionsRoute;
import org.broadinstitute.ddp.route.GetActivityInstanceListForActivityInstanceSelectQuestionRoute;
import org.broadinstitute.ddp.route.GetConsentSummariesRoute;
import org.broadinstitute.ddp.route.GetConsentSummaryRoute;
import org.broadinstitute.ddp.route.GetCountryAddressInfoRoute;
import org.broadinstitute.ddp.route.GetCountryAddressInfoSummariesRoute;
import org.broadinstitute.ddp.route.GetDeployedAppVersionRoute;
import org.broadinstitute.ddp.route.GetDsmConsentPdfRoute;
import org.broadinstitute.ddp.route.GetDsmDrugSuggestionsRoute;
import org.broadinstitute.ddp.route.GetDsmInstitutionRequestsRoute;
import org.broadinstitute.ddp.route.GetDsmKitRequestsRoute;
import org.broadinstitute.ddp.route.GetDsmMedicalRecordRoute;
import org.broadinstitute.ddp.route.GetDsmOnDemandActivitiesRoute;
import org.broadinstitute.ddp.route.GetDsmParticipantInstitutionsRoute;
import org.broadinstitute.ddp.route.GetDsmParticipantStatusRoute;
import org.broadinstitute.ddp.route.GetDsmReleasePdfRoute;
import org.broadinstitute.ddp.route.GetDsmStudyParticipant;
import org.broadinstitute.ddp.route.GetDsmTriggeredInstancesRoute;
import org.broadinstitute.ddp.route.GetGovernedStudyParticipantsRoute;
import org.broadinstitute.ddp.route.GetInstitutionSuggestionsRoute;
import org.broadinstitute.ddp.route.GetMailAddressRoute;
import org.broadinstitute.ddp.route.GetMailingListRoute;
import org.broadinstitute.ddp.route.GetMedicalProviderListRoute;
import org.broadinstitute.ddp.route.GetParticipantDefaultMailAddressRoute;
import org.broadinstitute.ddp.route.GetParticipantInfoRoute;
import org.broadinstitute.ddp.route.GetParticipantMailAddressRoute;
import org.broadinstitute.ddp.route.GetPdfRoute;
import org.broadinstitute.ddp.route.GetPrequalifierInstanceRoute;
import org.broadinstitute.ddp.route.GetProfileRoute;
import org.broadinstitute.ddp.route.GetStudiesRoute;
import org.broadinstitute.ddp.route.GetStudyDetailRoute;
import org.broadinstitute.ddp.route.GetStudyPasswordPolicyRoute;
import org.broadinstitute.ddp.route.GetStudyStatisticsRoute;
import org.broadinstitute.ddp.route.GetTempMailingAddressRoute;
import org.broadinstitute.ddp.route.GetUserAnnouncementsRoute;
import org.broadinstitute.ddp.route.GetWorkflowRoute;
import org.broadinstitute.ddp.route.GovernedParticipantRegistrationRoute;
import org.broadinstitute.ddp.route.HealthCheckRoute;
import org.broadinstitute.ddp.route.InvitationCheckStatusRoute;
import org.broadinstitute.ddp.route.InvitationVerifyRoute;
import org.broadinstitute.ddp.route.JoinMailingListRoute;
import org.broadinstitute.ddp.route.ListCancersRoute;
import org.broadinstitute.ddp.route.ListStudyLanguagesRoute;
import org.broadinstitute.ddp.route.ListUserStudyInvitationsRoute;
import org.broadinstitute.ddp.route.PatchActivityInstanceRoute;
import org.broadinstitute.ddp.route.PatchFormAnswersRoute;
import org.broadinstitute.ddp.route.PatchMedicalProviderRoute;
import org.broadinstitute.ddp.route.PatchProfileRoute;
import org.broadinstitute.ddp.route.PostMedicalProviderRoute;
import org.broadinstitute.ddp.route.PostPasswordResetRoute;
import org.broadinstitute.ddp.route.PutFormAnswersRoute;
import org.broadinstitute.ddp.route.PutTempMailingAddressRoute;
import org.broadinstitute.ddp.route.ReceiveDsmNotificationRoute;
import org.broadinstitute.ddp.route.SendEmailRoute;
import org.broadinstitute.ddp.route.SendExitNotificationRoute;
import org.broadinstitute.ddp.route.SendGridEventRoute;
import org.broadinstitute.ddp.route.SetParticipantDefaultMailAddressRoute;
import org.broadinstitute.ddp.route.UpdateMailAddressRoute;
import org.broadinstitute.ddp.route.UpdateUserEmailRoute;
import org.broadinstitute.ddp.route.UpdateUserPasswordRoute;
import org.broadinstitute.ddp.route.UserActivityInstanceListRoute;
import org.broadinstitute.ddp.route.UserRegistrationRoute;
import org.broadinstitute.ddp.route.VerifyMailAddressRoute;
import org.broadinstitute.ddp.schedule.DsmCancerLoaderJob;
import org.broadinstitute.ddp.schedule.DsmDrugLoaderJob;
import org.broadinstitute.ddp.schedule.JobScheduler;
import org.broadinstitute.ddp.security.JWTConverter;
import org.broadinstitute.ddp.service.ActivityInstanceService;
import org.broadinstitute.ddp.service.ActivityValidationService;
import org.broadinstitute.ddp.service.AddressService;
import org.broadinstitute.ddp.service.Auth0LogEventService;
import org.broadinstitute.ddp.service.CancerService;
import org.broadinstitute.ddp.service.ConsentService;
import org.broadinstitute.ddp.service.FileUploadService;
import org.broadinstitute.ddp.service.FormActivityService;
import org.broadinstitute.ddp.service.MedicalRecordService;
import org.broadinstitute.ddp.service.PdfBucketService;
import org.broadinstitute.ddp.service.PdfGenerationService;
import org.broadinstitute.ddp.service.PdfService;
import org.broadinstitute.ddp.service.SendGridEventService;
import org.broadinstitute.ddp.service.UserDeleteService;
import org.broadinstitute.ddp.service.WorkflowService;
import org.broadinstitute.ddp.transformers.NullableJsonTransformer;
import org.broadinstitute.ddp.transformers.SimpleJsonTransformer;
import org.broadinstitute.ddp.util.ConfigManager;
import org.broadinstitute.ddp.util.ConfigUtil;
import org.broadinstitute.ddp.util.ElasticsearchServiceUtil;
import org.broadinstitute.ddp.util.LiquibaseUtil;
import org.broadinstitute.ddp.util.LogbackConfigurationPrinter;
import org.broadinstitute.ddp.util.ResponseUtil;
import org.broadinstitute.ddp.util.RouteUtil;
import org.elasticsearch.client.RestHighLevelClient;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import spark.Request;
import spark.Response;
import spark.ResponseTransformer;
import spark.Route;
import spark.Spark;
import spark.route.HttpMethod;

public class DataDonationPlatform {

    public static final String MDC_STUDY = "Study";
    public static final String MDC_ROUTE_CLASS = "RouteClass";
    public static final String PORT = "PORT";
    public static final int DEFAULT_RATE_LIMIT_MAX_QUERIES_PER_SECOND = 10;
    public static final int DEFAULT_RATE_LIMIT_BURST = 15;
    private static final Logger LOG = LoggerFactory.getLogger(DataDonationPlatform.class);

    private static final String HTTP_METHOD__GET = "GET";
    private static final String HTTP_METHOD__PUT = "PUT";
    private static final String HTTP_METHOD__POST = "POST";
    private static final String HTTP_METHOD__OPTIONS = "OPTIONS";
    private static final String HTTP_METHOD__PATCH = "PATCH";

    private static final String[] CORS_HTTP_METHODS = new String[] {
            HTTP_METHOD__GET,
            HTTP_METHOD__PUT,
            HTTP_METHOD__POST,
            HTTP_METHOD__OPTIONS,
            HTTP_METHOD__PATCH
    };

    private static final String[] CORS_HTTP_HEADERS = new String[] {"Content-Type", "Authorization", "X-Requested-With",
            "Content-Length", "Accept", "Origin", ""};
    private static final Map<String, String> pathToClass = new HashMap<>();
    private static Scheduler scheduler = null;

    private static final AtomicBoolean isReady = new AtomicBoolean(false);
    private static final int DEFAULT_BOOT_WAIT_SECS = 30;


    /**
     * Stop the server using the default wait time.
     */
    public static void shutdown() {
        shutdown(1000);
    }

    /**
     * Stop the Spark server and give it time to close.
     *
     * @param millisecs milliseconds to wait for Spark to close
     */
    public static void shutdown(int millisecs) {
        if (scheduler != null) {
            JobScheduler.shutdownScheduler(scheduler, false);
        }

        stop();
        try {
            LOG.info("Pausing for {}ms for server to stop", millisecs);
            Thread.sleep(millisecs);
        } catch (InterruptedException e) {
            LOG.warn("Wait interrupted", e);
        }

        LOG.info("ddp shutdown complete");
    }

    public static void main(String[] args) {
        try {
            synchronized (isReady) {
                start();
                isReady.set(true);
            }
        } catch (Exception e) {
            LOG.error("Could not start ddp", e);
            shutdown();
        }
    }

    private static void start() throws MalformedURLException {
        LogbackConfigurationPrinter.printLoggingConfiguration();
        Config cfg = ConfigManager.getInstance().getConfig();
        int maxConnections = cfg.getInt(ConfigFile.NUM_POOLED_CONNECTIONS);

        int requestThreadTimeout = cfg.getInt(ConfigFile.THREAD_TIMEOUT);
        String healthcheckPassword = cfg.getString(ConfigFile.HEALTHCHECK_PASSWORD);
        String sendGridEventsVerificationKey = cfg.hasPath(EVENTS_VERIFICATION_KEY)
                ? cfg.getString(EVENTS_VERIFICATION_KEY) : null;
        String auth0LogEventsToken = cfg.hasPath(AUTH0_LOG_EVENTS_TOKEN) ? cfg.getString(AUTH0_LOG_EVENTS_TOKEN) : null;

        // app engine's port env var wins
        int configFilePort = cfg.getInt(ConfigFile.PORT);

        // GAE specifies port to use via environment variable
        String appEnginePort = System.getenv(PORT);

        String preferredSourceIPHeader = null;
        if (cfg.hasPath(ConfigFile.PREFERRED_SOURCE_IP_HEADER)) {
            preferredSourceIPHeader = cfg.getString(ConfigFile.PREFERRED_SOURCE_IP_HEADER);
        }

        String dbUrl = cfg.getString(ConfigFile.DB_URL);
        LOG.info("Using db {}", dbUrl);

        TransactionWrapper.init(
                new TransactionWrapper.DbConfiguration(TransactionWrapper.DB.APIS, maxConnections, dbUrl));
        Config sqlConfig = ConfigFactory.load(ConfigFile.SQL_CONFIG_FILE);
        initSqlCommands(sqlConfig);

        if (cfg.hasPath(ConfigFile.DO_LIQUIBASE_IN_STUDY_SERVER) && cfg.getBoolean(ConfigFile.DO_LIQUIBASE_IN_STUDY_SERVER)) {
            LOG.info("Running liquibase migrations in StudyServer against database url: {}", dbUrl);
            LiquibaseUtil.runLiquibase(dbUrl, TransactionWrapper.DB.APIS);
            LiquibaseUtil.releaseResources();
        }
        //@TODO figure out how to do this only at deployment time.
        CacheService.getInstance().resetAllCaches();
        TransactionWrapper.useTxn(TransactionWrapper.DB.APIS, LanguageStore::init);

        if (appEnginePort != null) {
            port(Integer.parseInt(appEnginePort));
        } else {
            port(configFilePort);
        }
        threadPool(-1, -1, requestThreadTimeout);
        JettyConfig.setupJetty(preferredSourceIPHeader);

        // The first route mapping call will also initialize the Spark server. Make that first call
        // the GAE lifecycle hooks so we capture the GAE call as soon as possible, and respond
        // only once server has fully booted.
        registerAppEngineCallbacks(DEFAULT_BOOT_WAIT_SECS);

        ActivityInstanceDao activityInstanceDao = new ActivityInstanceDao();

        PexInterpreter interpreter = new TreeWalkInterpreter();
        I18nContentRenderer i18nContentRenderer = new I18nContentRenderer();
        final ActivityInstanceService actInstService = new ActivityInstanceService(activityInstanceDao, interpreter, i18nContentRenderer);
        final ActivityValidationService activityValidationService = new ActivityValidationService();

        var jsonSerializer = new NullableJsonTransformer();
        SimpleJsonTransformer responseSerializer = new SimpleJsonTransformer();

        if (cfg.hasPath(ConfigFile.API_RATE_LIMIT.MAX_QUERIES_PER_SECOND) && cfg.hasPath(ConfigFile.API_RATE_LIMIT.BURST)) {
            int maxQueriesPerSecond = cfg.getInt(ConfigFile.API_RATE_LIMIT.MAX_QUERIES_PER_SECOND);
            int burst = cfg.getInt(ConfigFile.API_RATE_LIMIT.BURST);
            LOG.info("Will use rate limit {} with burst {}", maxQueriesPerSecond, burst);
            before("*", new RateLimitFilter(maxQueriesPerSecond, burst));
        } else {
            LOG.warn("No rate limit values given.  Rate limiting is disabled.");
        }

        before("*", new HttpHeaderMDCFilter(X_FORWARDED_FOR));
        before("*", new MDCLogBreadCrumbFilter());
        before("*", (Request request, Response response) -> {
            MDC.put(MDC_STUDY, RouteUtil.parseStudyGuid(request.pathInfo()));
        });
        enableCORS("*", String.join(",", CORS_HTTP_METHODS), String.join(",", CORS_HTTP_HEADERS));
        setupCatchAllErrorHandling();

        // before filter converts jwt into DDP_AUTH request attribute
        // we exclude the DSM paths. DSM paths have own separate authentication
        beforeWithExclusion(API.BASE + "/*", new TokenConverterFilter(new JWTConverter()),
                API.DSM_BASE + "/*",
                API.CHECK_IRB_PASSWORD,
                API.AUTH0_LOG_EVENT);
        beforeWithExclusion(API.BASE + "/*", new AddDDPAuthLoggingFilter(),
                API.DSM_BASE + "/*",
                API.CHECK_IRB_PASSWORD,
                API.AUTH0_LOG_EVENT);

        before(API.AUTH0_LOG_EVENT, new Auth0LogEventCheckTokenFilter(auth0LogEventsToken));

        // Internal routes
        get(API.HEALTH_CHECK, new HealthCheckRoute(healthcheckPassword), responseSerializer);
        get(API.DEPLOYED_VERSION, new GetDeployedAppVersionRoute(), responseSerializer);
        get(API.INTERNAL_ERROR, new ErrorRoute(), responseSerializer);

        before(API.SENDGRID_EVENT, new SendGridEventVerificationFilter(sendGridEventsVerificationKey));

        if (cfg.getBoolean(ConfigFile.RESTRICT_REGISTER_ROUTE)) {
            allowlist(API.REGISTRATION, cfg.getStringList(ConfigFile.AUTH0_IP_ALLOW_LIST));
        }
        if (ConfigUtil.getBoolOrElse(cfg, ConfigFile.RESTRICT_AUTH0_LOG_EVENT_ROUTE, false)) {
            allowlist(API.AUTH0_LOG_EVENT, cfg.getStringList(ConfigFile.AUTH0_IP_ALLOW_LIST));
        }

        post(API.REGISTRATION, new UserRegistrationRoute(interpreter, new TaskPubSubPublisher()), responseSerializer);
        post(API.TEMP_USERS, new CreateTemporaryUserRoute(), responseSerializer);

        post(API.SENDGRID_EVENT, new SendGridEventRoute(new SendGridEventService()), responseSerializer);
        post(API.AUTH0_LOG_EVENT, new Auth0LogEventRoute(new Auth0LogEventService()), responseSerializer);

        RestHighLevelClient esClient = ElasticsearchServiceUtil.getElasticsearchClient(cfg);

        // Admin APIs
        before(API.ADMIN_BASE + "/*", new StudyAdminAuthFilter());
        post(API.ADMIN_STUDY_PARTICIPANTS, new AdminCreateStudyParticipantRoute(new TaskPubSubPublisher()), jsonSerializer);
        post(API.ADMIN_STUDY_INVITATION_LOOKUP, new AdminLookupInvitationRoute(), jsonSerializer);
        post(API.ADMIN_STUDY_INVITATION_DETAILS, new AdminUpdateInvitationDetailsRoute(), jsonSerializer);
        post(API.ADMIN_STUDY_USER_LOGIN_ACCOUNT, new AdminCreateUserLoginAccountRoute(), jsonSerializer);
        post(API.ADMIN_STUDY_PARTICIPANTS_LOOKUP,
                new AdminParticipantsLookupRoute(new ESParticipantsLookupService(esClient)), responseSerializer);
        get(API.ADMIN_STUDY_PARTICIPANT_LOOKUP_BY_GUID,
                new AdminParticipantLookupByGuidRoute(new ESParticipantsLookupService(esClient)), responseSerializer);

        // These filters work in a tandem:
        // - StudyLanguageResolutionFilter figures out and sets the user language in the attribute store
        // - StudyLanguageContentLanguageSettingFilter sets the "Content-Language" header later on
        before(API.BASE + "/user/*/studies/*", new StudyLanguageResolutionFilter());
        before(API.STUDY_STATISTICS, new StudyLanguageResolutionFilter(), new UserAuthCheckFilter());
        after(API.BASE + "/user/*/studies/*", new StudyLanguageContentLanguageSettingFilter());
        beforeWithExclusion(API.BASE + "/studies/*", new StudyLanguageResolutionFilter(),
                API.INVITATION_VERIFY, API.INVITATION_CHECK);
        afterWithExclusion(API.BASE + "/studies/*", new StudyLanguageContentLanguageSettingFilter(),
                API.INVITATION_VERIFY, API.INVITATION_CHECK);

        // Study related routes
        get(API.STUDY_ALL, new GetStudiesRoute(), responseSerializer);
        get(API.STUDY_DETAIL, new GetStudyDetailRoute(), responseSerializer);
        get(API.STUDY_PASSWORD_POLICY, new GetStudyPasswordPolicyRoute(), responseSerializer);
        get(API.STUDY_LANGUAGES, new ListStudyLanguagesRoute(), responseSerializer);
        post(API.INVITATION_VERIFY, new InvitationVerifyRoute(), jsonSerializer);
        post(API.INVITATION_CHECK, new InvitationCheckStatusRoute(), jsonSerializer);

        get(API.ADDRESS_COUNTRIES, new GetCountryAddressInfoSummariesRoute(), responseSerializer);
        get(API.ADDRESS_COUNTRY_DETAILS, new GetCountryAddressInfoRoute(), responseSerializer);

        // User route filter
        before(API.USER_ALL, new UserAuthCheckFilter()
                .addTempUserAllowlist(HttpMethod.get, API.USER_PROFILE)
                .addTempUserAllowlist(HttpMethod.patch, API.USER_PROFILE)
                .addTempUserAllowlist(HttpMethod.get, API.USER_STUDY_WORKFLOW)
                .addTempUserAllowlist(HttpMethod.get, API.USER_ACTIVITIES_INSTANCE)
                .addTempUserAllowlist(HttpMethod.patch, API.USER_ACTIVITY_ANSWERS)
                .addTempUserAllowlist(HttpMethod.put, API.USER_ACTIVITY_ANSWERS)
        );
        patch(API.UPDATE_USER_PASSWORD, new UpdateUserPasswordRoute(), responseSerializer);
        patch(API.UPDATE_USER_EMAIL, new UpdateUserEmailRoute(), responseSerializer);

        // Governed participant routes
        get(API.USER_STUDY_PARTICIPANTS, new GetGovernedStudyParticipantsRoute(), responseSerializer);
        post(API.USER_STUDY_PARTICIPANTS, new GovernedParticipantRegistrationRoute(new TaskPubSubPublisher()), responseSerializer);

        // User profile routes
        get(API.USER_PROFILE, new GetProfileRoute(), responseSerializer);
        post(API.USER_PROFILE, new AddProfileRoute(), responseSerializer);
        patch(API.USER_PROFILE, new PatchProfileRoute(), responseSerializer);

        delete(API.USER_SPECIFIC, new DeleteUserRoute(new UserDeleteService(esClient)), responseSerializer);

        // User mailing address routes
        AddressService addressService = new AddressService(cfg.getString(ConfigFile.EASY_POST_API_KEY),
                cfg.getString(ConfigFile.GEOCODING_API_KEY));

        post(API.PARTICIPANT_ADDRESS, new CreateMailAddressRoute(addressService), responseSerializer);
        get(API.PARTICIPANT_ADDRESS, new GetParticipantMailAddressRoute(addressService), responseSerializer);

        post(API.DEFAULT_PARTICIPANT_ADDRESS,
                new SetParticipantDefaultMailAddressRoute(addressService), responseSerializer);
        get(API.DEFAULT_PARTICIPANT_ADDRESS,
                new GetParticipantDefaultMailAddressRoute(addressService), responseSerializer);

        get(API.ADDRESS, new GetMailAddressRoute(addressService), responseSerializer);
        put(API.ADDRESS, new UpdateMailAddressRoute(addressService), responseSerializer);
        delete(API.ADDRESS, new DeleteMailAddressRoute(addressService));

        get(API.PARTICIPANT_TEMP_ADDRESS, new GetTempMailingAddressRoute(), responseSerializer);
        put(API.PARTICIPANT_TEMP_ADDRESS, new PutTempMailingAddressRoute());
        delete(API.PARTICIPANT_TEMP_ADDRESS, new DeleteTempMailingAddressRoute());

        post(API.ADDRESS_VERIFY, new VerifyMailAddressRoute(addressService), responseSerializer);

        get(API.PARTICIPANTS_INFO_FOR_STUDY, new GetParticipantInfoRoute(), responseSerializer);

        // Workflow routing
        WorkflowService workflowService = new WorkflowService(interpreter);
        get(API.USER_STUDY_WORKFLOW, new GetWorkflowRoute(workflowService), responseSerializer);

        // User study announcements
        get(API.USER_STUDY_ANNOUNCEMENTS, new GetUserAnnouncementsRoute(i18nContentRenderer), responseSerializer);

        // User prequalifier instance route
        StudyActivityDao studyActivityDao = new StudyActivityDao();
        get(API.USER_STUDIES_PREQUALIFIER, new GetPrequalifierInstanceRoute(studyActivityDao, activityInstanceDao),
                responseSerializer);

        ConsentService consentService = new ConsentService(interpreter, studyActivityDao, new ConsentElectionDao());
        MedicalRecordService medicalRecordService = new MedicalRecordService(consentService);
        // User consent routes
        get(API.USER_STUDIES_ALL_CONSENTS, new GetConsentSummariesRoute(consentService), responseSerializer);
        get(API.USER_STUDIES_CONSENT, new GetConsentSummaryRoute(consentService), responseSerializer);

        get(API.ACTIVITY_INSTANCE_STATUS_TYPE_LIST, new GetActivityInstanceStatusTypeListRoute(), responseSerializer);

        // User activity instance routes
        get(API.USER_ACTIVITIES, new UserActivityInstanceListRoute(actInstService), responseSerializer);
        post(API.USER_ACTIVITIES, new CreateActivityInstanceRoute(), responseSerializer);
        get(
                API.USER_ACTIVITIES_INSTANCE,
                new GetActivityInstanceRoute(actInstService, activityValidationService, interpreter),
                responseSerializer
        );
        patch(API.USER_ACTIVITIES_INSTANCE, new PatchActivityInstanceRoute(activityInstanceDao), responseSerializer);
        delete(API.USER_ACTIVITIES_INSTANCE, new DeleteActivityInstanceRoute(actInstService), jsonSerializer);
        get(API.USER_ACTIVITY_SUMMARY, new GetActivityInstanceSummaryRoute(actInstService), responseSerializer);

        // User activity answers routes
        FormActivityService formService = new FormActivityService(interpreter);
        var fileUploadService = FileUploadService.fromConfig(cfg);

        patch(API.USER_ACTIVITY_ANSWERS,
                new PatchFormAnswersRoute(formService, activityValidationService, fileUploadService, interpreter),
                responseSerializer);
        put(
                API.USER_ACTIVITY_ANSWERS,
                new PutFormAnswersRoute(workflowService, actInstService, activityValidationService, interpreter, addressService),
                responseSerializer
        );
        post(API.USER_ACTIVITY_UPLOADS, new CreateUserActivityUploadRoute(fileUploadService), responseSerializer);

        // User study invitations
        get(API.USER_STUDY_INVITES, new ListUserStudyInvitationsRoute(), jsonSerializer);

        // Study exit request
        post(API.USER_STUDY_EXIT, new SendExitNotificationRoute());

        Config auth0Config = cfg.getConfig(ConfigFile.AUTH0);
        before(API.DSM_BASE + "/*", new DsmAuthFilter(auth0Config.getString(ConfigFile.AUTH0_DSM_CLIENT_ID),
                auth0Config.getString(ConfigFile.DOMAIN)));

        get(API.DSM_ALL_KIT_REQUESTS, new GetDsmKitRequestsRoute(), responseSerializer);
        get(API.DSM_KIT_REQUESTS_STARTING_AFTER, new GetDsmKitRequestsRoute(), responseSerializer);
        get(API.DSM_STUDY_PARTICIPANT, new GetDsmStudyParticipant(), responseSerializer);
        get(API.DSM_GET_INSTITUTION_REQUESTS, new GetDsmInstitutionRequestsRoute(), responseSerializer);
        get(API.DSM_PARTICIPANT_MEDICAL_INFO, new GetDsmMedicalRecordRoute(medicalRecordService), responseSerializer);
        get(API.DSM_PARTICIPANT_INSTITUTIONS, new GetDsmParticipantInstitutionsRoute(), responseSerializer);

        post(API.DSM_NOTIFICATION, new ReceiveDsmNotificationRoute(), jsonSerializer);
        post(API.DSM_TERMINATE_USER, new DsmExitUserRoute(), responseSerializer);

        PdfService pdfService = new PdfService();
        PdfBucketService pdfBucketService = new PdfBucketService(cfg);
        PdfGenerationService pdfGenerationService = new PdfGenerationService();
        get(API.DSM_PARTICIPANT_RELEASE_PDF, new GetDsmReleasePdfRoute(pdfService, pdfBucketService, pdfGenerationService));
        get(API.DSM_PARTICIPANT_CONSENT_PDF, new GetDsmConsentPdfRoute(pdfService, pdfBucketService, pdfGenerationService));
        get(API.DSM_PARTICIPANT_PDF, new GetPdfRoute(pdfService, pdfBucketService, pdfGenerationService));

        get(API.DSM_ONDEMAND_ACTIVITIES, new GetDsmOnDemandActivitiesRoute(), responseSerializer);
        get(API.DSM_ONDEMAND_ACTIVITY, new GetDsmTriggeredInstancesRoute(), responseSerializer);
        post(API.DSM_ONDEMAND_ACTIVITY, new DsmTriggerOnDemandActivityRoute(), responseSerializer);

        get(API.AUTOCOMPLETE_INSTITUTION, new GetInstitutionSuggestionsRoute(), responseSerializer);
        get(API.LIST_CANCERS, new ListCancersRoute(new CancerService()), responseSerializer);

        get(API.USER_MEDICAL_PROVIDERS, new GetMedicalProviderListRoute(), responseSerializer);
        post(API.USER_MEDICAL_PROVIDERS, new PostMedicalProviderRoute(), responseSerializer);
        patch(API.USER_MEDICAL_PROVIDER, new PatchMedicalProviderRoute(), responseSerializer);
        delete(API.USER_MEDICAL_PROVIDER, new DeleteMedicalProviderRoute(), responseSerializer);

        post(API.JOIN_MAILING_LIST, new JoinMailingListRoute());

        get(API.GET_STUDY_MAILING_LIST, new GetMailingListRoute(), responseSerializer);

        post(API.CHECK_IRB_PASSWORD, new CheckIrbPasswordRoute(), responseSerializer);

        post(API.SEND_EMAIL, new SendEmailRoute(workflowService), responseSerializer);

        get(API.POST_PASSWORD_RESET, new PostPasswordResetRoute(), responseSerializer);

        get(API.DSM_DRUG_SUGGESTION, new GetDsmDrugSuggestionsRoute(DrugStore.getInstance()), responseSerializer);

        get(API.CANCER_SUGGESTION, new GetCancerSuggestionsRoute(CancerStore.getInstance()), responseSerializer);

        get(API.ACTIVITY_INSTANCE_SELECT_SUGGESTION,
                new GetActivityInstanceListForActivityInstanceSelectQuestionRoute(actInstService), responseSerializer);

        get(API.STUDY_STATISTICS, new GetStudyStatisticsRoute(i18nContentRenderer), responseSerializer);

        // Routes calling DSM
        get(API.PARTICIPANT_STATUS, new GetDsmParticipantStatusRoute(esClient), responseSerializer);

        boolean runScheduler = cfg.getBoolean(ConfigFile.RUN_SCHEDULER);
        if (runScheduler) {
            // Setup DDP JobScheduler on server startup
            scheduler = JobScheduler.initializeWith(cfg,
                    DsmDrugLoaderJob::register,
                    DsmCancerLoaderJob::register);

            // Initialize drug/cancer list on startup
            try {
                scheduler.triggerJob(DsmDrugLoaderJob.getKey());
                scheduler.triggerJob(DsmCancerLoaderJob.getKey());
            } catch (SchedulerException e) {
                LOG.error("Could not trigger job to initialize drug/cancer lists", e);
            }
        } else {
            LOG.info("DDP job scheduler is not set to run");
        }

        setupApiActivityFilter();

        afterAfter(new MDCAttributeRemovalFilter(AddDDPAuthLoggingFilter.LOGGING_CLIENTID_PARAM,
                AddDDPAuthLoggingFilter.LOGGING_USERID_PARAM,
                MDC_STUDY,
                MDC_ROUTE_CLASS,
                X_FORWARDED_FOR,
                MDCLogBreadCrumbFilter.LOG_BREADCRUMB));

        awaitInitialization();
        LOG.info("ddp startup complete");
    }

    private static void registerAppEngineCallbacks(long bootWaitSecs) {
        get(RouteConstants.GAE.START_ENDPOINT, (request, response) -> {
            LOG.info("Received GAE start request [{}]", RouteConstants.GAE.START_ENDPOINT);
            long startedMillis = Instant.now().toEpochMilli();

            var status = new AtomicInteger(HttpStatus.SC_SERVICE_UNAVAILABLE);
            var waitForBoot = new Thread(() -> {
                synchronized (isReady) {
                    if (isReady.get()) {
                        status.set(HttpStatus.SC_OK);
                    }
                }
            });
            waitForBoot.start();
            waitForBoot.join(bootWaitSecs * 1000);

            long elapsed = Instant.now().toEpochMilli() - startedMillis;
            LOG.info("Responding to GAE start request with status {} after delay of {}ms", status, elapsed);
            response.status(status.get());
            return "";
        });

        get(RouteConstants.GAE.STOP_ENDPOINT, (request, response) -> {
            LOG.info("Received GAE stop request [{}]", RouteConstants.GAE.STOP_ENDPOINT);
            //flush out any pending GA events
            GoogleAnalyticsMetricsTracker.getInstance().flushOutMetrics();

            response.status(HttpStatus.SC_OK);
            return "";
        });
    }

    private static void setupApiActivityFilter() {
        afterAfter((Request request, Response response) -> {
            String endpoint = MDC.get(MDC_ROUTE_CLASS);
            if (endpoint == null) {
                endpoint = "unknown";
            }
            String study = MDC.get(MDC_STUDY);
            String httpMethod = request.requestMethod();
            String participant = MDC.get(AddDDPAuthLoggingFilter.LOGGING_USERID_PARAM);
            String client = MDC.get(AddDDPAuthLoggingFilter.LOGGING_CLIENTID_PARAM);

            new StackdriverMetricsTracker(StackdriverCustomMetric.API_ACTIVITY,
                    study, endpoint, httpMethod, client, participant, response.status(),
                    PointsReducerFactory.buildSumReducer()).addPoint(1, Instant.now().toEpochMilli());
        });
    }

    /**
     * This method sets to log4j MDC a key/value pair: "RouteClass"=route_class_name.
     * It is set before a route executing.<br>
     * And this MDC value ("RouteClass"=route_class_name) can be used in the route code or in after-filters
     * (for example when collecting Stackdriver metrics data).<br>
     * In map `pathToClass` a compound key is used: httpMethod + urlPath.
     * This needs in order to solve a problem that some of Routes share similar `path` (as a result when we have map key = path,
     * then we miss some of Route classes in the statistics: only the last one was saved in the map `pathToClass`).
     *
     * <p>Example of such routes having same urlPath:
     * <pre>
     *   PATCH /user/%s/studies/%s/activities/%s/answers
     *   PUT   /user/%s/studies/%s/activities/%s/answers
     * </pre>
     */
    private static void setupMDC(String path, String httpMethod, Route route) {
        pathToClass.put(httpMethod + path, route.getClass().getSimpleName());
        before(path, (request, response) -> MDC.put(MDC_ROUTE_CLASS, pathToClass.get(request.requestMethod() + path)));
    }

    public static void get(String path, Route route, ResponseTransformer transformer) {
        setupMDC(path, HTTP_METHOD__GET, route);
        Spark.get(path, route, transformer);
    }

    public static void get(String path, Route route) {
        setupMDC(path, HTTP_METHOD__GET, route);
        Spark.get(path, route);
    }

    public static void post(String path, Route route) {
        setupMDC(path, HTTP_METHOD__POST, route);
        Spark.post(path, route);
    }

    public static void post(String path, Route route, ResponseTransformer transformer) {
        setupMDC(path, HTTP_METHOD__POST, route);
        Spark.post(path, route, transformer);
    }

    public static void put(String path, Route route) {
        setupMDC(path, HTTP_METHOD__PUT, route);
        Spark.put(path, route);
    }

    public static void put(String path, Route route, ResponseTransformer transformer) {
        setupMDC(path, HTTP_METHOD__PUT, route);
        Spark.put(path, route, transformer);
    }

    public static void patch(String path, Route route, ResponseTransformer transformer) {
        setupMDC(path, HTTP_METHOD__PATCH, route);
        Spark.patch(path, route, transformer);
    }

    private static void setupCatchAllErrorHandling() {
        //JSON for Not Found (code 404) handling
        notFound((request, response) -> {
            LOG.info("[404] Current status: {}", response.status());
            return ResponseUtil.renderPageNotFound(response);
        });

        internalServerError((request, response) -> {
            ApiError apiError = new ApiError(ErrorCodes.SERVER_ERROR,
                    "Something unexpected happened.");
            response.type(ContentType.APPLICATION_JSON.getMimeType());
            SimpleJsonTransformer jsonTransformer = new SimpleJsonTransformer();
            return jsonTransformer.render(apiError);
        });
    }

    private static void enableCORS(final String origin, final String methods, final String headers) {
        options("/*", (request, response) -> {
            String accessControlRequestHeaders = request.headers("Access-Control-Request-Headers");
            if (accessControlRequestHeaders != null) {
                response.header("Access-Control-Allow-Headers", accessControlRequestHeaders);
            }

            String accessControlRequestMethod = request.headers("Access-Control-Request-Method");
            if (accessControlRequestMethod != null) {
                response.header("Access-Control-Allow-Methods", accessControlRequestMethod);
            }
            if (accessControlRequestMethod != null || accessControlRequestHeaders != null) {
                response.header("Access-Control-Max-Age", "172800");
            }

            return "OK";
        });

        before((request, response) -> {
            response.header("Access-Control-Allow-Origin", origin);
            response.header("Access-Control-Request-Method", methods);
            response.header("Access-Control-Allow-Headers", headers);
            response.header("Access-Control-Allow-Credentials", "true");
            response.type("application/json");
        });
    }

    private static void initSqlCommands(Config sqlConfig) {
        DBUtils.loadDaoSqlCommands(sqlConfig);
    }

}
