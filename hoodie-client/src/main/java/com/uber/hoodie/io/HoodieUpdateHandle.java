/*
 * Copyright (c) 2016 Uber Technologies, Inc. (hoodie-dev-group@uber.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.uber.hoodie.io;

import com.uber.hoodie.config.HoodieWriteConfig;
import com.uber.hoodie.WriteStatus;
import com.uber.hoodie.common.model.HoodieRecord;
import com.uber.hoodie.common.model.HoodieRecordLocation;
import com.uber.hoodie.common.model.HoodieRecordPayload;
import com.uber.hoodie.common.model.HoodieTableMetadata;
import com.uber.hoodie.common.model.HoodieWriteStat;
import com.uber.hoodie.common.util.FSUtils;
import com.uber.hoodie.exception.HoodieUpsertException;
import com.uber.hoodie.io.storage.HoodieStorageWriter;
import com.uber.hoodie.io.storage.HoodieStorageWriterFactory;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.IndexedRecord;
import org.apache.hadoop.fs.Path;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.spark.TaskContext;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;

@SuppressWarnings("Duplicates") public class HoodieUpdateHandle <T extends HoodieRecordPayload> extends HoodieIOHandle<T> {
    private static Logger logger = LogManager.getLogger(HoodieUpdateHandle.class);

    private final WriteStatus writeStatus;
    private final HashMap<String, HoodieRecord<T>> keyToNewRecords;
    private HoodieStorageWriter<IndexedRecord> storageWriter;
    private Path newFilePath;
    private Path oldFilePath;
    private long recordsWritten = 0;
    private long updatedRecordsWritten = 0;
    private String fileId;

    public HoodieUpdateHandle(HoodieWriteConfig config,
                              String commitTime,
                              HoodieTableMetadata metadata,
                              Iterator<HoodieRecord<T>> recordItr,
                              String fileId) {
        super(config, commitTime, metadata);
        WriteStatus writeStatus = new WriteStatus();
        writeStatus.setStat(new HoodieWriteStat());
        this.writeStatus = writeStatus;
        this.fileId = fileId;
        this.keyToNewRecords = new HashMap<>();
        init(recordItr);
    }

    /**
     * Load the new incoming records in a map, and extract the old file path.
     */
    private void init(Iterator<HoodieRecord<T>> newRecordsItr) {
        try {
            // Load the new records in a map
            while (newRecordsItr.hasNext()) {
                HoodieRecord<T> record = newRecordsItr.next();
                // If the first record, we need to extract some info out
                if (oldFilePath == null) {
                    String latestValidFilePath = metadata.getFilenameForRecord(fs, record, fileId);
                    writeStatus.getStat().setPrevCommit(FSUtils.getCommitTime(latestValidFilePath));
                    oldFilePath = new Path(
                        config.getBasePath() + "/" + record.getPartitionPath() + "/"
                            + latestValidFilePath);
                    newFilePath = new Path(
                        config.getBasePath() + "/" + record.getPartitionPath() + "/" + FSUtils
                            .makeDataFileName(commitTime, TaskContext.getPartitionId(), fileId));

                    // handle cases of partial failures, for update task
                    if (fs.exists(newFilePath)) {
                        fs.delete(newFilePath, false);
                    }

                    logger.info(String.format("Merging new data into oldPath %s, as newPath %s",
                        oldFilePath.toString(), newFilePath.toString()));
                    // file name is same for all records, in this bunch
                    writeStatus.setFileId(fileId);
                    writeStatus.setPartitionPath(record.getPartitionPath());
                    writeStatus.getStat().setFileId(fileId);
                    writeStatus.getStat().setFullPath(newFilePath.toString());
                }
                keyToNewRecords.put(record.getRecordKey(), record);
                // update the new location of the record, so we know where to find it next
                record.setNewLocation(new HoodieRecordLocation(commitTime, fileId));
            }
            // Create the writer for writing the new version file
            storageWriter = HoodieStorageWriterFactory
                .getStorageWriter(commitTime, newFilePath, metadata, config, schema);

        } catch (Exception e) {
            logger.error("Error in update task at commit " + commitTime, e);
            writeStatus.setGlobalError(e);
            throw new HoodieUpsertException(
                "Failed to initialize HoodieUpdateHandle for FileId: " + fileId + " on commit "
                    + commitTime + " on HDFS path " + metadata.getBasePath());
        }
    }


    private boolean writeUpdateRecord(HoodieRecord<T> hoodieRecord, IndexedRecord indexedRecord) {
        try {
            storageWriter.writeAvroWithMetadata(indexedRecord, hoodieRecord);
            hoodieRecord.deflate();
            writeStatus.markSuccess(hoodieRecord);
            recordsWritten ++;
            updatedRecordsWritten ++;
            return true;
        } catch (Exception e) {
            logger.error("Error writing record  "+ hoodieRecord, e);
            writeStatus.markFailure(hoodieRecord, e);
        }
        return false;
    }

    /**
     * Go through an old record. Here if we detect a newer version shows up, we write the new one to the file.
     */
    public void write(GenericRecord oldRecord) {
        String key = oldRecord.get(HoodieRecord.RECORD_KEY_METADATA_FIELD).toString();
        HoodieRecord<T> hoodieRecord = keyToNewRecords.get(key);
        boolean copyOldRecord = true;
        if (keyToNewRecords.containsKey(key)) {
            try {
                IndexedRecord avroRecord = hoodieRecord.getData().combineAndGetUpdateValue(oldRecord, schema);
                if (writeUpdateRecord(hoodieRecord, avroRecord)) {
                    /* ONLY WHEN
                     * 1) we have an update for this key AND
                     * 2) We are able to successfully write the the combined new value
                     *
                     * We no longer need to copy the old record over.
                     */
                    copyOldRecord = false;
                }
                keyToNewRecords.remove(key);
            } catch (Exception e) {
                throw new HoodieUpsertException("Failed to combine/merge new record with old value in storage, for new record {"
                        + keyToNewRecords.get(key) + "}, old value {" + oldRecord + "}", e);
            }
        }

        if (copyOldRecord) {
            // this should work as it is, since this is an existing record
            String errMsg = "Failed to merge old record into new file for key " + key + " from old file "
                + getOldFilePath() + " to new file " + newFilePath;
            try {
                storageWriter.writeAvro(key, oldRecord);
            } catch (ClassCastException e) {
                logger.error(
                    "Schema mismatch when rewriting old record " + oldRecord + " from file "
                        + getOldFilePath() + " to file " + newFilePath + " with schema " + schema
                        .toString(true));
                throw new HoodieUpsertException(errMsg, e);
            } catch (IOException e) {
                logger.error("Failed to merge old record into new file for key " + key + " from old file "
                    + getOldFilePath() + " to new file " + newFilePath, e);
                throw new HoodieUpsertException(errMsg, e);
            }
            recordsWritten ++;
        }
    }

    public void close() {
        try {
            // write out any pending records (this can happen when inserts are turned into updates)
            Iterator<String> pendingRecordsItr = keyToNewRecords.keySet().iterator();
            while (pendingRecordsItr.hasNext()) {
                String key = pendingRecordsItr.next();
                HoodieRecord<T> hoodieRecord = keyToNewRecords.get(key);
                writeUpdateRecord(hoodieRecord, hoodieRecord.getData().getInsertValue(schema));
            }
            keyToNewRecords.clear();

            if (storageWriter != null) {
                storageWriter.close();
            }
            writeStatus.getStat().setTotalWriteBytes(FSUtils.getFileSize(fs, newFilePath));
            writeStatus.getStat().setNumWrites(recordsWritten);
            writeStatus.getStat().setNumUpdateWrites(updatedRecordsWritten);
            writeStatus.getStat().setTotalWriteErrors(writeStatus.getFailedRecords().size());
        } catch (IOException e) {
            throw new HoodieUpsertException("Failed to close UpdateHandle", e);
        }
    }

    public Path getOldFilePath() {
        return oldFilePath;
    }

    public WriteStatus getWriteStatus() {
        return writeStatus;
    }
}
