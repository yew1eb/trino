/*
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
package io.trino.plugin.hive.metastore.thrift;

import com.google.common.collect.ImmutableList;
import io.airlift.log.Logger;
import io.trino.plugin.base.util.LoggingInvocationHandler;
import io.trino.plugin.base.util.LoggingInvocationHandler.AirliftParameterNamesProvider;
import io.trino.plugin.base.util.LoggingInvocationHandler.ParameterNamesProvider;
import io.trino.plugin.hive.acid.AcidOperation;
import io.trino.plugin.hive.metastore.thrift.MetastoreSupportsDateStatistics.DateStatisticsSupport;
import org.apache.hadoop.hive.common.ValidTxnList;
import org.apache.hadoop.hive.metastore.TableType;
import org.apache.hadoop.hive.metastore.api.AbortTxnRequest;
import org.apache.hadoop.hive.metastore.api.AddDynamicPartitions;
import org.apache.hadoop.hive.metastore.api.AllocateTableWriteIdsRequest;
import org.apache.hadoop.hive.metastore.api.AllocateTableWriteIdsResponse;
import org.apache.hadoop.hive.metastore.api.AlterPartitionsRequest;
import org.apache.hadoop.hive.metastore.api.AlterTableRequest;
import org.apache.hadoop.hive.metastore.api.CheckLockRequest;
import org.apache.hadoop.hive.metastore.api.ClientCapabilities;
import org.apache.hadoop.hive.metastore.api.ClientCapability;
import org.apache.hadoop.hive.metastore.api.ColumnStatistics;
import org.apache.hadoop.hive.metastore.api.ColumnStatisticsDesc;
import org.apache.hadoop.hive.metastore.api.ColumnStatisticsObj;
import org.apache.hadoop.hive.metastore.api.CommitTxnRequest;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.EnvironmentContext;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.GetPrincipalsInRoleRequest;
import org.apache.hadoop.hive.metastore.api.GetPrincipalsInRoleResponse;
import org.apache.hadoop.hive.metastore.api.GetRoleGrantsForPrincipalRequest;
import org.apache.hadoop.hive.metastore.api.GetRoleGrantsForPrincipalResponse;
import org.apache.hadoop.hive.metastore.api.GetTableRequest;
import org.apache.hadoop.hive.metastore.api.GetValidWriteIdsRequest;
import org.apache.hadoop.hive.metastore.api.GrantRevokePrivilegeRequest;
import org.apache.hadoop.hive.metastore.api.GrantRevokeRoleRequest;
import org.apache.hadoop.hive.metastore.api.GrantRevokeRoleResponse;
import org.apache.hadoop.hive.metastore.api.HeartbeatTxnRangeRequest;
import org.apache.hadoop.hive.metastore.api.HiveObjectPrivilege;
import org.apache.hadoop.hive.metastore.api.HiveObjectRef;
import org.apache.hadoop.hive.metastore.api.LockRequest;
import org.apache.hadoop.hive.metastore.api.LockResponse;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.hadoop.hive.metastore.api.OpenTxnRequest;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.PartitionsStatsRequest;
import org.apache.hadoop.hive.metastore.api.PrincipalType;
import org.apache.hadoop.hive.metastore.api.PrivilegeBag;
import org.apache.hadoop.hive.metastore.api.Role;
import org.apache.hadoop.hive.metastore.api.RolePrincipalGrant;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.api.TableStatsRequest;
import org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore;
import org.apache.hadoop.hive.metastore.api.TxnToWriteId;
import org.apache.hadoop.hive.metastore.api.UnlockRequest;
import org.apache.hadoop.hive.metastore.txn.TxnUtils;
import org.apache.thrift.TApplicationException;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.TTransport;
import org.apache.thrift.transport.TTransportException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Throwables.propagateIfPossible;
import static com.google.common.base.Throwables.throwIfUnchecked;
import static com.google.common.base.Verify.verify;
import static com.google.common.base.Verify.verifyNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.reflect.Reflection.newProxy;
import static io.trino.plugin.hive.ViewReaderUtil.PRESTO_VIEW_FLAG;
import static io.trino.plugin.hive.metastore.thrift.MetastoreSupportsDateStatistics.DateStatisticsSupport.NOT_SUPPORTED;
import static io.trino.plugin.hive.metastore.thrift.MetastoreSupportsDateStatistics.DateStatisticsSupport.SUPPORTED;
import static io.trino.plugin.hive.metastore.thrift.MetastoreSupportsDateStatistics.DateStatisticsSupport.UNKNOWN;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static org.apache.hadoop.hive.metastore.api.GrantRevokeType.GRANT;
import static org.apache.hadoop.hive.metastore.api.GrantRevokeType.REVOKE;
import static org.apache.hadoop.hive.metastore.api.hive_metastoreConstants.HIVE_FILTER_FIELD_PARAMS;
import static org.apache.hadoop.hive.metastore.txn.TxnUtils.createValidTxnWriteIdList;
import static org.apache.thrift.TApplicationException.UNKNOWN_METHOD;

public class ThriftHiveMetastoreClient
        implements ThriftMetastoreClient
{
    private static final Logger log = Logger.get(ThriftHiveMetastoreClient.class);

    private static final ParameterNamesProvider PARAMETER_NAMES_PROVIDER = new AirliftParameterNamesProvider(ThriftHiveMetastore.Iface.class, ThriftHiveMetastore.Client.class);

    private static final Pattern TABLE_PARAMETER_SAFE_KEY_PATTERN = Pattern.compile("^[a-zA-Z_]+$");
    private static final Pattern TABLE_PARAMETER_SAFE_VALUE_PATTERN = Pattern.compile("^[a-zA-Z0-9\\s]*$");

    private final TransportSupplier transportSupplier;
    private TTransport transport;
    protected ThriftHiveMetastore.Iface client;
    private final String hostname;

    private final MetastoreSupportsDateStatistics metastoreSupportsDateStatistics;
    private final AtomicInteger chosenGetTableAlternative;
    private final AtomicInteger chosenTableParamAlternative;
    private final AtomicInteger chosenGetAllViewsAlternative;
    private final AtomicInteger chosenAlterTransactionalTableAlternative;
    private final AtomicInteger chosenAlterPartitionsAlternative;

    public ThriftHiveMetastoreClient(
            TransportSupplier transportSupplier,
            String hostname,
            MetastoreSupportsDateStatistics metastoreSupportsDateStatistics,
            AtomicInteger chosenGetTableAlternative,
            AtomicInteger chosenTableParamAlternative,
            AtomicInteger chosenGetAllViewsAlternative,
            AtomicInteger chosenAlterTransactionalTableAlternative,
            AtomicInteger chosenAlterPartitionsAlternative)
            throws TTransportException
    {
        this.transportSupplier = requireNonNull(transportSupplier, "transportSupplier is null");
        this.hostname = requireNonNull(hostname, "hostname is null");
        this.metastoreSupportsDateStatistics = requireNonNull(metastoreSupportsDateStatistics, "metastoreSupportsDateStatistics is null");
        this.chosenGetTableAlternative = requireNonNull(chosenGetTableAlternative, "chosenGetTableAlternative is null");
        this.chosenTableParamAlternative = requireNonNull(chosenTableParamAlternative, "chosenTableParamAlternative is null");
        this.chosenGetAllViewsAlternative = requireNonNull(chosenGetAllViewsAlternative, "chosenGetAllViewsAlternative is null");
        this.chosenAlterTransactionalTableAlternative = requireNonNull(chosenAlterTransactionalTableAlternative, "chosenAlterTransactionalTableAlternative is null");
        this.chosenAlterPartitionsAlternative = requireNonNull(chosenAlterPartitionsAlternative, "chosenAlterPartitionsAlternative is null");

        connect();
    }

    private void connect()
            throws TTransportException
    {
        transport = transportSupplier.createTransport();
        ThriftHiveMetastore.Iface client = new ThriftHiveMetastore.Client(new TBinaryProtocol(transport));
        if (log.isDebugEnabled()) {
            client = newProxy(ThriftHiveMetastore.Iface.class, new LoggingInvocationHandler(client, PARAMETER_NAMES_PROVIDER, log::debug));
        }
        this.client = client;
    }

    @Override
    public void close()
    {
        disconnect();
    }

    private void disconnect()
    {
        transport.close();
    }

    @Override
    public List<String> getAllDatabases()
            throws TException
    {
        return client.get_all_databases();
    }

    @Override
    public Database getDatabase(String dbName)
            throws TException
    {
        return client.get_database(dbName);
    }

    @Override
    public List<String> getAllTables(String databaseName)
            throws TException
    {
        return client.get_all_tables(databaseName);
    }

    @Override
    public List<String> getAllViews(String databaseName)
            throws TException
    {
        return alternativeCall(
                exception -> !isUnknownMethodExceptionalResponse(exception),
                chosenGetAllViewsAlternative,
                () -> client.get_tables_by_type(databaseName, ".*", TableType.VIRTUAL_VIEW.name()),
                // fallback to enumerating Presto views only (Hive views can still be executed, but will be listed as tables and not views)
                () -> getTablesWithParameter(databaseName, PRESTO_VIEW_FLAG, "true"));
    }

    @Override
    public List<String> getTablesWithParameter(String databaseName, String parameterKey, String parameterValue)
            throws TException
    {
        checkArgument(TABLE_PARAMETER_SAFE_KEY_PATTERN.matcher(parameterKey).matches(), "Parameter key contains invalid characters: '%s'", parameterKey);
        /*
         * The parameter value is restricted to have only alphanumeric characters so that it's safe
         * to be used against HMS. When using with a LIKE operator, the HMS may want the parameter
         * value to follow a Java regex pattern or an SQL pattern. And it's hard to predict the
         * HMS's behavior from outside. Also, by restricting parameter values, we avoid the problem
         * of how to quote them when passing within the filter string.
         */
        checkArgument(TABLE_PARAMETER_SAFE_VALUE_PATTERN.matcher(parameterValue).matches(), "Parameter value contains invalid characters: '%s'", parameterValue);
        /*
         * Thrift call `get_table_names_by_filter` may be translated by Metastore to an SQL query against Metastore database.
         * Hive 2.3 on some databases uses CLOB for table parameter value column and some databases disallow `=` predicate over
         * CLOB values. At the same time, they allow `LIKE` predicates over them.
         */
        String filterWithEquals = HIVE_FILTER_FIELD_PARAMS + parameterKey + " = \"" + parameterValue + "\"";
        String filterWithLike = HIVE_FILTER_FIELD_PARAMS + parameterKey + " LIKE \"" + parameterValue + "\"";

        return alternativeCall(
                ThriftHiveMetastoreClient::defaultIsValidExceptionalResponse,
                chosenTableParamAlternative,
                () -> client.get_table_names_by_filter(databaseName, filterWithEquals, (short) -1),
                () -> client.get_table_names_by_filter(databaseName, filterWithLike, (short) -1));
    }

    @Override
    public void createDatabase(Database database)
            throws TException
    {
        client.create_database(database);
    }

    @Override
    public void dropDatabase(String databaseName, boolean deleteData, boolean cascade)
            throws TException
    {
        client.drop_database(databaseName, deleteData, cascade);
    }

    @Override
    public void alterDatabase(String databaseName, Database database)
            throws TException
    {
        client.alter_database(databaseName, database);
    }

    @Override
    public void createTable(Table table)
            throws TException
    {
        client.create_table(table);
    }

    @Override
    public void dropTable(String databaseName, String name, boolean deleteData)
            throws TException
    {
        client.drop_table(databaseName, name, deleteData);
    }

    @Override
    public void alterTableWithEnvironmentContext(String databaseName, String tableName, Table newTable, EnvironmentContext context)
            throws TException
    {
        client.alter_table_with_environment_context(databaseName, tableName, newTable, context);
    }

    @Override
    public Table getTable(String databaseName, String tableName)
            throws TException
    {
        return alternativeCall(
                ThriftHiveMetastoreClient::defaultIsValidExceptionalResponse,
                chosenGetTableAlternative,
                () -> {
                    GetTableRequest request = new GetTableRequest(databaseName, tableName);
                    request.setCapabilities(new ClientCapabilities(ImmutableList.of(ClientCapability.INSERT_ONLY_TABLES)));
                    return client.get_table_req(request).getTable();
                },
                () -> client.get_table(databaseName, tableName));
    }

    @Override
    public List<FieldSchema> getFields(String databaseName, String tableName)
            throws TException
    {
        return client.get_fields(databaseName, tableName);
    }

    @Override
    public List<ColumnStatisticsObj> getTableColumnStatistics(String databaseName, String tableName, List<String> columnNames)
            throws TException
    {
        TableStatsRequest tableStatsRequest = new TableStatsRequest(databaseName, tableName, columnNames);
        return client.get_table_statistics_req(tableStatsRequest).getTableStats();
    }

    @Override
    public void setTableColumnStatistics(String databaseName, String tableName, List<ColumnStatisticsObj> statistics)
            throws TException
    {
        setColumnStatistics(
                format("table %s.%s", databaseName, tableName),
                statistics,
                stats -> {
                    ColumnStatisticsDesc statisticsDescription = new ColumnStatisticsDesc(true, databaseName, tableName);
                    ColumnStatistics request = new ColumnStatistics(statisticsDescription, stats);
                    client.update_table_column_statistics(request);
                });
    }

    @Override
    public void deleteTableColumnStatistics(String databaseName, String tableName, String columnName)
            throws TException
    {
        client.delete_table_column_statistics(databaseName, tableName, columnName);
    }

    @Override
    public Map<String, List<ColumnStatisticsObj>> getPartitionColumnStatistics(String databaseName, String tableName, List<String> partitionNames, List<String> columnNames)
            throws TException
    {
        PartitionsStatsRequest partitionsStatsRequest = new PartitionsStatsRequest(databaseName, tableName, columnNames, partitionNames);
        return client.get_partitions_statistics_req(partitionsStatsRequest).getPartStats();
    }

    @Override
    public void setPartitionColumnStatistics(String databaseName, String tableName, String partitionName, List<ColumnStatisticsObj> statistics)
            throws TException
    {
        setColumnStatistics(
                format("partition of table %s.%s", databaseName, tableName),
                statistics,
                stats -> {
                    ColumnStatisticsDesc statisticsDescription = new ColumnStatisticsDesc(false, databaseName, tableName);
                    statisticsDescription.setPartName(partitionName);
                    ColumnStatistics request = new ColumnStatistics(statisticsDescription, stats);
                    client.update_partition_column_statistics(request);
                });
    }

    @Override
    public void deletePartitionColumnStatistics(String databaseName, String tableName, String partitionName, String columnName)
            throws TException
    {
        client.delete_partition_column_statistics(databaseName, tableName, partitionName, columnName);
    }

    private void setColumnStatistics(String objectName, List<ColumnStatisticsObj> statistics, UnaryCall<List<ColumnStatisticsObj>> saveColumnStatistics)
            throws TException
    {
        boolean containsDateStatistics = statistics.stream().anyMatch(stats -> stats.getStatsData().isSetDateStats());

        DateStatisticsSupport dateStatisticsSupported = this.metastoreSupportsDateStatistics.isSupported();
        if (containsDateStatistics && dateStatisticsSupported == NOT_SUPPORTED) {
            log.debug("Skipping date statistics for %s because metastore does not support them", objectName);
            statistics = statistics.stream()
                    .filter(stats -> !stats.getStatsData().isSetDateStats())
                    .collect(toImmutableList());
            containsDateStatistics = false;
        }

        if (!containsDateStatistics || dateStatisticsSupported == SUPPORTED) {
            saveColumnStatistics.call(statistics);
            return;
        }

        List<ColumnStatisticsObj> statisticsExceptDate = statistics.stream()
                .filter(stats -> !stats.getStatsData().isSetDateStats())
                .collect(toImmutableList());

        List<ColumnStatisticsObj> dateStatistics = statistics.stream()
                .filter(stats -> stats.getStatsData().isSetDateStats())
                .collect(toImmutableList());

        verify(!dateStatistics.isEmpty() && dateStatisticsSupported == UNKNOWN);

        if (!statisticsExceptDate.isEmpty()) {
            saveColumnStatistics.call(statisticsExceptDate);
        }

        try {
            saveColumnStatistics.call(dateStatistics);
        }
        catch (TException e) {
            // When `dateStatistics.size() > 1` we expect something like "TApplicationException: Required field 'colName' is unset! Struct:ColumnStatisticsObj(colName:null, colType:null, statsData:null)"
            // When `dateStatistics.size() == 1` we expect something like "TTransportException: java.net.SocketTimeoutException: Read timed out"
            log.warn(e, "Failed to save date statistics for %s. Metastore might not support date statistics", objectName);
            if (!statisticsExceptDate.isEmpty()) {
                this.metastoreSupportsDateStatistics.failed();
            }
            return;
        }
        this.metastoreSupportsDateStatistics.succeeded();
    }

    @Override
    public List<String> getPartitionNames(String databaseName, String tableName)
            throws TException
    {
        return client.get_partition_names(databaseName, tableName, (short) -1);
    }

    @Override
    public List<String> getPartitionNamesFiltered(String databaseName, String tableName, List<String> partitionValues)
            throws TException
    {
        return client.get_partition_names_ps(databaseName, tableName, partitionValues, (short) -1);
    }

    @Override
    public int addPartitions(List<Partition> newPartitions)
            throws TException
    {
        return client.add_partitions(newPartitions);
    }

    @Override
    public boolean dropPartition(String databaseName, String tableName, List<String> partitionValues, boolean deleteData)
            throws TException
    {
        return client.drop_partition(databaseName, tableName, partitionValues, deleteData);
    }

    @Override
    public void alterPartition(String databaseName, String tableName, Partition partition)
            throws TException
    {
        client.alter_partition(databaseName, tableName, partition);
    }

    @Override
    public Partition getPartition(String databaseName, String tableName, List<String> partitionValues)
            throws TException
    {
        return client.get_partition(databaseName, tableName, partitionValues);
    }

    @Override
    public List<Partition> getPartitionsByNames(String databaseName, String tableName, List<String> partitionNames)
            throws TException
    {
        return client.get_partitions_by_names(databaseName, tableName, partitionNames);
    }

    @Override
    public List<Role> listRoles(String principalName, PrincipalType principalType)
            throws TException
    {
        return client.list_roles(principalName, principalType);
    }

    @Override
    public List<HiveObjectPrivilege> listPrivileges(String principalName, PrincipalType principalType, HiveObjectRef hiveObjectRef)
            throws TException
    {
        return client.list_privileges(principalName, principalType, hiveObjectRef);
    }

    @Override
    public List<String> getRoleNames()
            throws TException
    {
        return client.get_role_names();
    }

    @Override
    public void createRole(String roleName, String grantor)
            throws TException
    {
        Role role = new Role(roleName, 0, grantor);
        client.create_role(role);
    }

    @Override
    public void dropRole(String role)
            throws TException
    {
        client.drop_role(role);
    }

    @Override
    public boolean grantPrivileges(PrivilegeBag privilegeBag)
            throws TException
    {
        return client.grant_revoke_privileges(new GrantRevokePrivilegeRequest(GRANT, privilegeBag)).isSuccess();
    }

    @Override
    public boolean revokePrivileges(PrivilegeBag privilegeBag, boolean revokeGrantOption)
            throws TException
    {
        GrantRevokePrivilegeRequest grantRevokePrivilegeRequest = new GrantRevokePrivilegeRequest(REVOKE, privilegeBag);
        grantRevokePrivilegeRequest.setRevokeGrantOption(revokeGrantOption);
        return client.grant_revoke_privileges(grantRevokePrivilegeRequest).isSuccess();
    }

    @Override
    public void grantRole(String role, String granteeName, PrincipalType granteeType, String grantorName, PrincipalType grantorType, boolean grantOption)
            throws TException
    {
        List<RolePrincipalGrant> grants = listRoleGrants(granteeName, granteeType);
        for (RolePrincipalGrant grant : grants) {
            if (grant.getRoleName().equals(role)) {
                if (grant.isGrantOption() == grantOption) {
                    return;
                }
                if (!grant.isGrantOption() && grantOption) {
                    revokeRole(role, granteeName, granteeType, false);
                    break;
                }
            }
        }
        createGrant(role, granteeName, granteeType, grantorName, grantorType, grantOption);
    }

    private void createGrant(String role, String granteeName, PrincipalType granteeType, String grantorName, PrincipalType grantorType, boolean grantOption)
            throws TException
    {
        GrantRevokeRoleRequest request = new GrantRevokeRoleRequest();
        request.setRequestType(GRANT);
        request.setRoleName(role);
        request.setPrincipalName(granteeName);
        request.setPrincipalType(granteeType);
        request.setGrantor(grantorName);
        request.setGrantorType(grantorType);
        request.setGrantOption(grantOption);
        GrantRevokeRoleResponse response = client.grant_revoke_role(request);
        if (!response.isSetSuccess()) {
            throw new MetaException("GrantRevokeResponse missing success field");
        }
    }

    @Override
    public void revokeRole(String role, String granteeName, PrincipalType granteeType, boolean grantOption)
            throws TException
    {
        List<RolePrincipalGrant> grants = listRoleGrants(granteeName, granteeType);
        RolePrincipalGrant currentGrant = null;
        for (RolePrincipalGrant grant : grants) {
            if (grant.getRoleName().equals(role)) {
                currentGrant = grant;
                break;
            }
        }

        if (currentGrant == null) {
            return;
        }

        if (!currentGrant.isGrantOption() && grantOption) {
            return;
        }

        removeGrant(role, granteeName, granteeType, grantOption);
    }

    private void removeGrant(String role, String granteeName, PrincipalType granteeType, boolean grantOption)
            throws TException
    {
        GrantRevokeRoleRequest request = new GrantRevokeRoleRequest();
        request.setRequestType(REVOKE);
        request.setRoleName(role);
        request.setPrincipalName(granteeName);
        request.setPrincipalType(granteeType);
        request.setGrantOption(grantOption);
        GrantRevokeRoleResponse response = client.grant_revoke_role(request);
        if (!response.isSetSuccess()) {
            throw new MetaException("GrantRevokeResponse missing success field");
        }
    }

    @Override
    public List<RolePrincipalGrant> listGrantedPrincipals(String role)
            throws TException
    {
        GetPrincipalsInRoleRequest request = new GetPrincipalsInRoleRequest(role);
        GetPrincipalsInRoleResponse response = client.get_principals_in_role(request);
        return ImmutableList.copyOf(response.getPrincipalGrants());
    }

    @Override
    public List<RolePrincipalGrant> listRoleGrants(String principalName, PrincipalType principalType)
            throws TException
    {
        GetRoleGrantsForPrincipalRequest request = new GetRoleGrantsForPrincipalRequest(principalName, principalType);
        GetRoleGrantsForPrincipalResponse resp = client.get_role_grants_for_principal(request);
        return ImmutableList.copyOf(resp.getPrincipalGrants());
    }

    @Override
    public void setUGI(String userName)
            throws TException
    {
        client.set_ugi(userName, new ArrayList<>());
    }

    @Override
    public long openTransaction(String user)
            throws TException
    {
        OpenTxnRequest request = new OpenTxnRequest(1, user, hostname);
        return client.open_txns(request).getTxn_ids().get(0);
    }

    @Override
    public void commitTransaction(long transactionId)
            throws TException
    {
        client.commit_txn(new CommitTxnRequest(transactionId));
    }

    @Override
    public void abortTransaction(long transactionId)
            throws TException
    {
        client.abort_txn(new AbortTxnRequest(transactionId));
    }

    @Override
    public void sendTransactionHeartbeat(long transactionId)
            throws TException
    {
        HeartbeatTxnRangeRequest request = new HeartbeatTxnRangeRequest(transactionId, transactionId);
        client.heartbeat_txn_range(request);
    }

    @Override
    public LockResponse acquireLock(LockRequest lockRequest)
            throws TException
    {
        return client.lock(lockRequest);
    }

    @Override
    public LockResponse checkLock(long lockId)
            throws TException
    {
        return client.check_lock(new CheckLockRequest(lockId));
    }

    @Override
    public void unlock(long lockId)
            throws TException
    {
        client.unlock(new UnlockRequest(lockId));
    }

    @Override
    public String getValidWriteIds(List<String> tableList, long currentTransactionId)
            throws TException
    {
        // Pass currentTxn as 0L to get the recent snapshot of valid transactions in Hive
        // Do not pass currentTransactionId instead as it will break Hive's listing of delta directories if major compaction
        // deletes delta directories for valid transactions that existed at the time transaction is opened
        ValidTxnList validTransactions = TxnUtils.createValidReadTxnList(client.get_open_txns(), 0L);
        GetValidWriteIdsRequest request = new GetValidWriteIdsRequest(tableList, validTransactions.toString());
        return createValidTxnWriteIdList(
                currentTransactionId,
                client.get_valid_write_ids(request).getTblValidWriteIds())
                .toString();
    }

    @Override
    public String getConfigValue(String name, String defaultValue)
            throws TException
    {
        return client.get_config_value(name, defaultValue);
    }

    @Override
    public String getDelegationToken(String userName)
            throws TException
    {
        return client.get_delegation_token(userName, userName);
    }

    @Override
    public List<TxnToWriteId> allocateTableWriteIds(String dbName, String tableName, List<Long> transactionIds)
            throws TException
    {
        AllocateTableWriteIdsRequest request = new AllocateTableWriteIdsRequest(dbName, tableName);
        request.setTxnIds(transactionIds);
        AllocateTableWriteIdsResponse response = client.allocate_table_write_ids(request);
        return response.getTxnToWriteIds();
    }

    @Override
    public void alterPartitions(String dbName, String tableName, List<Partition> partitions, long writeId)
            throws TException
    {
        alternativeCall(
                exception -> !isUnknownMethodExceptionalResponse(exception),
                chosenAlterPartitionsAlternative,
                () -> {
                    AlterPartitionsRequest request = new AlterPartitionsRequest(dbName, tableName, partitions);
                    request.setWriteId(writeId);
                    client.alter_partitions_req(request);
                    return null;
                },
                () -> {
                    client.alter_partitions_with_environment_context(dbName, tableName, partitions, new EnvironmentContext());
                    return null;
                });
    }

    @Override
    public void addDynamicPartitions(String dbName, String tableName, List<String> partitionNames, long transactionId, long writeId, AcidOperation operation)
            throws TException
    {
        AddDynamicPartitions request = new AddDynamicPartitions(transactionId, writeId, dbName, tableName, partitionNames);
        request.setOperationType(operation.getMetastoreOperationType().orElseThrow());
        client.add_dynamic_partitions(request);
    }

    @Override
    public void alterTransactionalTable(Table table, long transactionId, long writeId, EnvironmentContext environmentContext)
            throws TException
    {
        long originalWriteId = table.getWriteId();
        alternativeCall(
                exception -> !isUnknownMethodExceptionalResponse(exception),
                chosenAlterTransactionalTableAlternative,
                () -> {
                    table.setWriteId(writeId);
                    checkArgument(writeId >= table.getWriteId(), "The writeId supplied %s should be greater than or equal to the table writeId %s", writeId, table.getWriteId());
                    AlterTableRequest request = new AlterTableRequest(table.getDbName(), table.getTableName(), table);
                    request.setValidWriteIdList(getValidWriteIds(ImmutableList.of(format("%s.%s", table.getDbName(), table.getTableName())), transactionId));
                    request.setWriteId(writeId);
                    request.setEnvironmentContext(environmentContext);
                    client.alter_table_req(request);
                    return null;
                },
                () -> {
                    table.setWriteId(originalWriteId);
                    client.alter_table_with_environment_context(table.getDbName(), table.getTableName(), table, environmentContext);
                    return null;
                });
    }

    @SafeVarargs
    private <T> T alternativeCall(
            Predicate<Exception> isValidExceptionalResponse,
            AtomicInteger chosenAlternative,
            AlternativeCall<T>... alternatives)
            throws TException
    {
        checkArgument(alternatives.length > 0, "No alternatives");
        int chosen = chosenAlternative.get();
        checkArgument(chosen == Integer.MAX_VALUE || (0 <= chosen && chosen < alternatives.length), "Bad chosen alternative value: %s", chosen);

        if (chosen != Integer.MAX_VALUE) {
            return alternatives[chosen].execute();
        }

        Exception firstException = null;
        for (int i = 0; i < alternatives.length; i++) {
            int position = i;
            try {
                T result = alternatives[i].execute();
                chosenAlternative.updateAndGet(currentChosen -> Math.min(currentChosen, position));
                return result;
            }
            catch (TException | RuntimeException exception) {
                if (isValidExceptionalResponse.test(exception)) {
                    // This is likely a valid response. We are not settling on an alternative yet.
                    // We will do it later when we get a more obviously valid response.
                    throw exception;
                }
                if (firstException == null) {
                    firstException = exception;
                }
                else if (firstException != exception) {
                    firstException.addSuppressed(exception);
                }
                // Client that threw exception is in an unknown state. We need to open it again to
                // make sure it will respond properly to the next call.
                disconnect();
                connect();
            }
        }

        verifyNotNull(firstException);
        propagateIfPossible(firstException, TException.class);
        throw propagate(firstException);
    }

    // TODO we should recognize exceptions which we suppress and try different alternative call
    // this requires product tests with HDP 3
    private static boolean defaultIsValidExceptionalResponse(Exception exception)
    {
        if (exception instanceof NoSuchObjectException) {
            return true;
        }

        if (exception.toString().contains("AccessControlException")) {
            // e.g. org.apache.hadoop.hive.metastore.api.MetaException: org.apache.hadoop.security.AccessControlException: Permission denied: ...
            return true;
        }

        return false;
    }

    private static boolean isUnknownMethodExceptionalResponse(Exception exception)
    {
        if (!(exception instanceof TApplicationException applicationException)) {
            return false;
        }

        return applicationException.getType() == UNKNOWN_METHOD;
    }

    private static RuntimeException propagate(Throwable throwable)
    {
        if (throwable instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        throwIfUnchecked(throwable);
        throw new RuntimeException(throwable);
    }

    @FunctionalInterface
    private interface AlternativeCall<T>
    {
        T execute()
                throws TException;
    }

    @FunctionalInterface
    private interface UnaryCall<A>
    {
        void call(A arg)
                throws TException;
    }

    public interface TransportSupplier
    {
        TTransport createTransport()
                throws TTransportException;
    }
}
