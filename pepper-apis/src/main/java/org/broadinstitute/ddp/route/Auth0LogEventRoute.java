package org.broadinstitute.ddp.route;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.apache.http.HttpStatus.SC_BAD_REQUEST;
import static org.apache.http.HttpStatus.SC_INTERNAL_SERVER_ERROR;
import static org.apache.http.HttpStatus.SC_UNAUTHORIZED;
import static org.broadinstitute.ddp.constants.ConfigFile.Auth0LogEventApi.AUTH0_LOG_EVENT_API;
import static org.broadinstitute.ddp.constants.ConfigFile.Auth0LogEventApi.BEARER_TOKEN;
import static org.broadinstitute.ddp.constants.ConfigFile.Auth0LogEventApi.TOKEN_CHECK_ENABLED;
import static org.broadinstitute.ddp.constants.ErrorCodes.DATA_PERSIST_ERROR;
import static org.broadinstitute.ddp.constants.ErrorCodes.INVALID_TOKEN;
import static org.broadinstitute.ddp.constants.ErrorCodes.REQUIRED_HEADER_MISSING;
import static org.broadinstitute.ddp.constants.ErrorCodes.REQUIRED_PARAMETER_MISSING;
import static org.broadinstitute.ddp.constants.RouteConstants.Header.AUTHORIZATION;
import static org.broadinstitute.ddp.constants.RouteConstants.Header.BEARER;
import static org.slf4j.LoggerFactory.getLogger;

import java.util.List;
import java.util.Map;


import com.google.gson.JsonElement;
import com.typesafe.config.Config;
import org.apache.http.HttpStatus;
import org.broadinstitute.ddp.db.TransactionWrapper;
import org.broadinstitute.ddp.json.auth0.Auth0LogEvent;
import org.broadinstitute.ddp.json.errors.ApiError;
import org.broadinstitute.ddp.service.Auth0LogEventService;
import org.broadinstitute.ddp.util.ResponseUtil;
import org.slf4j.Logger;
import spark.Request;
import spark.Response;
import spark.Route;

/**
 * Handler for POST ../auth0-log-event?tenant=[TENANT_NAME]
 *
 * <p>JSON (sent in payload) is parsed and auth0 log events are extracted from it.
 * Then log events are logged and persisted into table 'auth0_log_event'.
 *
 * <p>Header ("Authorization") with authorization token can be (optionally) specified in
 * Auth0 Custom Webhook definition.<br>
 * If config parameter 'auth0LogEventApi.tokenCheckEnabled' is true then a token
 * which is passed in the REST header 'Authorization' will be compared with
 * 'Bearer ' + 'auth0LogEventApi.bearerToken'.
 * On the other side in Auth0 Custom Webhook in "Authorization token" the same token
 * (with prefix 'Bearer') should be specified.<br>
 * If config section 'auth0LogEventApi' not specified or 'auth0LogEventApi.tokenCheckEnabled' is false,
 * then token is not checked.
 */
public class Auth0LogEventRoute implements Route {

    private static final Logger LOG = getLogger(Auth0LogEventRoute.class);

    /** Mandatory parameter in the log event URL. Specifies name of auth0 tenant */
    static final String QUERY_PARAM_TENANT = "tenant";

    private final String auth0LogEventApiBearerToken;

    public Auth0LogEventRoute(final Config config) {
        auth0LogEventApiBearerToken = detectBearerTokenFromConfigIfDefinedAndEnabled(config);
    }

    @Override
    public Object handle(Request request, Response response) throws Exception {
        String tenant = readTenant(request);
        checkAuthorizationToken(request);

        final var auth0LogEventService = new Auth0LogEventService();

        final List<Map<String, JsonElement>> logEvents = auth0LogEventService.parseAuth0LogEvents(request.body());
        for (final var logEvent : logEvents) {
            final var logEventObject = Auth0LogEvent.createInstance(logEvent, tenant);
            auth0LogEventService.logAuth0LogEvent(logEventObject);
            persistLogEvent(auth0LogEventService, logEventObject);
        }

        response.status(HttpStatus.SC_OK);
        return "";
    }

    private String detectBearerTokenFromConfigIfDefinedAndEnabled(final Config config) {
        return config.hasPath(AUTH0_LOG_EVENT_API) && config.getBoolean(TOKEN_CHECK_ENABLED)
                ? config.getString(BEARER_TOKEN) : null;
    }

    private String readTenant(final Request request) {
        String tenant = request.queryParams(QUERY_PARAM_TENANT);
        if (tenant == null) {
            haltError(SC_BAD_REQUEST, REQUIRED_PARAMETER_MISSING, "Parameter not specified: " + QUERY_PARAM_TENANT);
        }
        return tenant;
    }

    private void checkAuthorizationToken(final Request request) {
        if (isNotBlank(auth0LogEventApiBearerToken)) {
            String authorizationToken = request.headers(AUTHORIZATION);
            if (authorizationToken == null) {
                haltError(SC_BAD_REQUEST, REQUIRED_HEADER_MISSING, "Header not specified: " + AUTHORIZATION);
            }
            if (!addBearerPrefixToToken(auth0LogEventApiBearerToken).equals(authorizationToken)) {
                haltError(SC_UNAUTHORIZED, INVALID_TOKEN, "Invalid authorization token");
            }
        }
    }

    private void persistLogEvent(final Auth0LogEventService auth0LogEventService, final Auth0LogEvent logEventObject) {
        try {
            TransactionWrapper.useTxn(handle -> auth0LogEventService.persistAuth0LogEvent(handle, logEventObject));
        } catch (Exception e) {
            haltError(SC_INTERNAL_SERVER_ERROR, DATA_PERSIST_ERROR, e.getMessage());
        }
    }

    private String addBearerPrefixToToken(String token) {
        return BEARER + token;
    }

    private void haltError(int status, String code, String msg) {
        LOG.warn(msg);
        throw ResponseUtil.haltError(status, new ApiError(code, msg));
    }
}
