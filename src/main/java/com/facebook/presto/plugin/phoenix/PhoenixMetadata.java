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
package com.facebook.presto.plugin.phoenix;

import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.ColumnMetadata;
import com.facebook.presto.spi.ConnectorInsertTableHandle;
import com.facebook.presto.spi.ConnectorNewTableLayout;
import com.facebook.presto.spi.ConnectorOutputTableHandle;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.ConnectorTableHandle;
import com.facebook.presto.spi.ConnectorTableLayout;
import com.facebook.presto.spi.ConnectorTableLayoutHandle;
import com.facebook.presto.spi.ConnectorTableLayoutResult;
import com.facebook.presto.spi.ConnectorTableMetadata;
import com.facebook.presto.spi.Constraint;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.SchemaTableName;
import com.facebook.presto.spi.SchemaTablePrefix;
import com.facebook.presto.spi.TableNotFoundException;
import com.facebook.presto.spi.connector.ConnectorMetadata;
import com.facebook.presto.spi.connector.ConnectorOutputMetadata;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.airlift.slice.Slice;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static com.facebook.presto.spi.StandardErrorCode.NOT_SUPPORTED;
import static com.facebook.presto.spi.StandardErrorCode.PERMISSION_DENIED;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

public class PhoenixMetadata
        implements ConnectorMetadata
{
    private final PhoenixClient phoenixClient;
    private final boolean allowDropTable;

    private final AtomicReference<Runnable> rollbackAction = new AtomicReference<>();

    public PhoenixMetadata(PhoenixClient phoenixClient, boolean allowDropTable)
    {
        this.phoenixClient = requireNonNull(phoenixClient, "client is null");
        this.allowDropTable = allowDropTable;
    }

    @Override
    public List<String> listSchemaNames(ConnectorSession session)
    {
        return ImmutableList.copyOf(phoenixClient.getSchemaNames());
    }

    @Override
    public PhoenixTableHandle getTableHandle(ConnectorSession session, SchemaTableName tableName)
    {
        return phoenixClient.getTableHandle(tableName);
    }

    @Override
    public List<ConnectorTableLayoutResult> getTableLayouts(ConnectorSession session, ConnectorTableHandle table, Constraint<ColumnHandle> constraint, Optional<Set<ColumnHandle>> desiredColumns)
    {
        PhoenixTableHandle tableHandle = (PhoenixTableHandle) table;
        ConnectorTableLayout layout = new ConnectorTableLayout(new PhoenixTableLayoutHandle(tableHandle, constraint.getSummary()));
        return ImmutableList.of(new ConnectorTableLayoutResult(layout, constraint.getSummary()));
    }

    @Override
    public ConnectorTableLayout getTableLayout(ConnectorSession session, ConnectorTableLayoutHandle handle)
    {
        return new ConnectorTableLayout(handle);
    }

    @Override
    public ConnectorTableMetadata getTableMetadata(ConnectorSession session, ConnectorTableHandle table)
    {
        PhoenixTableHandle handle = (PhoenixTableHandle) table;

        ImmutableList.Builder<ColumnMetadata> columnMetadata = ImmutableList.builder();
        for (PhoenixColumnHandle column : phoenixClient.getColumns(handle)) {
            columnMetadata.add(column.getColumnMetadata());
        }
        return new ConnectorTableMetadata(handle.getSchemaTableName(), columnMetadata.build(), ((PhoenixClient) phoenixClient).getTableProperties(handle));
    }

    @Override
    public List<SchemaTableName> listTables(ConnectorSession session, String schemaNameOrNull)
    {
        return phoenixClient.getTableNames(schemaNameOrNull);
    }

    @Override
    public Map<String, ColumnHandle> getColumnHandles(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        PhoenixTableHandle phoenixTableHandle = (PhoenixTableHandle) tableHandle;

        ImmutableMap.Builder<String, ColumnHandle> columnHandles = ImmutableMap.builder();
        for (PhoenixColumnHandle column : phoenixClient.getColumns(phoenixTableHandle)) {
            columnHandles.put(column.getColumnMetadata().getName(), column);
        }
        return columnHandles.build();
    }

    @Override
    public Map<SchemaTableName, List<ColumnMetadata>> listTableColumns(ConnectorSession session, SchemaTablePrefix prefix)
    {
        ImmutableMap.Builder<SchemaTableName, List<ColumnMetadata>> columns = ImmutableMap.builder();
        List<SchemaTableName> tables;
        if (prefix.getTableName() != null) {
            tables = ImmutableList.of(new SchemaTableName(prefix.getSchemaName(), prefix.getTableName()));
        }
        else {
            tables = listTables(session, prefix.getSchemaName());
        }
        for (SchemaTableName tableName : tables) {
            try {
                PhoenixTableHandle tableHandle = phoenixClient.getTableHandle(tableName);
                if (tableHandle == null) {
                    continue;
                }
                columns.put(tableName, getTableMetadata(session, tableHandle).getColumns());
            }
            catch (TableNotFoundException e) {
                // table disappeared during listing operation
            }
        }
        return columns.build();
    }

    @Override
    public ColumnMetadata getColumnMetadata(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle columnHandle)
    {
        return ((PhoenixColumnHandle) columnHandle).getColumnMetadata();
    }

    @Override
    public void createTable(ConnectorSession session, ConnectorTableMetadata tableMetadata)
    {
        phoenixClient.createTable(tableMetadata);
    }

    @Override
    public void dropTable(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        if (!allowDropTable) {
            throw new PrestoException(PERMISSION_DENIED, "DROP TABLE is disabled in this catalog");
        }
        PhoenixTableHandle handle = (PhoenixTableHandle) tableHandle;
        phoenixClient.dropTable(handle);
    }

    @Override
    public ConnectorOutputTableHandle beginCreateTable(ConnectorSession session, ConnectorTableMetadata tableMetadata, Optional<ConnectorNewTableLayout> layout)
    {
        PhoenixOutputTableHandle handle = phoenixClient.createTable(tableMetadata);
        setRollback(() -> phoenixClient.rollbackCreateTable(handle));
        return handle;
    }

    @Override
    public Optional<ConnectorOutputMetadata> finishCreateTable(ConnectorSession session, ConnectorOutputTableHandle tableHandle, Collection<Slice> fragments)
    {
        clearRollback();
        return Optional.empty();
    }

    private void setRollback(Runnable action)
    {
        checkState(rollbackAction.compareAndSet(null, action), "rollback action is already set");
    }

    private void clearRollback()
    {
        rollbackAction.set(null);
    }

    public void rollback()
    {
        Optional.ofNullable(rollbackAction.getAndSet(null)).ifPresent(Runnable::run);
    }

    @Override
    public ConnectorInsertTableHandle beginInsert(ConnectorSession session, ConnectorTableHandle tableHandle)
    {
        return phoenixClient.beginInsertTable(getTableMetadata(session, tableHandle));
    }

    @Override
    public Optional<ConnectorOutputMetadata> finishInsert(ConnectorSession session, ConnectorInsertTableHandle tableHandle, Collection<Slice> fragments)
    {
        return Optional.empty();
    }

    @Override
    public void addColumn(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnMetadata column)
    {
        throw new PrestoException(NOT_SUPPORTED, "This connector does not support adding columns");
    }

    @Override
    public void dropColumn(ConnectorSession session, ConnectorTableHandle tableHandle, ColumnHandle column)
    {
        throw new PrestoException(NOT_SUPPORTED, "This connector does not support dropping columns");
    }
}