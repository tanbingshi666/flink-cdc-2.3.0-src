/*
 * Copyright 2022 Ververica Inc.
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

package com.ververica.cdc.connectors.mysql.source.reader;

import org.apache.flink.connector.base.source.reader.RecordsWithSplitIds;
import org.apache.flink.connector.base.source.reader.splitreader.SplitReader;
import org.apache.flink.connector.base.source.reader.splitreader.SplitsAddition;
import org.apache.flink.connector.base.source.reader.splitreader.SplitsChange;

import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.ververica.cdc.connectors.mysql.debezium.reader.BinlogSplitReader;
import com.ververica.cdc.connectors.mysql.debezium.reader.DebeziumReader;
import com.ververica.cdc.connectors.mysql.debezium.reader.SnapshotSplitReader;
import com.ververica.cdc.connectors.mysql.debezium.task.context.StatefulTaskContext;
import com.ververica.cdc.connectors.mysql.source.MySqlSource;
import com.ververica.cdc.connectors.mysql.source.config.MySqlSourceConfig;
import com.ververica.cdc.connectors.mysql.source.split.MySqlRecords;
import com.ververica.cdc.connectors.mysql.source.split.MySqlSplit;
import com.ververica.cdc.connectors.mysql.source.split.SourceRecords;
import io.debezium.connector.mysql.MySqlConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Queue;

import static com.ververica.cdc.connectors.mysql.debezium.DebeziumUtils.createBinaryClient;
import static com.ververica.cdc.connectors.mysql.debezium.DebeziumUtils.createMySqlConnection;

/**
 * The {@link SplitReader} implementation for the {@link MySqlSource}.
 */
public class MySqlSplitReader implements SplitReader<SourceRecords, MySqlSplit> {

    private static final Logger LOG = LoggerFactory.getLogger(MySqlSplitReader.class);
    private final Queue<MySqlSplit> splits;
    private final MySqlSourceConfig sourceConfig;
    private final int subtaskId;
    private final MySqlSourceReaderContext context;

    @Nullable
    private DebeziumReader<SourceRecords, MySqlSplit> currentReader;
    @Nullable
    private String currentSplitId;

    public MySqlSplitReader(
            MySqlSourceConfig sourceConfig, int subtaskId, MySqlSourceReaderContext context) {
        // mysql 配置信息
        this.sourceConfig = sourceConfig;
        // mysql SourceReader subtask id
        this.subtaskId = subtaskId;
        // 切片缓存容器
        this.splits = new ArrayDeque<>();
        this.context = context;
    }

    @Override
    public RecordsWithSplitIds<SourceRecords> fetch() throws IOException {

        /**
         * 创建 currentReader 等于 SnapshotSplitReader 或者 BinlogSplitReader
         * currentReader 根据切片信息异步执行数据拉取
         */
        checkSplitOrStartNext();

        checkNeedStopBinlogReader();

        Iterator<SourceRecords> dataIt;
        try {
            /**
             * 从 currentReader 拉取数据
             */
            dataIt = currentReader.pollSplitRecords();
        } catch (InterruptedException e) {
            LOG.warn("fetch data failed.", e);
            throw new IOException(e);
        }
        /**
         * 拉取数据封装并返回
         */
        return dataIt == null
                ? finishedSnapshotSplit()
                : MySqlRecords.forRecords(currentSplitId, dataIt);
    }

    private void checkNeedStopBinlogReader() {
        if (currentReader instanceof BinlogSplitReader
                && context.needStopBinlogSplitReader()
                && !currentReader.isFinished()) {
            ((BinlogSplitReader) currentReader).stopBinlogReadTask();
        }
    }

    @Override
    public void handleSplitsChanges(SplitsChange<MySqlSplit> splitsChanges) {
        if (!(splitsChanges instanceof SplitsAddition)) {
            throw new UnsupportedOperationException(
                    String.format(
                            "The SplitChange type of %s is not supported.",
                            splitsChanges.getClass()));
        }

        LOG.debug("Handling split change {}", splitsChanges);
        // 添加当前 MysqlSplitReader 读取哪些切片信息
        splits.addAll(splitsChanges.splits());
    }

    @Override
    public void wakeUp() {
    }

    @Override
    public void close() throws Exception {
        if (currentReader != null) {
            LOG.info(
                    "Close current debezium reader {}",
                    currentReader.getClass().getCanonicalName());
            currentReader.close();
            currentSplitId = null;
        }
    }

    private void checkSplitOrStartNext() throws IOException {
        /**
         * 判断当前 currentReader 是否为 null 或者处于 finish
         */
        if (canAssignNextSplit()) {

            /**
             * 获取一个切片信息
             */
            MySqlSplit nextSplit = splits.poll();
            if (nextSplit == null) {
                return;
            }

            /**
             * 获取切片信息 ID
             */
            currentSplitId = nextSplit.splitId();

            /**
             * 判断切片是否为 MySqlSnapshotSplit 或者 MySqlSchemalessSnapshotSplit 类型
             */
            if (nextSplit.isSnapshotSplit()) {
                if (currentReader instanceof BinlogSplitReader) {
                    LOG.info(
                            "This is the point from binlog split reading change to snapshot split reading");
                    currentReader.close();
                    currentReader = null;
                }

                /**
                 * 创建 SnapshotSplitReader 读取 mysql-snapshot 数据
                 */
                if (currentReader == null) {
                    /**
                     * 创建 MySqlConnection 但是没有连接
                     */
                    final MySqlConnection jdbcConnection = createMySqlConnection(sourceConfig);
                    /**
                     * 创建 BinaryLogClient 消费 mysql-binlog
                     */
                    final BinaryLogClient binaryLogClient =
                            createBinaryClient(sourceConfig.getDbzConfiguration());

                    /**
                     * 创建 StatefulTaskContext 以便状态恢复
                     */
                    final StatefulTaskContext statefulTaskContext =
                            new StatefulTaskContext(sourceConfig, binaryLogClient, jdbcConnection);

                    /**
                     * 创建 SnapshotSplitReader 读取 mysql-snapshot 数据
                     */
                    currentReader = new SnapshotSplitReader(statefulTaskContext, subtaskId);
                }
            } else {
                // point from snapshot split to binlog split
                if (currentReader != null) {
                    LOG.info("It's turn to read binlog split, close current snapshot reader");
                    currentReader.close();
                }
                final MySqlConnection jdbcConnection = createMySqlConnection(sourceConfig);
                final BinaryLogClient binaryLogClient =
                        createBinaryClient(sourceConfig.getDbzConfiguration());
                final StatefulTaskContext statefulTaskContext =
                        new StatefulTaskContext(sourceConfig, binaryLogClient, jdbcConnection);
                /**
                 * 创建 BinlogSplitReader 读取 mysql-binlog 数据
                 */
                currentReader = new BinlogSplitReader(statefulTaskContext, subtaskId);
                LOG.info("BinlogSplitReader is created.");
            }

            /**
             * 提交切片信息到 SnapshotSplitReader 或者 BinlogSplitReader
             */
            currentReader.submitSplit(nextSplit);
        }
    }

    private boolean canAssignNextSplit() {
        return currentReader == null || currentReader.isFinished();
    }

    private MySqlRecords finishedSnapshotSplit() {
        final MySqlRecords finishedRecords = MySqlRecords.forFinishedSplit(currentSplitId);
        currentSplitId = null;
        return finishedRecords;
    }
}
