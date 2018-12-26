/*
 * Copyright 2018 The CovenantSQL Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.covenantsql.connector;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.covenantsql.connector.except.CovenantException;
import io.covenantsql.connector.response.CovenantResultSet;
import io.covenantsql.connector.response.beans.CovenantRequestBean;
import io.covenantsql.connector.response.beans.CovenantResponseBean;
import io.covenantsql.connector.settings.CovenantProperties;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.fluent.Executor;
import org.apache.http.client.fluent.Request;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.CloseableHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

public class CovenantStatementImpl extends CovenantMockStatementUnused implements CovenantStatement {
    private static final Logger LOG = LoggerFactory.getLogger(CovenantStatementImpl.class);
    private static final String API_EXEC = "/v1/exec";
    private static final String API_QUERY = "/v1/query";
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final Executor executor;
    private final CloseableHttpClient httpClient;
    private final String database;
    protected CovenantProperties properties;
    private CovenantConnection connection;
    private CovenantResultSet currentResultSet;
    private int currentUpdateCount = -1;
    private int queryTimeout;
    private int maxRows;

    public CovenantStatementImpl(CloseableHttpClient httpClient, CovenantConnection connection, CovenantProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
        this.connection = connection;
        this.database = properties.getDatabase();
        this.executor = Executor.newInstance(httpClient);
    }

    protected static boolean isSelect(String sql) {
        return StringUtils.startsWithIgnoreCase(sql, "SELECT") ||
            StringUtils.startsWithIgnoreCase(sql, "SHOW") ||
            StringUtils.startsWithIgnoreCase(sql, "DESC");
    }

    private static String extractTableName(String sql) {
        if (isSelect(sql)) {
            String[] tokens = StringUtils.splitByWholeSeparatorPreserveAllTokens(sql, null);

            boolean nextIsTableName = false;

            for (String s : tokens) {
                if (nextIsTableName) {
                    // parse table name
                    return StringUtils.stripToEmpty(StringUtils.strip(s, "'`"));
                }

                if (StringUtils.equalsIgnoreCase(s, "FROM")) {
                    nextIsTableName = true;
                }
            }
        }

        return "";
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        return executeQuery(sql, null);
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        return executeUpdate(sql, (List<Object>) null);
    }

    public ResultSet executeQuery(String sql, List<Object> params) throws SQLException {
        try {
            if (isSelect(sql)) {
                CovenantResponseBean resultBean = sendRequest(API_QUERY, sql, params);

                if (!resultBean.isSuccess()) {
                    throw new CovenantException(resultBean.getStatus(), properties.getHost(), properties.getPort());
                }

                currentResultSet = new CovenantResultSet(resultBean.getData(), database, extractTableName(sql), this);
                currentResultSet.setMaxRows(maxRows);
                return currentResultSet;
            } else {
                executeUpdate(sql, params);
                return currentResultSet;
            }
        } catch (Exception e) {
            // re-throw
            throw new CovenantException(e, properties.getHost(), properties.getPort());
        }
    }

    @Override
    public int getUpdateCount() throws SQLException {
        return currentUpdateCount;
    }

    public int executeUpdate(String sql, List<Object> params) throws SQLException {
        try {
            CovenantResponseBean resultBean = sendRequest(API_EXEC, sql, params);

            if (!resultBean.isSuccess()) {
                throw new CovenantException(resultBean.getStatus(), properties.getHost(), properties.getPort());
            }
            currentResultSet = CovenantResultSet.EMPTY;
            currentUpdateCount = resultBean.getData() != null ? resultBean.getData().getAffectedRows() : -1;

            return 1;
        } catch (Exception e) {
            // re-throw
            throw new CovenantException(e, properties.getHost(), properties.getPort());
        }
    }

    public CovenantResponseBean sendRequest(String path, String sql, List<Object> params) throws SQLException {
        try {
            URI uri = new URIBuilder()
                .setHost(properties.getHost())
                .setPort(properties.getPort())
                .setScheme(properties.isSsl() ? "https" : "http")
                .setPath(path)
                .build();

            CovenantRequestBean bean = new CovenantRequestBean();
            bean.setDatabase(properties.getDatabase());
            bean.setQuery(sql);
            bean.setArgs(params);

            return executor.execute(Request.Post(uri)
                .bodyString(objectMapper.writeValueAsString(bean), ContentType.APPLICATION_JSON))
                .handleResponse(new ResponseHandler<CovenantResponseBean>() {
                    @Override
                    public CovenantResponseBean handleResponse(HttpResponse response) throws IOException {
                        return objectMapper.readValue(response.getEntity().getContent(), CovenantResponseBean.class);
                    }
                });
        } catch (Exception e) {
            throw new CovenantException(e, properties.getHost(), properties.getPort());
        }
    }

    @Override
    public void close() throws SQLException {
        if (currentResultSet != null) {
            currentResultSet.close();
        }
    }

    @Override
    public int getMaxRows() throws SQLException {
        return maxRows;
    }

    @Override
    public void setMaxRows(int max) throws SQLException {
        if (max < 0) {
            throw new SQLException(String.format("Illegal maxRows value: %d", max));
        }
        maxRows = max;
    }

    @Override
    public int getQueryTimeout() throws SQLException {
        return queryTimeout;
    }

    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        queryTimeout = seconds;
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        executeQuery(sql);
        return isSelect(sql);
    }

    @Override
    public ResultSet getResultSet() throws SQLException {
        return currentResultSet;
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        if (currentResultSet != null) {
            currentResultSet.close();
            currentResultSet = null;
            currentUpdateCount = -1;
        }

        return false;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return connection;
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isAssignableFrom(getClass())) {
            return iface.cast(this);
        }
        throw new SQLException("Cannot unwrap to " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return iface.isAssignableFrom(getClass());
    }
}
