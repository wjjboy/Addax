/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.wgzhao.addax.plugin.writer.influxdb2writer;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.wgzhao.addax.common.element.Column;
import com.wgzhao.addax.common.element.Record;
import com.wgzhao.addax.common.exception.AddaxException;
import com.wgzhao.addax.common.plugin.RecordReceiver;
import com.wgzhao.addax.common.spi.Writer;
import com.wgzhao.addax.common.util.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.wgzhao.addax.common.base.Constant.DEFAULT_BATCH_SIZE;
import static com.wgzhao.addax.common.base.Key.BATCH_SIZE;
import static com.wgzhao.addax.common.base.Key.COLUMN;
import static com.wgzhao.addax.common.base.Key.CONNECTION;
import static com.wgzhao.addax.common.base.Key.ENDPOINT;
import static com.wgzhao.addax.common.base.Key.TABLE;

public class InfluxDB2Writer
        extends Writer
{

    public static class Job
            extends Writer.Job
    {

        private Configuration originalConfig = null;

        @Override
        public void init()
        {
            this.originalConfig = super.getPluginJobConf();
            originalConfig.getNecessaryValue(InfluxDB2Key.TOKEN, InfluxDB2WriterErrorCode.REQUIRED_VALUE);
            Configuration connConf = Configuration.from(originalConfig.getList(CONNECTION, Object.class).get(0).toString());
            connConf.getNecessaryValue(InfluxDB2Key.ENDPOINT, InfluxDB2WriterErrorCode.REQUIRED_VALUE);
            connConf.getNecessaryValue(InfluxDB2Key.BUCKET, InfluxDB2WriterErrorCode.REQUIRED_VALUE);
            connConf.getNecessaryValue(InfluxDB2Key.ORG, InfluxDB2WriterErrorCode.REQUIRED_VALUE);
            connConf.getNecessaryValue(TABLE, InfluxDB2WriterErrorCode.REQUIRED_VALUE);
            List<String> columns = originalConfig.getList(COLUMN, String.class);
            if (columns == null || columns.isEmpty() || (columns.size() == 1 && "*".equals(columns.get(0)))) {
                throw AddaxException.asAddaxException(InfluxDB2WriterErrorCode.REQUIRED_VALUE,
                        "The column must be configured and '*' is not supported yet");
            }
        }

        @Override
        public void prepare()
        {

        }

        @Override
        public List<Configuration> split(int mandatoryNumber)
        {
            //修改多channel无效问题
            ArrayList<Configuration> configurations = new ArrayList<Configuration>(mandatoryNumber);
            for (int i = 0; i < mandatoryNumber; i++) {
                configurations.add(this.originalConfig.clone());
            }
            return configurations;
        }

        @Override
        public void post()
        {
            //
        }

        @Override
        public void destroy()
        {
            //
        }
    }

    public static class Task
            extends Writer.Task
    {
        private static final Logger LOG = LoggerFactory.getLogger(Task.class);
        private String endpoint;
        private String token;
        private String org;
        private String bucket;
        private String table;

        private List<String> columns;
        private List<Map> tags;
        private WritePrecision wp;
        private int batchSize;

        @Override
        public void init()
        {
            Configuration readerSliceConfig = super.getPluginJobConf();
            // get connection information
            Configuration connConf = Configuration.from(readerSliceConfig.getList(CONNECTION, Object.class).get(0).toString());
            this.endpoint = connConf.getString(ENDPOINT);
            this.org = connConf.getString(InfluxDB2Key.ORG);
            this.bucket = connConf.getString(InfluxDB2Key.BUCKET);
            this.table = connConf.getString(TABLE);

            this.token = readerSliceConfig.getString(InfluxDB2Key.TOKEN);
            this.columns = readerSliceConfig.getList(COLUMN, String.class);
            this.tags = readerSliceConfig.getList(InfluxDB2Key.TAG, Map.class);
            this.wp = WritePrecision.valueOf(readerSliceConfig.getString(InfluxDB2Key.INTERVAL, "ms").toUpperCase());
            this.batchSize = readerSliceConfig.getInt(BATCH_SIZE, DEFAULT_BATCH_SIZE);
        }

        @Override
        public void startWrite(RecordReceiver lineReceiver)
        {
            Record record;
            Column column;

            InfluxDBClient influxDBClient = InfluxDBClientFactory.create(endpoint, token.toCharArray(), org, bucket);
            WriteApi writeApi = influxDBClient.makeWriteApi();
            List<Point> points = new ArrayList<>(batchSize);

            while ((record = lineReceiver.getFromReader()) != null) {
                Point point = Point.measurement(table);
                if (!tags.isEmpty()) {
                    tags.forEach(i -> point.addTags(i));
                }

                // The first column must be timestamp
                column = record.getColumn(0);
                final Instant instant = column.asTimestamp().toInstant();
                point.time(processTimeUnit(instant), wp);
                for (int i = 0; i < columns.size(); i++) {
                    String name = columns.get(i);
                    String dest;
                    if (name.contains(":")) {
                        String[] str = name.split(":");
                        name = str[0];
                        dest = str[1].toUpperCase();
                    } else {
                        dest = "FIELD";
                    }

                    column = record.getColumn(i + 1); // the first field has processed above

                    if ("LIST".equals(dest)) {
                        switch (column.getType()) {
                            case STRING:
                                String str = column.asString();
                                JSONArray array = new JSONArray();
                                try {
                                    array = JSON.parseArray(str);
                                } catch (Exception e) {
                                    // 字符串转数组异常直接跳过
                                    LOG.error("list string to array error: {}", str);
                                }
                                for(int j = 0; j < array.size(); j++) {
                                    String o = array.getString(j);
                                    try {
                                        if(o.contains(".")) {
                                            // 小数
                                            point.addField(name + (j + 1), Double.valueOf(o));
                                        } else {
                                            // 整数
                                            point.addField(name + (j + 1), Long.valueOf(o));
                                        }
                                    } catch (NumberFormatException e) {
                                        // 转换异常直接跳过
                                        LOG.error("number format error: {}", str);
                                    }

                                }
                                break;
                            default:
                                point.addField(name, column.asString());
                                break;
                        }
                    } else if ("TAG".equals(dest)) {
                        switch (column.getType()) {
                            case LONG:
                                point.addTag(name, String.valueOf(column.asLong()));
                                break;
                            case DOUBLE:
                                point.addTag(name, String.valueOf(column.asDouble()));
                                break;
                            case BOOL:
                                point.addTag(name, String.valueOf(column.asBoolean()));
                                break;
                            case DATE:
                            default:
                                point.addTag(name, column.asString());
                                break;
                        }
                    } else {
                        switch (column.getType()) {
                            case LONG:
                                point.addField(name, column.asLong());
                                break;
                            case DOUBLE:
                                point.addField(name, column.asDouble());
                                break;
                            case BOOL:
                                point.addField(name, column.asBoolean());
                                break;
                            case DATE:
                            default:
                                point.addField(name, column.asString());
                                break;
                        }
                    }
                }
                points.add(point);
                if (points.size() == batchSize) {
                    writeApi.writePoints(points);
                    points.clear();
                }
            }
            // write remain points if present
            if (!points.isEmpty()) {
                writeApi.writePoints(points);
            }
            influxDBClient.close();
        }

        private long processTimeUnit(Instant instant)
        {
            long ts = instant.toEpochMilli();
            switch (wp) {
                case MS:
                    ts = instant.toEpochMilli();
                    break;
                case S:
                    ts = instant.getEpochSecond();
                    break;
                case US:
                    ts = instant.getNano() / 1000;
                    break;
                case NS:
                    ts = instant.getNano();
                    break;
            }
            return ts;
        }
        @Override
        public void post()
        {
            //
        }

        @Override
        public void destroy()
        {
            //
        }
    }

}
