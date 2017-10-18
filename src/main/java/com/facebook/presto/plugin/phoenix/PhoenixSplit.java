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
import com.facebook.presto.spi.ConnectorSplit;
import com.facebook.presto.spi.HostAddress;
import com.facebook.presto.spi.predicate.TupleDomain;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import org.apache.hadoop.io.DataInputBuffer;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.phoenix.mapreduce.PhoenixInputSplit;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.Base64;
import java.util.List;

import static java.util.Objects.requireNonNull;

public class PhoenixSplit
        implements ConnectorSplit
{
    private final String connectorId;
    private final String catalogName;
    private final String schemaName;
    private final String tableName;
    private final TupleDomain<ColumnHandle> tupleDomain;
    private String scans;
    private PhoenixInputSplit phoenixSplit;

    public PhoenixSplit(
            String connectorId,
            String catalogName,
            String schemaName,
            String tableName,
            TupleDomain<ColumnHandle> tupleDomain,
            PhoenixInputSplit phoenixSplit)
    {
        this.connectorId = requireNonNull(connectorId, "connector id is null");
        this.catalogName = catalogName;
        this.schemaName = schemaName;
        this.tableName = requireNonNull(tableName, "table name is null");
        this.tupleDomain = requireNonNull(tupleDomain, "tupleDomain is null");
        this.phoenixSplit = phoenixSplit;

        try {
            DataOutputBuffer out = new DataOutputBuffer();
            phoenixSplit.write(out);
            this.scans = Base64.getEncoder().encodeToString(out.getData());
        }
        catch (IOException e) {
        }
    }

    @JsonCreator
    public PhoenixSplit(
            @JsonProperty("connectorId") String connectorId,
            @JsonProperty("catalogName") @Nullable String catalogName,
            @JsonProperty("schemaName") @Nullable String schemaName,
            @JsonProperty("tableName") String tableName,
            @JsonProperty("tupleDomain") TupleDomain<ColumnHandle> tupleDomain,
            @JsonProperty("scans") String scans)
    {
        this.connectorId = requireNonNull(connectorId, "connector id is null");
        this.catalogName = catalogName;
        this.schemaName = schemaName;
        this.tableName = requireNonNull(tableName, "table name is null");
        this.tupleDomain = requireNonNull(tupleDomain, "tupleDomain is null");
        this.scans = scans;

        DataInputBuffer in = new DataInputBuffer();
        byte[] byteData = Base64.getDecoder().decode(scans);
        in.reset(byteData, byteData.length);

        phoenixSplit = new PhoenixInputSplit();
        try {
            phoenixSplit.readFields(in);
        }
        catch (IOException e) {
            phoenixSplit = null;
        }
    }

    @JsonProperty
    public String getConnectorId()
    {
        return connectorId;
    }

    @JsonProperty
    @Nullable
    public String getCatalogName()
    {
        return catalogName;
    }

    @JsonProperty
    @Nullable
    public String getSchemaName()
    {
        return schemaName;
    }

    @JsonProperty
    public String getTableName()
    {
        return tableName;
    }

    @JsonProperty
    public TupleDomain<ColumnHandle> getTupleDomain()
    {
        return tupleDomain;
    }

    @JsonProperty
    public String getScans()
    {
        return scans;
    }

    public PhoenixInputSplit getPhoenixInputSplit() {
        return phoenixSplit;
    }

    @Override
    public boolean isRemotelyAccessible()
    {
        return true;
    }

    @Override
    public List<HostAddress> getAddresses()
    {
        try {
            String[] locations = phoenixSplit.getLocations();
            return ImmutableList.of(HostAddress.fromString(locations[0]));
        }
        catch (Exception e) {
            return ImmutableList.of();
        }
    }

    @Override
    public Object getInfo()
    {
        return this;
    }
}
