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

import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.RecordCursor;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.block.BlockBuilderStatus;
import com.facebook.presto.spi.type.BigintType;
import com.facebook.presto.spi.type.CharType;
import com.facebook.presto.spi.type.DateType;
import com.facebook.presto.spi.type.DecimalType;
import com.facebook.presto.spi.type.IntegerType;
import com.facebook.presto.spi.type.RealType;
import com.facebook.presto.spi.type.SmallintType;
import com.facebook.presto.spi.type.TimeType;
import com.facebook.presto.spi.type.TimestampType;
import com.facebook.presto.spi.type.TinyintType;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.type.TypeUtils;
import com.facebook.presto.spi.type.VarbinaryType;
import com.facebook.presto.spi.type.VarcharType;
import com.google.common.base.CharMatcher;
import com.google.common.collect.ImmutableList;
import io.airlift.log.Logger;
import io.airlift.slice.Slice;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.lib.db.DBWritable;
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl;
import org.apache.phoenix.jdbc.PhoenixConnection;
import org.apache.phoenix.jdbc.PhoenixResultSet;
import org.apache.phoenix.mapreduce.PhoenixInputFormat;
import org.apache.phoenix.mapreduce.PhoenixInputSplit;
import org.apache.phoenix.mapreduce.util.PhoenixConfigurationUtil;
import org.apache.phoenix.schema.tuple.ResultTuple;
import org.joda.time.chrono.ISOChronology;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.facebook.presto.plugin.phoenix.PhoenixClient.buildInputSplit;
import static com.facebook.presto.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static com.facebook.presto.spi.type.Decimals.encodeScaledValue;
import static com.facebook.presto.spi.type.Decimals.isShortDecimal;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static io.airlift.slice.Slices.utf8Slice;
import static io.airlift.slice.Slices.wrappedBuffer;
import static java.lang.Float.floatToRawIntBits;
import static java.util.Objects.requireNonNull;
import static org.apache.hadoop.hbase.client.Result.getTotalSizeOfCells;
import static org.joda.time.DateTimeZone.UTC;

public class PhoenixRecordCursor
        implements RecordCursor
{
    private static final Logger log = Logger.get(PhoenixRecordCursor.class);

    private static final ISOChronology UTC_CHRONOLOGY = ISOChronology.getInstance(UTC);

    private final List<PhoenixColumnHandle> columnHandles;

    private final PhoenixConnection connection;
    private final PhoenixResultSet resultSet;
    private boolean closed;

    private long bytesRead;
    private long nanoStart;
    private long nanoEnd;

    public PhoenixRecordCursor(PhoenixClient phoenixClient, PhoenixSplit split, List<PhoenixColumnHandle> columnHandles)
    {
        this.columnHandles = ImmutableList.copyOf(requireNonNull(columnHandles, "columnHandles is null"));

        try {
            connection = ((PhoenixClient) phoenixClient).getConnection();

            String inputQuery = ((PhoenixClient) phoenixClient).buildSql(connection,
                    split.getCatalogName(),
                    split.getSchemaName(),
                    split.getTableName(),
                    split.getTupleDomain(),
                    columnHandles);

            List<InputSplit> splits = buildInputSplit(connection, inputQuery)
                    .stream().filter(inputSplit -> inputSplit.equals(split.getPhoenixInputSplit())).collect(Collectors.toList());

            PhoenixInputSplit phoenixInputSplit = (PhoenixInputSplit) splits.get(0);

            Configuration configuration = HBaseConfiguration.create(connection.getQueryServices().getConfiguration());
            PhoenixConfigurationUtil.setInputQuery(configuration, inputQuery);
            RecordReader<NullWritable, DBWritable> reader = new PhoenixInputFormat<>().createRecordReader(phoenixInputSplit, new TaskAttemptContextImpl(configuration, new TaskAttemptID()));
            reader.initialize(phoenixInputSplit, null);

            Field field = reader.getClass().getDeclaredField("resultSet");
            field.setAccessible(true);
            resultSet = (PhoenixResultSet) field.get(reader);

            log.debug("scan data: %s, bytes: %d in %s", phoenixInputSplit.getKeyRange(), phoenixInputSplit.getLength(), phoenixInputSplit.getLocations()[0]);
        }
        catch (Exception e) {
            throw handleSqlException(e);
        }
    }

    @Override
    public long getReadTimeNanos()
    {
        return nanoStart > 0L ? (nanoEnd == 0 ? System.nanoTime() : nanoEnd) - nanoStart : 0L;
    }

    @Override
    public long getCompletedBytes()
    {
        return bytesRead;
    }

    @Override
    public Type getType(int field)
    {
        return columnHandles.get(field).getColumnType();
    }

    @Override
    public boolean advanceNextPosition()
    {
        if (closed) {
            return false;
        }

        if (nanoStart == 0) {
            nanoStart = System.nanoTime();
        }

        try {
            boolean result = resultSet.next();
            if (!result) {
                close();
            }
            else {
                bytesRead += getTotalSizeOfCells(((ResultTuple) resultSet.getCurrentRow()).getResult());
            }
            return result;
        }
        catch (SQLException | RuntimeException e) {
            e.printStackTrace();
            throw handleSqlException(e);
        }
    }

    @Override
    public boolean getBoolean(int field)
    {
        checkState(!closed, "cursor is closed");
        try {
            return resultSet.getBoolean(field + 1);
        }
        catch (SQLException | RuntimeException e) {
            throw handleSqlException(e);
        }
    }

    @Override
    public long getLong(int field)
    {
        checkState(!closed, "cursor is closed");
        try {
            Type type = getType(field);
            if (type.equals(TinyintType.TINYINT)) {
                return (long) resultSet.getByte(field + 1);
            }
            if (type.equals(SmallintType.SMALLINT)) {
                return (long) resultSet.getShort(field + 1);
            }
            if (type.equals(IntegerType.INTEGER)) {
                return (long) resultSet.getInt(field + 1);
            }
            if (type.equals(RealType.REAL)) {
                return (long) floatToRawIntBits(resultSet.getFloat(field + 1));
            }
            if (isShortDecimal(type)) {
                BigDecimal decimal = resultSet.getBigDecimal(field + 1);
                return decimal.unscaledValue().longValue();
            }
            if (type.equals(BigintType.BIGINT)) {
                return resultSet.getLong(field + 1);
            }
            if (type.equals(DateType.DATE)) {
                // JDBC returns a date using a timestamp at midnight in the JVM timezone
                long localMillis = resultSet.getDate(field + 1).getTime();
                // Convert it to a midnight in UTC
                long utcMillis = ISOChronology.getInstance().getZone().getMillisKeepLocal(UTC, localMillis);
                // convert to days
                return TimeUnit.MILLISECONDS.toDays(utcMillis);
            }
            if (type.equals(TimeType.TIME)) {
                Time time = resultSet.getTime(field + 1);
                return UTC_CHRONOLOGY.millisOfDay().get(time.getTime());
            }
            if (type.equals(TimestampType.TIMESTAMP)) {
                Timestamp timestamp = resultSet.getTimestamp(field + 1);
                return timestamp.getTime();
            }
            throw new PrestoException(GENERIC_INTERNAL_ERROR, "Unhandled type for long: " + type.getTypeSignature());
        }
        catch (SQLException | RuntimeException e) {
            throw handleSqlException(e);
        }
    }

    @Override
    public double getDouble(int field)
    {
        checkState(!closed, "cursor is closed");
        try {
            return resultSet.getDouble(field + 1);
        }
        catch (SQLException | RuntimeException e) {
            throw handleSqlException(e);
        }
    }

    @Override
    public Slice getSlice(int field)
    {
        checkState(!closed, "cursor is closed");
        try {
            Type type = getType(field);
            if (type instanceof VarcharType) {
                return utf8Slice(resultSet.getString(field + 1));
            }
            if (type instanceof CharType) {
                return utf8Slice(CharMatcher.is(' ').trimTrailingFrom(resultSet.getString(field + 1)));
            }
            if (type.equals(VarbinaryType.VARBINARY)) {
                return wrappedBuffer(resultSet.getBytes(field + 1));
            }
            if (type instanceof DecimalType) {
                return encodeScaledValue(resultSet.getBigDecimal(field + 1));
            }
            throw new PrestoException(GENERIC_INTERNAL_ERROR, "Unhandled type for slice: " + type.getTypeSignature());
        }
        catch (SQLException | RuntimeException e) {
            throw handleSqlException(e);
        }
    }

    @Override
    public Object getObject(int field)
    {
        Type type = getType(field);
        checkArgument(Types.isArrayType(type), "Expected field %s to be a type of array but is %s", field, type);

        if (Types.isArrayType(type)) {
            try {
                Object[] result = createArrayFromArrayObject(resultSet.getArray(field + 1).getArray());
                Type elementType = Types.getElementType(type);
                return getBlockFromArray(elementType, result);
            }
            catch (SQLException | RuntimeException e) {
                throw handleSqlException(e);
            }
        }

        throw new UnsupportedOperationException();
    }

    private Object[] createArrayFromArrayObject(Object o)
    {
        if (!o.getClass().isArray()) {
            return null;
        }

        if (!o.getClass().getComponentType().isPrimitive()) {
            return (Object[]) o;
        }

        int element_count = Array.getLength(o);
        Object elements[] = new Object[element_count];

        for (int i = 0; i < element_count; i++) {
            elements[i] = Array.get(o, i);
        }

        return elements;
    }

    private Block getBlockFromArray(Type elementType, Object[] array)
    {
        BlockBuilder builder = elementType.createBlockBuilder(new BlockBuilderStatus(), array.length);
        for (Object item : array) {
            writeObject(builder, elementType, item);
        }
        return builder.build();
    }

    private void writeObject(BlockBuilder builder, Type type, Object obj)
    {
        if (Types.isArrayType(type)) {
            BlockBuilder arrayBldr = builder.beginBlockEntry();
            Type elementType = Types.getElementType(type);
            for (Object item : (List<?>) obj) {
                writeObject(arrayBldr, elementType, item);
            }
            builder.closeEntry();
        }
        else if (Types.isMapType(type)) {
            BlockBuilder mapBlockBuilder = builder.beginBlockEntry();
            for (Entry<?, ?> entry : ((Map<?, ?>) obj).entrySet()) {
                writeObject(mapBlockBuilder, Types.getKeyType(type), entry.getKey());
                writeObject(mapBlockBuilder, Types.getValueType(type), entry.getValue());
            }
            builder.closeEntry();
        }
        else {
            TypeUtils.writeNativeValue(type, builder, obj);
        }
    }

    @Override
    public boolean isNull(int field)
    {
        checkState(!closed, "cursor is closed");
        checkArgument(field < columnHandles.size(), "Invalid field index");

        try {
            // JDBC is kind of dumb: we need to read the field and then ask
            // if it was null, which means we are wasting effort here.
            // We could save the result of the field access if it matters.
            resultSet.getObject(field + 1);

            return resultSet.wasNull();
        }
        catch (SQLException | RuntimeException e) {
            throw handleSqlException(e);
        }
    }

    @Override
    public void close()
    {
        if (closed) {
            return;
        }
        closed = true;

        // use try with resources to close everything properly
        try (Connection connection = this.connection;
                ResultSet resultSet = this.resultSet) {
            // do nothing
        }
        catch (SQLException e) {
            throw new RuntimeException(e);
        }
        nanoEnd = System.nanoTime();
    }

    private RuntimeException handleSqlException(Exception e)
    {
        try {
            close();
        }
        catch (Exception closeException) {
            // Self-suppression not permitted
            if (e != closeException) {
                e.addSuppressed(closeException);
            }
        }
        return new RuntimeException(e);
    }
}
