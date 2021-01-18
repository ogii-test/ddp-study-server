package org.broadinstitute.ddp.json.auth0;

import static org.broadinstitute.ddp.json.auth0.Auth0LogEventNode.CLIENT_ID;
import static org.broadinstitute.ddp.json.auth0.Auth0LogEventNode.CONNECTION_ID;
import static org.broadinstitute.ddp.json.auth0.Auth0LogEventNode.DATA;
import static org.broadinstitute.ddp.json.auth0.Auth0LogEventNode.DATE;
import static org.broadinstitute.ddp.json.auth0.Auth0LogEventNode.EMAIL;
import static org.broadinstitute.ddp.json.auth0.Auth0LogEventNode.IP;
import static org.broadinstitute.ddp.json.auth0.Auth0LogEventNode.LOG_ID;
import static org.broadinstitute.ddp.json.auth0.Auth0LogEventNode.TYPE;
import static org.broadinstitute.ddp.json.auth0.Auth0LogEventNode.USER_AGENT;
import static org.broadinstitute.ddp.json.auth0.Auth0LogEventNode.USER_ID;
import static org.broadinstitute.ddp.json.auth0.Auth0LogEventNode.resolveDateTimeValue;
import static org.broadinstitute.ddp.json.auth0.Auth0LogEventNode.resolveStringValue;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;


import com.google.gson.JsonElement;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

/**
 * Holds one log event generated by auth0
 */
public class Auth0LogEvent {

    private final String tenant;
    private final String type;
    private final Instant date;
    private final String logId;
    private final String clientId;
    private final String connectionId;
    private final String userId;
    private final String userAgent;
    private final String ip;
    private final String email;
    private final String data;

    private Auth0LogEventCodeDto auth0LogEventCodeDto;

    public Auth0LogEvent(
            String tenant,
            String type,
            Instant date,
            String logId,
            String clientId,
            String connectionId,
            String userId,
            String userAgent,
            String ip,
            String email,
            String data) {
        this.tenant = tenant;
        this.type = type;
        this.date = date;
        this.logId = logId;
        this.clientId = clientId;
        this.connectionId = connectionId;
        this.userId = userId;
        this.userAgent = userAgent;
        this.ip = ip;
        this.email = email;
        this.data = data;
    }

    public String getTenant() {
        return tenant;
    }

    public String getType() {
        return type;
    }

    public Instant getDate() {
        return date;
    }

    public String getLogId() {
        return logId;
    }

    public String getClientId() {
        return clientId;
    }

    public String getConnectionId() {
        return connectionId;
    }

    public String getUserId() {
        return userId;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getIp() {
        return ip;
    }

    public String getEmail() {
        return email;
    }

    public String getData() {
        return data;
    }

    public Auth0LogEventCodeDto getAuth0LogEventCode() {
        return auth0LogEventCodeDto;
    }

    public void setAuth0LogEventCode(final Auth0LogEventCodeDto auth0LogEventCodeDto) {
        this.auth0LogEventCodeDto = auth0LogEventCodeDto;
    }

    public static Auth0LogEvent createInstance(final Map<String, JsonElement> logEvent, String tenant) {
        return new Auth0LogEvent(
                tenant,
                resolveStringValue(TYPE, logEvent),
                resolveDateTimeValue(DATE, logEvent),
                resolveStringValue(LOG_ID, logEvent),
                resolveStringValue(CLIENT_ID, logEvent),
                resolveStringValue(CONNECTION_ID, logEvent),
                resolveStringValue(USER_ID, logEvent),
                resolveStringValue(USER_AGENT, logEvent),
                resolveStringValue(IP, logEvent),
                resolveStringValue(EMAIL, logEvent),
                resolveStringValue(DATA, logEvent));
    }

    public static class Auth0LogEventCodeDto {

        private final String title;
        private final String description;

        public Auth0LogEventCodeDto(String title, String description) {
            this.title = title;
            this.description = description;
        }

        public String getTitle() {
            return title;
        }

        public String getDescription() {
            return description;
        }

        public static class Auth0LogEventCodeDtoMapper implements RowMapper<Auth0LogEventCodeDto> {
            @Override
            public Auth0LogEventCodeDto map(ResultSet rs, StatementContext ctx) throws SQLException {
                return new Auth0LogEventCodeDto(rs.getString(1), rs.getString(2));
            }
        }
    }
}
