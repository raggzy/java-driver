/*
 *      Copyright (C) 2012-2015 DataStax Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.datastax.driver.extras.codecs.joda;

import com.datastax.driver.core.*;
import com.datastax.driver.core.exceptions.InvalidTypeException;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.DateTimeFormatterBuilder;
import org.joda.time.format.ISODateTimeFormat;

import java.nio.ByteBuffer;
import java.util.List;

import static com.datastax.driver.core.ParseUtils.isLongLiteral;
import static com.datastax.driver.core.ParseUtils.quote;
import static com.google.common.base.Preconditions.checkArgument;
import static org.joda.time.DateTimeZone.UTC;

/**
 * {@link TypeCodec} that maps
 * {@link DateTime} to CQL {@code tuple<timestamp,varchar>},
 * providing a pattern for maintaining timezone information in
 * Cassandra.
 * <p/>
 * Since Cassandra's <code>timestamp</code> type preserves only
 * milliseconds since epoch, any timezone information
 * would normally be lost. By using a
 * <code>tuple&lt;timestamp,varchar&gt;</code> a timezone ID can be
 * persisted in the <code>varchar</code> field such that when the
 * value is deserialized the timezone is
 * preserved.
 * <p/>
 * <p>
 * This codec produce CQL literals of the following form:
 * <ol>
 * <li>Timestamp component: an ISO-8601 full date and time pattern, including year,
 * month, day, hour, minutes and milliseconds (see below), followed by the zone ID {@code Z} (UTC),
 * e.g. {@code 2010-06-30T01:20:47.999Z};
 * note that timestamp components are always expressed in UTC time, hence the zone ID {@code Z}.</li>
 * <li>Zone component: a zone offset such as {@code -07:00}, or a zone ID such as {@code UTC} or {@code Europe/Paris},
 * depending on what information is available.</li>
 * </ol>
 * <p>
 * <strong>IMPORTANT</strong>: this codec's {@link #format(Object) format} method formats
 * timestamps as CQL literal strings using an ISO-8601 format that includes milliseconds.
 * <strong>This format is incompatible with Cassandra versions < 2.0.9.</strong>
 *
 * @see <a href="https://cassandra.apache.org/doc/cql3/CQL-2.2.html#usingtimestamps">'Working with timestamps' section of CQL specification</a>
 */
public class DateTimeCodec extends TypeCodec.AbstractTupleCodec<DateTime> {

    /**
     * A {@link DateTimeFormatter} that parses (most) of
     * the ISO formats accepted in CQL.
     */
    private static final DateTimeFormatter PARSER = new DateTimeFormatterBuilder()
            .append(ISODateTimeFormat.dateOptionalTimeParser().getParser())
            .appendOptional(
                    new DateTimeFormatterBuilder()
                            .appendTimeZoneOffset("Z", true, 2, 4)
                            .toParser())
            .toFormatter()
            .withZoneUTC();

    /**
     * Creates a new {@link DateTimeCodec} for the given tuple.
     *
     * @param tupleType The tuple type this codec should handle.
     *                  It must be a {@code tuple<timestamp,varchar>}.
     * @throws IllegalArgumentException if the provided tuple type is not a {@code tuple<timestamp,varchar>}.
     */
    public DateTimeCodec(TupleType tupleType) {
        super(tupleType, DateTime.class);
        List<DataType> types = tupleType.getComponentTypes();
        checkArgument(
                types.size() == 2 && types.get(0).equals(DataType.timestamp()) && types.get(1).equals(DataType.varchar()),
                "Expected tuple<timestamp,varchar>, got %s",
                tupleType);
    }

    @Override
    protected DateTime newInstance() {
        return null;
    }

    @Override
    protected ByteBuffer serializeField(DateTime source, int index, ProtocolVersion protocolVersion) {
        if (index == 0) {
            long millis = source.getMillis();
            return bigint().serializeNoBoxing(millis, protocolVersion);
        }
        if (index == 1) {
            return varchar().serialize(source.getZone().getID(), protocolVersion);
        }
        throw new IndexOutOfBoundsException("Tuple index out of bounds. " + index);
    }

    @Override
    protected DateTime deserializeAndSetField(ByteBuffer input, DateTime target, int index, ProtocolVersion protocolVersion) {
        if (index == 0) {
            long millis = bigint().deserializeNoBoxing(input, protocolVersion);
            return new DateTime(millis);
        }
        if (index == 1) {
            String zoneId = varchar().deserialize(input, protocolVersion);
            return target.withZone(DateTimeZone.forID(zoneId));
        }
        throw new IndexOutOfBoundsException("Tuple index out of bounds. " + index);
    }

    @Override
    protected String formatField(DateTime value, int index) {
        if (index == 0) {
            return quote(value.withZone(UTC).toString());
        }
        if (index == 1) {
            return quote(value.getZone().getID());
        }
        throw new IndexOutOfBoundsException("Tuple index out of bounds. " + index);
    }

    @Override
    protected DateTime parseAndSetField(String input, DateTime target, int index) {
        if (index == 0) {
            // strip enclosing single quotes, if any
            if (ParseUtils.isQuoted(input))
                input = ParseUtils.unquote(input);
            if (isLongLiteral(input)) {
                try {
                    long millis = Long.parseLong(input);
                    return new DateTime(millis);
                } catch (NumberFormatException e) {
                    throw new InvalidTypeException(String.format("Cannot parse timestamp value from \"%s\"", input));
                }
            }
            try {
                return PARSER.parseDateTime(input);
            } catch (RuntimeException e) {
                throw new InvalidTypeException(String.format("Cannot parse timestamp value from \"%s\"", target));
            }
        }
        if (index == 1) {
            String zoneId = varchar().parse(input);
            if ("Z".equals(zoneId))
                return target.withZone(DateTimeZone.UTC);
            return target.withZone(DateTimeZone.forID(zoneId));
        }
        throw new IndexOutOfBoundsException("Tuple index out of bounds. " + index);
    }

}
