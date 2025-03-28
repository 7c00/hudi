/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.metadata;

import org.apache.hudi.avro.model.HoodieCleanMetadata;
import org.apache.hudi.avro.model.HoodieMetadataColumnStats;
import org.apache.hudi.avro.model.HoodieRestoreMetadata;
import org.apache.hudi.avro.model.HoodieRollbackMetadata;
import org.apache.hudi.common.bloom.BloomFilter;
import org.apache.hudi.common.config.HoodieMetadataConfig;
import org.apache.hudi.common.data.HoodieData;
import org.apache.hudi.common.engine.HoodieEngineContext;
import org.apache.hudi.common.fs.FSUtils;
import org.apache.hudi.common.model.FileSlice;
import org.apache.hudi.common.model.HoodieColumnRangeMetadata;
import org.apache.hudi.common.model.HoodieCommitMetadata;
import org.apache.hudi.common.model.HoodieDeltaWriteStat;
import org.apache.hudi.common.model.HoodieFileFormat;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.model.HoodieWriteStat;
import org.apache.hudi.common.table.HoodieTableConfig;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.common.table.TableSchemaResolver;
import org.apache.hudi.common.table.timeline.HoodieActiveTimeline;
import org.apache.hudi.common.table.timeline.HoodieDefaultTimeline;
import org.apache.hudi.common.table.timeline.HoodieInstant;
import org.apache.hudi.common.table.timeline.HoodieTimeline;
import org.apache.hudi.common.table.view.HoodieTableFileSystemView;
import org.apache.hudi.common.util.CollectionUtils;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.ParquetUtils;
import org.apache.hudi.common.util.StringUtils;
import org.apache.hudi.common.util.ValidationUtils;
import org.apache.hudi.common.util.collection.Pair;
import org.apache.hudi.exception.HoodieException;
import org.apache.hudi.exception.HoodieIOException;
import org.apache.hudi.exception.HoodieMetadataException;
import org.apache.hudi.io.storage.HoodieFileReader;
import org.apache.hudi.io.storage.HoodieFileReaderFactory;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.generic.IndexedRecord;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import javax.annotation.Nonnull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.hudi.avro.HoodieAvroUtils.addMetadataFields;
import static org.apache.hudi.avro.HoodieAvroUtils.getNestedFieldValAsString;
import static org.apache.hudi.common.model.HoodieColumnRangeMetadata.COLUMN_RANGE_MERGE_FUNCTION;
import static org.apache.hudi.common.model.HoodieColumnRangeMetadata.Stats.MAX;
import static org.apache.hudi.common.model.HoodieColumnRangeMetadata.Stats.MIN;
import static org.apache.hudi.common.model.HoodieColumnRangeMetadata.Stats.NULL_COUNT;
import static org.apache.hudi.common.model.HoodieColumnRangeMetadata.Stats.TOTAL_SIZE;
import static org.apache.hudi.common.model.HoodieColumnRangeMetadata.Stats.TOTAL_UNCOMPRESSED_SIZE;
import static org.apache.hudi.common.model.HoodieColumnRangeMetadata.Stats.VALUE_COUNT;
import static org.apache.hudi.common.util.StringUtils.isNullOrEmpty;
import static org.apache.hudi.metadata.HoodieTableMetadata.EMPTY_PARTITION_NAME;
import static org.apache.hudi.metadata.HoodieTableMetadata.NON_PARTITIONED_NAME;

/**
 * A utility to convert timeline information to metadata table records.
 */
public class HoodieTableMetadataUtil {

  private static final Logger LOG = LogManager.getLogger(HoodieTableMetadataUtil.class);

  protected static final String PARTITION_NAME_FILES = "files";
  protected static final String PARTITION_NAME_COLUMN_STATS = "column_stats";
  protected static final String PARTITION_NAME_BLOOM_FILTERS = "bloom_filters";

  /**
   * Delete the metadata table for the dataset. This will be invoked during upgrade/downgrade operation during which
   * no other
   * process should be running.
   *
   * @param basePath base path of the dataset
   * @param context  instance of {@link HoodieEngineContext}.
   */
  public static void deleteMetadataTable(String basePath, HoodieEngineContext context) {
    final String metadataTablePath = HoodieTableMetadata.getMetadataTableBasePath(basePath);
    FileSystem fs = FSUtils.getFs(metadataTablePath, context.getHadoopConf().get());
    try {
      fs.delete(new Path(metadataTablePath), true);
    } catch (Exception e) {
      throw new HoodieMetadataException("Failed to remove metadata table from path " + metadataTablePath, e);
    }
  }

  /**
   * Convert commit action to metadata records for the enabled partition types.
   *
   * @param commitMetadata          - Commit action metadata
   * @param instantTime             - Action instant time
   * @param recordsGenerationParams - Parameters for the record generation
   * @return Map of partition to metadata records for the commit action
   */
  public static Map<MetadataPartitionType, HoodieData<HoodieRecord>> convertMetadataToRecords(
      HoodieEngineContext context, HoodieCommitMetadata commitMetadata, String instantTime,
      MetadataRecordsGenerationParams recordsGenerationParams) {
    final Map<MetadataPartitionType, HoodieData<HoodieRecord>> partitionToRecordsMap = new HashMap<>();
    final HoodieData<HoodieRecord> filesPartitionRecordsRDD = context.parallelize(
        convertMetadataToFilesPartitionRecords(commitMetadata, instantTime), 1);
    partitionToRecordsMap.put(MetadataPartitionType.FILES, filesPartitionRecordsRDD);

    if (recordsGenerationParams.getEnabledPartitionTypes().contains(MetadataPartitionType.BLOOM_FILTERS)) {
      final HoodieData<HoodieRecord> metadataBloomFilterRecords = convertMetadataToBloomFilterRecords(context, commitMetadata, instantTime, recordsGenerationParams);
      partitionToRecordsMap.put(MetadataPartitionType.BLOOM_FILTERS, metadataBloomFilterRecords);
    }

    if (recordsGenerationParams.getEnabledPartitionTypes().contains(MetadataPartitionType.COLUMN_STATS)) {
      final HoodieData<HoodieRecord> metadataColumnStatsRDD = convertMetadataToColumnStatsRecords(commitMetadata, context, recordsGenerationParams);
      partitionToRecordsMap.put(MetadataPartitionType.COLUMN_STATS, metadataColumnStatsRDD);
    }
    return partitionToRecordsMap;
  }

  /**
   * Finds all new files/partitions created as part of commit and creates metadata table records for them.
   *
   * @param commitMetadata - Commit action metadata
   * @param instantTime    - Commit action instant time
   * @return List of metadata table records
   */
  public static List<HoodieRecord> convertMetadataToFilesPartitionRecords(HoodieCommitMetadata commitMetadata,
                                                                          String instantTime) {
    List<HoodieRecord> records = new ArrayList<>(commitMetadata.getPartitionToWriteStats().size());

    // Add record bearing partitions list
    ArrayList<String> partitionsList = new ArrayList<>(commitMetadata.getPartitionToWriteStats().keySet());

    records.add(HoodieMetadataPayload.createPartitionListRecord(partitionsList));

    // Update files listing records for each individual partition
    List<HoodieRecord<HoodieMetadataPayload>> updatedPartitionFilesRecords =
        commitMetadata.getPartitionToWriteStats().entrySet()
            .stream()
            .map(entry -> {
              String partitionStatName = entry.getKey();
              List<HoodieWriteStat> writeStats = entry.getValue();

              String partition = getPartition(partitionStatName);

              HashMap<String, Long> updatedFilesToSizesMapping =
                  writeStats.stream().reduce(new HashMap<>(writeStats.size()),
                      (map, stat) -> {
                        String pathWithPartition = stat.getPath();
                        if (pathWithPartition == null) {
                          // Empty partition
                          LOG.warn("Unable to find path in write stat to update metadata table " + stat);
                          return map;
                        }

                        int offset = partition.equals(NON_PARTITIONED_NAME)
                            ? (pathWithPartition.startsWith("/") ? 1 : 0)
                            : partition.length() + 1;
                        String filename = pathWithPartition.substring(offset);

                        // Since write-stats are coming in no particular order, if the same
                        // file have previously been appended to w/in the txn, we simply pick max
                        // of the sizes as reported after every write, since file-sizes are
                        // monotonically increasing (ie file-size never goes down, unless deleted)
                        map.merge(filename, stat.getFileSizeInBytes(), Math::max);

                        return map;
                      },
                      CollectionUtils::combine);

              return HoodieMetadataPayload.createPartitionFilesRecord(partition, Option.of(updatedFilesToSizesMapping),
                  Option.empty());
            })
            .collect(Collectors.toList());

    records.addAll(updatedPartitionFilesRecords);

    LOG.info("Updating at " + instantTime + " from Commit/" + commitMetadata.getOperationType()
        + ". #partitions_updated=" + records.size());

    return records;
  }

  /**
   * Convert commit action metadata to bloom filter records.
   *
   * @param context                 - Engine context to use
   * @param commitMetadata          - Commit action metadata
   * @param instantTime             - Action instant time
   * @param recordsGenerationParams - Parameters for bloom filter record generation
   * @return HoodieData of metadata table records
   */
  public static HoodieData<HoodieRecord> convertMetadataToBloomFilterRecords(
      HoodieEngineContext context, HoodieCommitMetadata commitMetadata,
      String instantTime, MetadataRecordsGenerationParams recordsGenerationParams) {
    final List<HoodieWriteStat> allWriteStats = commitMetadata.getPartitionToWriteStats().values().stream()
        .flatMap(entry -> entry.stream()).collect(Collectors.toList());
    if (allWriteStats.isEmpty()) {
      return context.emptyHoodieData();
    }

    final int parallelism = Math.max(Math.min(allWriteStats.size(), recordsGenerationParams.getBloomIndexParallelism()), 1);
    HoodieData<HoodieWriteStat> allWriteStatsRDD = context.parallelize(allWriteStats, parallelism);
    return allWriteStatsRDD.flatMap(hoodieWriteStat -> {
      final String partition = hoodieWriteStat.getPartitionPath();

      // For bloom filter index, delta writes do not change the base file bloom filter entries
      if (hoodieWriteStat instanceof HoodieDeltaWriteStat) {
        return Collections.emptyListIterator();
      }

      String pathWithPartition = hoodieWriteStat.getPath();
      if (pathWithPartition == null) {
        // Empty partition
        LOG.error("Failed to find path in write stat to update metadata table " + hoodieWriteStat);
        return Collections.emptyListIterator();
      }
      int offset = partition.equals(NON_PARTITIONED_NAME) ? (pathWithPartition.startsWith("/") ? 1 : 0) :
          partition.length() + 1;

      final String fileName = pathWithPartition.substring(offset);
      if (!FSUtils.isBaseFile(new Path(fileName))) {
        return Collections.emptyListIterator();
      }

      final Path writeFilePath = new Path(recordsGenerationParams.getDataMetaClient().getBasePath(), pathWithPartition);
      try (HoodieFileReader<IndexedRecord> fileReader =
               HoodieFileReaderFactory.getFileReader(recordsGenerationParams.getDataMetaClient().getHadoopConf(), writeFilePath)) {
        try {
          final BloomFilter fileBloomFilter = fileReader.readBloomFilter();
          if (fileBloomFilter == null) {
            LOG.error("Failed to read bloom filter for " + writeFilePath);
            return Collections.emptyListIterator();
          }
          ByteBuffer bloomByteBuffer = ByteBuffer.wrap(fileBloomFilter.serializeToString().getBytes());
          HoodieRecord record = HoodieMetadataPayload.createBloomFilterMetadataRecord(
              partition, fileName, instantTime, recordsGenerationParams.getBloomFilterType(), bloomByteBuffer, false);
          return Collections.singletonList(record).iterator();
        } catch (Exception e) {
          LOG.error("Failed to read bloom filter for " + writeFilePath);
          return Collections.emptyListIterator();
        } finally {
          fileReader.close();
        }
      } catch (IOException e) {
        LOG.error("Failed to get bloom filter for file: " + writeFilePath + ", write stat: " + hoodieWriteStat);
      }
      return Collections.emptyListIterator();
    });
  }

  /**
   * Convert the clean action to metadata records.
   */
  public static Map<MetadataPartitionType, HoodieData<HoodieRecord>> convertMetadataToRecords(
      HoodieEngineContext engineContext, HoodieCleanMetadata cleanMetadata,
      MetadataRecordsGenerationParams recordsGenerationParams, String instantTime) {
    final Map<MetadataPartitionType, HoodieData<HoodieRecord>> partitionToRecordsMap = new HashMap<>();
    final HoodieData<HoodieRecord> filesPartitionRecordsRDD = engineContext.parallelize(
        convertMetadataToFilesPartitionRecords(cleanMetadata, instantTime), 1);
    partitionToRecordsMap.put(MetadataPartitionType.FILES, filesPartitionRecordsRDD);

    if (recordsGenerationParams.getEnabledPartitionTypes().contains(MetadataPartitionType.BLOOM_FILTERS)) {
      final HoodieData<HoodieRecord> metadataBloomFilterRecordsRDD = convertMetadataToBloomFilterRecords(cleanMetadata, engineContext, instantTime, recordsGenerationParams);
      partitionToRecordsMap.put(MetadataPartitionType.BLOOM_FILTERS, metadataBloomFilterRecordsRDD);
    }

    if (recordsGenerationParams.getEnabledPartitionTypes().contains(MetadataPartitionType.COLUMN_STATS)) {
      final HoodieData<HoodieRecord> metadataColumnStatsRDD = convertMetadataToColumnStatsRecords(cleanMetadata, engineContext, recordsGenerationParams);
      partitionToRecordsMap.put(MetadataPartitionType.COLUMN_STATS, metadataColumnStatsRDD);
    }

    return partitionToRecordsMap;
  }

  /**
   * Finds all files that were deleted as part of a clean and creates metadata table records for them.
   *
   * @param cleanMetadata
   * @param instantTime
   * @return a list of metadata table records
   */
  public static List<HoodieRecord> convertMetadataToFilesPartitionRecords(HoodieCleanMetadata cleanMetadata,
                                                                          String instantTime) {
    List<HoodieRecord> records = new LinkedList<>();
    int[] fileDeleteCount = {0};
    cleanMetadata.getPartitionMetadata().forEach((partitionName, partitionMetadata) -> {
      final String partition = getPartition(partitionName);
      // Files deleted from a partition
      List<String> deletedFiles = partitionMetadata.getDeletePathPatterns();
      HoodieRecord record = HoodieMetadataPayload.createPartitionFilesRecord(partition, Option.empty(),
          Option.of(new ArrayList<>(deletedFiles)));

      records.add(record);
      fileDeleteCount[0] += deletedFiles.size();
    });

    LOG.info("Updating at " + instantTime + " from Clean. #partitions_updated=" + records.size()
        + ", #files_deleted=" + fileDeleteCount[0]);
    return records;
  }

  /**
   * Convert clean metadata to bloom filter index records.
   *
   * @param cleanMetadata           - Clean action metadata
   * @param engineContext           - Engine context
   * @param instantTime             - Clean action instant time
   * @param recordsGenerationParams - Parameters for bloom filter record generation
   * @return List of bloom filter index records for the clean metadata
   */
  public static HoodieData<HoodieRecord> convertMetadataToBloomFilterRecords(HoodieCleanMetadata cleanMetadata,
                                                                             HoodieEngineContext engineContext,
                                                                             String instantTime,
                                                                             MetadataRecordsGenerationParams recordsGenerationParams) {
    List<Pair<String, String>> deleteFileList = new ArrayList<>();
    cleanMetadata.getPartitionMetadata().forEach((partition, partitionMetadata) -> {
      // Files deleted from a partition
      List<String> deletedFiles = partitionMetadata.getDeletePathPatterns();
      deletedFiles.forEach(entry -> {
        final Path deletedFilePath = new Path(entry);
        if (FSUtils.isBaseFile(deletedFilePath)) {
          deleteFileList.add(Pair.of(partition, deletedFilePath.getName()));
        }
      });
    });

    final int parallelism = Math.max(Math.min(deleteFileList.size(), recordsGenerationParams.getBloomIndexParallelism()), 1);
    HoodieData<Pair<String, String>> deleteFileListRDD = engineContext.parallelize(deleteFileList, parallelism);
    return deleteFileListRDD.map(deleteFileInfoPair -> HoodieMetadataPayload.createBloomFilterMetadataRecord(
        deleteFileInfoPair.getLeft(), deleteFileInfoPair.getRight(), instantTime, StringUtils.EMPTY_STRING,
        ByteBuffer.allocate(0), true));
  }

  /**
   * Convert clean metadata to column stats index records.
   *
   * @param cleanMetadata           - Clean action metadata
   * @param engineContext           - Engine context
   * @param recordsGenerationParams - Parameters for bloom filter record generation
   * @return List of column stats index records for the clean metadata
   */
  public static HoodieData<HoodieRecord> convertMetadataToColumnStatsRecords(HoodieCleanMetadata cleanMetadata,
                                                                             HoodieEngineContext engineContext,
                                                                             MetadataRecordsGenerationParams recordsGenerationParams) {
    List<Pair<String, String>> deleteFileList = new ArrayList<>();
    cleanMetadata.getPartitionMetadata().forEach((partition, partitionMetadata) -> {
      // Files deleted from a partition
      List<String> deletedFiles = partitionMetadata.getDeletePathPatterns();
      deletedFiles.forEach(entry -> deleteFileList.add(Pair.of(partition, entry)));
    });

    HoodieTableMetaClient dataTableMetaClient = recordsGenerationParams.getDataMetaClient();

    List<String> columnsToIndex = getColumnsToIndex(recordsGenerationParams,
        dataTableMetaClient.getTableConfig(), tryResolveSchemaForTable(dataTableMetaClient));

    if (columnsToIndex.isEmpty()) {
      // In case there are no columns to index, bail
      return engineContext.emptyHoodieData();
    }

    int parallelism = Math.max(Math.min(deleteFileList.size(), recordsGenerationParams.getColumnStatsIndexParallelism()), 1);
    return engineContext.parallelize(deleteFileList, parallelism)
        .flatMap(deleteFileInfoPair -> {
          if (deleteFileInfoPair.getRight().endsWith(HoodieFileFormat.PARQUET.getFileExtension())) {
            return getColumnStats(deleteFileInfoPair.getLeft(), deleteFileInfoPair.getRight(), dataTableMetaClient, columnsToIndex, true).iterator();
          }
          return Collections.emptyListIterator();
        });
  }

  /**
   * Convert restore action metadata to metadata table records.
   */
  public static Map<MetadataPartitionType, HoodieData<HoodieRecord>> convertMetadataToRecords(
      HoodieEngineContext engineContext, HoodieActiveTimeline metadataTableTimeline, HoodieRestoreMetadata restoreMetadata,
      MetadataRecordsGenerationParams recordsGenerationParams, String instantTime, Option<String> lastSyncTs) {
    final Map<MetadataPartitionType, HoodieData<HoodieRecord>> partitionToRecordsMap = new HashMap<>();
    final Map<String, Map<String, Long>> partitionToAppendedFiles = new HashMap<>();
    final Map<String, List<String>> partitionToDeletedFiles = new HashMap<>();

    processRestoreMetadata(metadataTableTimeline, restoreMetadata, partitionToAppendedFiles, partitionToDeletedFiles, lastSyncTs);
    final HoodieData<HoodieRecord> filesPartitionRecordsRDD =
        engineContext.parallelize(convertFilesToFilesPartitionRecords(partitionToDeletedFiles, partitionToAppendedFiles, instantTime, "Restore"), 1);
    partitionToRecordsMap.put(MetadataPartitionType.FILES, filesPartitionRecordsRDD);

    if (recordsGenerationParams.getEnabledPartitionTypes().contains(MetadataPartitionType.BLOOM_FILTERS)) {
      final HoodieData<HoodieRecord> metadataBloomFilterRecordsRDD =
          convertFilesToBloomFilterRecords(engineContext, partitionToDeletedFiles, partitionToAppendedFiles, recordsGenerationParams, instantTime);
      partitionToRecordsMap.put(MetadataPartitionType.BLOOM_FILTERS, metadataBloomFilterRecordsRDD);
    }

    if (recordsGenerationParams.getEnabledPartitionTypes().contains(MetadataPartitionType.COLUMN_STATS)) {
      final HoodieData<HoodieRecord> metadataColumnStatsRDD = convertFilesToColumnStatsRecords(engineContext, partitionToDeletedFiles, partitionToAppendedFiles, recordsGenerationParams);
      partitionToRecordsMap.put(MetadataPartitionType.COLUMN_STATS, metadataColumnStatsRDD);
    }
    return partitionToRecordsMap;
  }

  /**
   * Aggregates all files deleted and appended to from all rollbacks associated with a restore operation then
   * creates metadata table records for them.
   *
   * @param restoreMetadata - Restore action metadata
   * @return a list of metadata table records
   */
  private static void processRestoreMetadata(HoodieActiveTimeline metadataTableTimeline,
                                             HoodieRestoreMetadata restoreMetadata,
                                             Map<String, Map<String, Long>> partitionToAppendedFiles,
                                             Map<String, List<String>> partitionToDeletedFiles,
                                             Option<String> lastSyncTs) {
    restoreMetadata.getHoodieRestoreMetadata().values().forEach(rms -> rms.forEach(rm -> processRollbackMetadata(metadataTableTimeline, rm,
        partitionToDeletedFiles, partitionToAppendedFiles, lastSyncTs)));
  }

  /**
   * Convert rollback action metadata to metadata table records.
   */
  public static Map<MetadataPartitionType, HoodieData<HoodieRecord>> convertMetadataToRecords(
      HoodieEngineContext engineContext, HoodieActiveTimeline metadataTableTimeline,
      HoodieRollbackMetadata rollbackMetadata, MetadataRecordsGenerationParams recordsGenerationParams,
      String instantTime, Option<String> lastSyncTs, boolean wasSynced) {
    final Map<MetadataPartitionType, HoodieData<HoodieRecord>> partitionToRecordsMap = new HashMap<>();
    Map<String, List<String>> partitionToDeletedFiles = new HashMap<>();
    Map<String, Map<String, Long>> partitionToAppendedFiles = new HashMap<>();

    List<HoodieRecord> filesPartitionRecords =
        convertMetadataToRollbackRecords(metadataTableTimeline, rollbackMetadata, partitionToDeletedFiles, partitionToAppendedFiles, instantTime, lastSyncTs, wasSynced);
    final HoodieData<HoodieRecord> rollbackRecordsRDD = engineContext.parallelize(filesPartitionRecords, 1);
    partitionToRecordsMap.put(MetadataPartitionType.FILES, rollbackRecordsRDD);

    if (recordsGenerationParams.getEnabledPartitionTypes().contains(MetadataPartitionType.BLOOM_FILTERS)) {
      final HoodieData<HoodieRecord> metadataBloomFilterRecordsRDD =
          convertFilesToBloomFilterRecords(engineContext, partitionToDeletedFiles, partitionToAppendedFiles, recordsGenerationParams, instantTime);
      partitionToRecordsMap.put(MetadataPartitionType.BLOOM_FILTERS, metadataBloomFilterRecordsRDD);
    }

    if (recordsGenerationParams.getEnabledPartitionTypes().contains(MetadataPartitionType.COLUMN_STATS)) {
      final HoodieData<HoodieRecord> metadataColumnStatsRDD = convertFilesToColumnStatsRecords(engineContext, partitionToDeletedFiles, partitionToAppendedFiles, recordsGenerationParams);
      partitionToRecordsMap.put(MetadataPartitionType.COLUMN_STATS, metadataColumnStatsRDD);
    }

    return partitionToRecordsMap;
  }

  /**
   * Convert rollback action metadata to files partition records.
   */
  private static List<HoodieRecord> convertMetadataToRollbackRecords(HoodieActiveTimeline metadataTableTimeline,
                                                                     HoodieRollbackMetadata rollbackMetadata,
                                                                     Map<String, List<String>> partitionToDeletedFiles,
                                                                     Map<String, Map<String, Long>> partitionToAppendedFiles,
                                                                     String instantTime,
                                                                     Option<String> lastSyncTs, boolean wasSynced) {
    processRollbackMetadata(metadataTableTimeline, rollbackMetadata, partitionToDeletedFiles,
        partitionToAppendedFiles, lastSyncTs);
    if (!wasSynced) {
      // Since the instant-being-rolled-back was never committed to the metadata table, the files added there
      // need not be deleted. For MOR Table, the rollback appends logBlocks so we need to keep the appended files.
      partitionToDeletedFiles.clear();
    }
    return convertFilesToFilesPartitionRecords(partitionToDeletedFiles, partitionToAppendedFiles, instantTime, "Rollback");
  }

  /**
   * Extracts information about the deleted and append files from the {@code HoodieRollbackMetadata}.
   * <p>
   * During a rollback files may be deleted (COW, MOR) or rollback blocks be appended (MOR only) to files. This
   * function will extract this change file for each partition.
   *
   * @param metadataTableTimeline    Current timeline of the Metadata Table
   * @param rollbackMetadata         {@code HoodieRollbackMetadata}
   * @param partitionToDeletedFiles  The {@code Map} to fill with files deleted per partition.
   * @param partitionToAppendedFiles The {@code Map} to fill with files appended per partition and their sizes.
   */
  private static void processRollbackMetadata(HoodieActiveTimeline metadataTableTimeline,
                                              HoodieRollbackMetadata rollbackMetadata,
                                              Map<String, List<String>> partitionToDeletedFiles,
                                              Map<String, Map<String, Long>> partitionToAppendedFiles,
                                              Option<String> lastSyncTs) {
    rollbackMetadata.getPartitionMetadata().values().forEach(pm -> {
      final String instantToRollback = rollbackMetadata.getCommitsRollback().get(0);
      // Has this rollback produced new files?
      boolean hasRollbackLogFiles = pm.getRollbackLogFiles() != null && !pm.getRollbackLogFiles().isEmpty();
      boolean hasNonZeroRollbackLogFiles = hasRollbackLogFiles && pm.getRollbackLogFiles().values().stream().mapToLong(Long::longValue).sum() > 0;

      // If instant-to-rollback has not been synced to metadata table yet then there is no need to update metadata
      // This can happen in two cases:
      //  Case 1: Metadata Table timeline is behind the instant-to-rollback.
      boolean shouldSkip = lastSyncTs.isPresent()
          && HoodieTimeline.compareTimestamps(instantToRollback, HoodieTimeline.GREATER_THAN, lastSyncTs.get());

      if (!hasNonZeroRollbackLogFiles && shouldSkip) {
        LOG.info(String.format("Skipping syncing of rollbackMetadata at %s, given metadata table is already synced upto to %s",
            instantToRollback, lastSyncTs.get()));
        return;
      }

      // Case 2: The instant-to-rollback was never committed to Metadata Table. This can happen if the instant-to-rollback
      // was a failed commit (never completed) as only completed instants are synced to Metadata Table.
      // But the required Metadata Table instants should not have been archived
      HoodieInstant syncedInstant = new HoodieInstant(false, HoodieTimeline.DELTA_COMMIT_ACTION, instantToRollback);
      if (metadataTableTimeline.getCommitsTimeline().isBeforeTimelineStarts(syncedInstant.getTimestamp())) {
        throw new HoodieMetadataException(String.format("The instant %s required to sync rollback of %s has been archived",
            syncedInstant, instantToRollback));
      }

      shouldSkip = !metadataTableTimeline.containsInstant(syncedInstant);
      if (!hasNonZeroRollbackLogFiles && shouldSkip) {
        LOG.info(String.format("Skipping syncing of rollbackMetadata at %s, since this instant was never committed to Metadata Table",
            instantToRollback));
        return;
      }

      final String partition = pm.getPartitionPath();
      if ((!pm.getSuccessDeleteFiles().isEmpty() || !pm.getFailedDeleteFiles().isEmpty()) && !shouldSkip) {
        if (!partitionToDeletedFiles.containsKey(partition)) {
          partitionToDeletedFiles.put(partition, new ArrayList<>());
        }

        // Extract deleted file name from the absolute paths saved in getSuccessDeleteFiles()
        List<String> deletedFiles = pm.getSuccessDeleteFiles().stream().map(p -> new Path(p).getName())
            .collect(Collectors.toList());
        if (!pm.getFailedDeleteFiles().isEmpty()) {
          deletedFiles.addAll(pm.getFailedDeleteFiles().stream().map(p -> new Path(p).getName())
              .collect(Collectors.toList()));
        }
        partitionToDeletedFiles.get(partition).addAll(deletedFiles);
      }

      BiFunction<Long, Long, Long> fileMergeFn = (oldSize, newSizeCopy) -> {
        // if a file exists in both written log files and rollback log files, we want to pick the one that is higher
        // as rollback file could have been updated after written log files are computed.
        return oldSize > newSizeCopy ? oldSize : newSizeCopy;
      };

      if (hasRollbackLogFiles) {
        if (!partitionToAppendedFiles.containsKey(partition)) {
          partitionToAppendedFiles.put(partition, new HashMap<>());
        }

        // Extract appended file name from the absolute paths saved in getAppendFiles()
        pm.getRollbackLogFiles().forEach((path, size) -> {
          partitionToAppendedFiles.get(partition).merge(new Path(path).getName(), size, fileMergeFn);
        });
      }
    });
  }

  /**
   * Convert rollback action metadata to files partition records.
   */
  private static List<HoodieRecord> convertFilesToFilesPartitionRecords(Map<String, List<String>> partitionToDeletedFiles,
                                                                        Map<String, Map<String, Long>> partitionToAppendedFiles,
                                                                        String instantTime, String operation) {
    List<HoodieRecord> records = new LinkedList<>();
    int[] fileChangeCount = {0, 0}; // deletes, appends

    partitionToDeletedFiles.forEach((partitionName, deletedFiles) -> {
      fileChangeCount[0] += deletedFiles.size();
      final String partition = getPartition(partitionName);

      Option<Map<String, Long>> filesAdded = Option.empty();
      if (partitionToAppendedFiles.containsKey(partitionName)) {
        filesAdded = Option.of(partitionToAppendedFiles.remove(partitionName));
      }

      HoodieRecord record = HoodieMetadataPayload.createPartitionFilesRecord(partition, filesAdded,
          Option.of(new ArrayList<>(deletedFiles)));
      records.add(record);
    });

    partitionToAppendedFiles.forEach((partitionName, appendedFileMap) -> {
      final String partition = getPartition(partitionName);
      fileChangeCount[1] += appendedFileMap.size();

      // Validate that no appended file has been deleted
      ValidationUtils.checkState(
          !appendedFileMap.keySet().removeAll(partitionToDeletedFiles.getOrDefault(partition, Collections.emptyList())),
          "Rollback file cannot both be appended and deleted");

      // New files added to a partition
      HoodieRecord record = HoodieMetadataPayload.createPartitionFilesRecord(partition, Option.of(appendedFileMap),
          Option.empty());
      records.add(record);
    });

    LOG.info("Found at " + instantTime + " from " + operation + ". #partitions_updated=" + records.size()
        + ", #files_deleted=" + fileChangeCount[0] + ", #files_appended=" + fileChangeCount[1]);

    return records;
  }

  /**
   * Returns partition name for the given path.
   *
   * @param path
   * @return
   */
  static String getPartition(@Nonnull String path) {
    return EMPTY_PARTITION_NAME.equals(path) ? NON_PARTITIONED_NAME : path;
  }

  /**
   * Convert added and deleted files metadata to bloom filter index records.
   */
  public static HoodieData<HoodieRecord> convertFilesToBloomFilterRecords(HoodieEngineContext engineContext,
                                                                          Map<String, List<String>> partitionToDeletedFiles,
                                                                          Map<String, Map<String, Long>> partitionToAppendedFiles,
                                                                          MetadataRecordsGenerationParams recordsGenerationParams,
                                                                          String instantTime) {
    HoodieData<HoodieRecord> allRecordsRDD = engineContext.emptyHoodieData();

    List<Pair<String, List<String>>> partitionToDeletedFilesList = partitionToDeletedFiles.entrySet()
        .stream().map(e -> Pair.of(e.getKey(), e.getValue())).collect(Collectors.toList());
    int parallelism = Math.max(Math.min(partitionToDeletedFilesList.size(), recordsGenerationParams.getBloomIndexParallelism()), 1);
    HoodieData<Pair<String, List<String>>> partitionToDeletedFilesRDD = engineContext.parallelize(partitionToDeletedFilesList, parallelism);

    HoodieData<HoodieRecord> deletedFilesRecordsRDD = partitionToDeletedFilesRDD.flatMap(partitionToDeletedFilesPair -> {
      final String partitionName = partitionToDeletedFilesPair.getLeft();
      final List<String> deletedFileList = partitionToDeletedFilesPair.getRight();
      return deletedFileList.stream().flatMap(deletedFile -> {
        if (!FSUtils.isBaseFile(new Path(deletedFile))) {
          return Stream.empty();
        }

        final String partition = getPartition(partitionName);
        return Stream.<HoodieRecord>of(HoodieMetadataPayload.createBloomFilterMetadataRecord(
            partition, deletedFile, instantTime, StringUtils.EMPTY_STRING, ByteBuffer.allocate(0), true));
      }).iterator();
    });
    allRecordsRDD = allRecordsRDD.union(deletedFilesRecordsRDD);

    List<Pair<String, Map<String, Long>>> partitionToAppendedFilesList = partitionToAppendedFiles.entrySet()
        .stream().map(entry -> Pair.of(entry.getKey(), entry.getValue())).collect(Collectors.toList());
    parallelism = Math.max(Math.min(partitionToAppendedFilesList.size(), recordsGenerationParams.getBloomIndexParallelism()), 1);
    HoodieData<Pair<String, Map<String, Long>>> partitionToAppendedFilesRDD = engineContext.parallelize(partitionToAppendedFilesList, parallelism);

    HoodieData<HoodieRecord> appendedFilesRecordsRDD = partitionToAppendedFilesRDD.flatMap(partitionToAppendedFilesPair -> {
      final String partitionName = partitionToAppendedFilesPair.getLeft();
      final Map<String, Long> appendedFileMap = partitionToAppendedFilesPair.getRight();
      final String partition = getPartition(partitionName);
      return appendedFileMap.entrySet().stream().flatMap(appendedFileLengthPairEntry -> {
        final String appendedFile = appendedFileLengthPairEntry.getKey();
        if (!FSUtils.isBaseFile(new Path(appendedFile))) {
          return Stream.empty();
        }
        final String pathWithPartition = partitionName + "/" + appendedFile;
        final Path appendedFilePath = new Path(recordsGenerationParams.getDataMetaClient().getBasePath(), pathWithPartition);
        try (HoodieFileReader<IndexedRecord> fileReader =
                 HoodieFileReaderFactory.getFileReader(recordsGenerationParams.getDataMetaClient().getHadoopConf(), appendedFilePath)) {
          final BloomFilter fileBloomFilter = fileReader.readBloomFilter();
          if (fileBloomFilter == null) {
            LOG.error("Failed to read bloom filter for " + appendedFilePath);
            return Stream.empty();
          }
          ByteBuffer bloomByteBuffer = ByteBuffer.wrap(fileBloomFilter.serializeToString().getBytes());
          HoodieRecord record = HoodieMetadataPayload.createBloomFilterMetadataRecord(
              partition, appendedFile, instantTime, recordsGenerationParams.getBloomFilterType(), bloomByteBuffer, false);
          return Stream.of(record);
        } catch (IOException e) {
          LOG.error("Failed to get bloom filter for file: " + appendedFilePath);
        }
        return Stream.empty();
      }).iterator();
    });
    allRecordsRDD = allRecordsRDD.union(appendedFilesRecordsRDD);

    return allRecordsRDD;
  }

  /**
   * Convert added and deleted action metadata to column stats index records.
   */
  public static HoodieData<HoodieRecord> convertFilesToColumnStatsRecords(HoodieEngineContext engineContext,
                                                                          Map<String, List<String>> partitionToDeletedFiles,
                                                                          Map<String, Map<String, Long>> partitionToAppendedFiles,
                                                                          MetadataRecordsGenerationParams recordsGenerationParams) {
    HoodieData<HoodieRecord> allRecordsRDD = engineContext.emptyHoodieData();
    HoodieTableMetaClient dataTableMetaClient = recordsGenerationParams.getDataMetaClient();

    final List<String> columnsToIndex = getColumnsToIndex(recordsGenerationParams,
        dataTableMetaClient.getTableConfig(), tryResolveSchemaForTable(dataTableMetaClient));

    if (columnsToIndex.isEmpty()) {
      // In case there are no columns to index, bail
      return engineContext.emptyHoodieData();
    }

    final List<Pair<String, List<String>>> partitionToDeletedFilesList = partitionToDeletedFiles.entrySet()
        .stream().map(e -> Pair.of(e.getKey(), e.getValue())).collect(Collectors.toList());
    int parallelism = Math.max(Math.min(partitionToDeletedFilesList.size(), recordsGenerationParams.getColumnStatsIndexParallelism()), 1);
    final HoodieData<Pair<String, List<String>>> partitionToDeletedFilesRDD = engineContext.parallelize(partitionToDeletedFilesList, parallelism);

    HoodieData<HoodieRecord> deletedFilesRecordsRDD = partitionToDeletedFilesRDD.flatMap(partitionToDeletedFilesPair -> {
      final String partitionName = partitionToDeletedFilesPair.getLeft();
      final String partition = getPartition(partitionName);
      final List<String> deletedFileList = partitionToDeletedFilesPair.getRight();

      return deletedFileList.stream().flatMap(deletedFile -> {
        final String filePathWithPartition = partitionName + "/" + deletedFile;
        return getColumnStats(partition, filePathWithPartition, dataTableMetaClient, columnsToIndex, true);
      }).iterator();
    });
    allRecordsRDD = allRecordsRDD.union(deletedFilesRecordsRDD);

    final List<Pair<String, Map<String, Long>>> partitionToAppendedFilesList = partitionToAppendedFiles.entrySet()
        .stream().map(entry -> Pair.of(entry.getKey(), entry.getValue())).collect(Collectors.toList());
    parallelism = Math.max(Math.min(partitionToAppendedFilesList.size(), recordsGenerationParams.getColumnStatsIndexParallelism()), 1);
    final HoodieData<Pair<String, Map<String, Long>>> partitionToAppendedFilesRDD = engineContext.parallelize(partitionToAppendedFilesList, parallelism);

    HoodieData<HoodieRecord> appendedFilesRecordsRDD = partitionToAppendedFilesRDD.flatMap(partitionToAppendedFilesPair -> {
      final String partitionName = partitionToAppendedFilesPair.getLeft();
      final String partition = getPartition(partitionName);
      final Map<String, Long> appendedFileMap = partitionToAppendedFilesPair.getRight();

      return appendedFileMap.entrySet().stream().flatMap(appendedFileNameLengthEntry -> {
        if (!FSUtils.isBaseFile(new Path(appendedFileNameLengthEntry.getKey()))
            || !appendedFileNameLengthEntry.getKey().endsWith(HoodieFileFormat.PARQUET.getFileExtension())) {
          return Stream.empty();
        }
        final String filePathWithPartition = partitionName + "/" + appendedFileNameLengthEntry.getKey();
        return getColumnStats(partition, filePathWithPartition, dataTableMetaClient, columnsToIndex, false);
      }).iterator();

    });
    allRecordsRDD = allRecordsRDD.union(appendedFilesRecordsRDD);

    return allRecordsRDD;
  }

  /**
   * Map a record key to a file group in partition of interest.
   * <p>
   * Note: For hashing, the algorithm is same as String.hashCode() but is being defined here as hashCode()
   * implementation is not guaranteed by the JVM to be consistent across JVM versions and implementations.
   *
   * @param recordKey record key for which the file group index is looked up for.
   * @return An integer hash of the given string
   */
  public static int mapRecordKeyToFileGroupIndex(String recordKey, int numFileGroups) {
    int h = 0;
    for (int i = 0; i < recordKey.length(); ++i) {
      h = 31 * h + recordKey.charAt(i);
    }

    return Math.abs(Math.abs(h) % numFileGroups);
  }

  /**
   * Get the latest file slices for a Metadata Table partition. If the file slice is
   * because of pending compaction instant, then merge the file slice with the one
   * just before the compaction instant time. The list of file slices returned is
   * sorted in the correct order of file group name.
   *
   * @param metaClient - Instance of {@link HoodieTableMetaClient}.
   * @param partition  - The name of the partition whose file groups are to be loaded.
   * @return List of latest file slices for all file groups in a given partition.
   */
  public static List<FileSlice> getPartitionLatestMergedFileSlices(HoodieTableMetaClient metaClient, String partition) {
    LOG.info("Loading latest merged file slices for metadata table partition " + partition);
    return getPartitionFileSlices(metaClient, Option.empty(), partition, true);
  }

  /**
   * Get the latest file slices for a Metadata Table partition. The list of file slices
   * returned is sorted in the correct order of file group name.
   *
   * @param metaClient - Instance of {@link HoodieTableMetaClient}.
   * @param fsView     - Metadata table filesystem view
   * @param partition  - The name of the partition whose file groups are to be loaded.
   * @return List of latest file slices for all file groups in a given partition.
   */
  public static List<FileSlice> getPartitionLatestFileSlices(HoodieTableMetaClient metaClient,
                                                             Option<HoodieTableFileSystemView> fsView, String partition) {
    LOG.info("Loading latest file slices for metadata table partition " + partition);
    return getPartitionFileSlices(metaClient, fsView, partition, false);
  }

  /**
   * Get metadata table file system view.
   *
   * @param metaClient - Metadata table meta client
   * @return Filesystem view for the metadata table
   */
  public static HoodieTableFileSystemView getFileSystemView(HoodieTableMetaClient metaClient) {
    // If there are no commits on the metadata table then the table's
    // default FileSystemView will not return any file slices even
    // though we may have initialized them.
    HoodieTimeline timeline = metaClient.getActiveTimeline();
    if (timeline.empty()) {
      final HoodieInstant instant = new HoodieInstant(false, HoodieTimeline.DELTA_COMMIT_ACTION,
          HoodieActiveTimeline.createNewInstantTime());
      timeline = new HoodieDefaultTimeline(Stream.of(instant), metaClient.getActiveTimeline()::getInstantDetails);
    }
    return new HoodieTableFileSystemView(metaClient, timeline);
  }

  /**
   * Get the latest file slices for a given partition.
   *
   * @param metaClient      - Instance of {@link HoodieTableMetaClient}.
   * @param partition       - The name of the partition whose file groups are to be loaded.
   * @param mergeFileSlices - When enabled, will merge the latest file slices with the last known
   *                        completed instant. This is useful for readers when there are pending
   *                        compactions. MergeFileSlices when disabled, will return the latest file
   *                        slices without any merging, and this is needed for the writers.
   * @return List of latest file slices for all file groups in a given partition.
   */
  private static List<FileSlice> getPartitionFileSlices(HoodieTableMetaClient metaClient,
                                                        Option<HoodieTableFileSystemView> fileSystemView,
                                                        String partition,
                                                        boolean mergeFileSlices) {
    HoodieTableFileSystemView fsView = fileSystemView.orElse(getFileSystemView(metaClient));
    Stream<FileSlice> fileSliceStream;
    if (mergeFileSlices) {
      fileSliceStream = fsView.getLatestMergedFileSlicesBeforeOrOn(
          partition, metaClient.getActiveTimeline().filterCompletedInstants().lastInstant().get().getTimestamp());
    } else {
      fileSliceStream = fsView.getLatestFileSlices(partition);
    }
    return fileSliceStream.sorted(Comparator.comparing(FileSlice::getFileId)).collect(Collectors.toList());
  }

  public static HoodieData<HoodieRecord> convertMetadataToColumnStatsRecords(HoodieCommitMetadata commitMetadata,
                                                                             HoodieEngineContext engineContext,
                                                                             MetadataRecordsGenerationParams recordsGenerationParams) {
    List<HoodieWriteStat> allWriteStats = commitMetadata.getPartitionToWriteStats().values().stream()
        .flatMap(Collection::stream).collect(Collectors.toList());

    if (allWriteStats.isEmpty()) {
      return engineContext.emptyHoodieData();
    }

    try {
      Option<Schema> writerSchema =
          Option.ofNullable(commitMetadata.getMetadata(HoodieCommitMetadata.SCHEMA_KEY))
              .flatMap(writerSchemaStr ->
                  isNullOrEmpty(writerSchemaStr)
                    ? Option.empty()
                    : Option.of(new Schema.Parser().parse(writerSchemaStr)));

      HoodieTableMetaClient dataTableMetaClient = recordsGenerationParams.getDataMetaClient();
      HoodieTableConfig tableConfig = dataTableMetaClient.getTableConfig();

      // NOTE: Writer schema added to commit metadata will not contain Hudi's metadata fields
      Option<Schema> tableSchema = writerSchema.map(schema ->
          tableConfig.populateMetaFields() ? addMetadataFields(schema) : schema);

      List<String> columnsToIndex = getColumnsToIndex(recordsGenerationParams,
          tableConfig, tableSchema);

      if (columnsToIndex.isEmpty()) {
        // In case there are no columns to index, bail
        return engineContext.emptyHoodieData();
      }

      int parallelism = Math.max(Math.min(allWriteStats.size(), recordsGenerationParams.getColumnStatsIndexParallelism()), 1);
      return engineContext.parallelize(allWriteStats, parallelism)
          .flatMap(writeStat ->
              translateWriteStatToColumnStats(writeStat, dataTableMetaClient, columnsToIndex).iterator());
    } catch (Exception e) {
      throw new HoodieException("Failed to generate column stats records for metadata table", e);
    }
  }

  /**
   * Get the latest columns for the table for column stats indexing.
   */
  private static List<String> getColumnsToIndex(MetadataRecordsGenerationParams recordsGenParams,
                                                HoodieTableConfig tableConfig,
                                                Option<Schema> writerSchemaOpt) {
    if (recordsGenParams.isAllColumnStatsIndexEnabled() && writerSchemaOpt.isPresent()) {
      return writerSchemaOpt.get().getFields().stream()
          .map(Schema.Field::name).collect(Collectors.toList());
    }

    // In case no writer schema could be obtained we fall back to only index primary key
    // columns
    return Arrays.asList(tableConfig.getRecordKeyFields().get());
  }

  public static HoodieMetadataColumnStats mergeColumnStats(HoodieMetadataColumnStats oldColumnStats, HoodieMetadataColumnStats newColumnStats) {
    ValidationUtils.checkArgument(oldColumnStats.getFileName().equals(newColumnStats.getFileName()));
    if (newColumnStats.getIsDeleted()) {
      return newColumnStats;
    }
    return HoodieMetadataColumnStats.newBuilder()
        .setFileName(newColumnStats.getFileName())
        .setMinValue(Stream.of(oldColumnStats.getMinValue(), newColumnStats.getMinValue()).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null))
        .setMaxValue(Stream.of(oldColumnStats.getMinValue(), newColumnStats.getMinValue()).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null))
        .setValueCount(oldColumnStats.getValueCount() + newColumnStats.getValueCount())
        .setNullCount(oldColumnStats.getNullCount() + newColumnStats.getNullCount())
        .setTotalSize(oldColumnStats.getTotalSize() + newColumnStats.getTotalSize())
        .setTotalUncompressedSize(oldColumnStats.getTotalUncompressedSize() + newColumnStats.getTotalUncompressedSize())
        .setIsDeleted(newColumnStats.getIsDeleted())
        .build();
  }

  public static Stream<HoodieRecord> translateWriteStatToColumnStats(HoodieWriteStat writeStat,
                                                                     HoodieTableMetaClient datasetMetaClient,
                                                                     List<String> columnsToIndex) {
    if (writeStat instanceof HoodieDeltaWriteStat && ((HoodieDeltaWriteStat) writeStat).getRecordsStats().isPresent()) {
      Map<String, HoodieColumnRangeMetadata<Comparable>> columnRangeMap = ((HoodieDeltaWriteStat) writeStat).getRecordsStats().get().getStats();
      List<HoodieColumnRangeMetadata<Comparable>> columnRangeMetadataList = new ArrayList<>(columnRangeMap.values());
      return HoodieMetadataPayload.createColumnStatsRecords(writeStat.getPartitionPath(), columnRangeMetadataList, false);
    }
    return getColumnStats(writeStat.getPartitionPath(), writeStat.getPath(), datasetMetaClient, columnsToIndex, false);
  }

  private static Stream<HoodieRecord> getColumnStats(final String partitionPath, final String filePathWithPartition,
                                                     HoodieTableMetaClient datasetMetaClient,
                                                     List<String> columnsToIndex,
                                                     boolean isDeleted) {
    final String partition = getPartition(partitionPath);
    final int offset = partition.equals(NON_PARTITIONED_NAME) ? (filePathWithPartition.startsWith("/") ? 1 : 0)
        : partition.length() + 1;
    final String fileName = filePathWithPartition.substring(offset);

    if (filePathWithPartition.endsWith(HoodieFileFormat.PARQUET.getFileExtension())) {
      final Path fullFilePath = new Path(datasetMetaClient.getBasePath(), filePathWithPartition);
      List<HoodieColumnRangeMetadata<Comparable>> columnRangeMetadataList;
      if (!isDeleted) {
        columnRangeMetadataList = new ParquetUtils().readRangeFromParquetMetadata(
            datasetMetaClient.getHadoopConf(), fullFilePath, columnsToIndex);
      } else {
        // TODO we should delete records instead of stubbing them
        columnRangeMetadataList =
            columnsToIndex.stream().map(entry -> new HoodieColumnRangeMetadata<Comparable>(fileName,
                    entry, null, null, 0, 0, 0, 0))
                .collect(Collectors.toList());
      }
      return HoodieMetadataPayload.createColumnStatsRecords(partitionPath, columnRangeMetadataList, isDeleted);
    } else {
      throw new HoodieException("Column range index not supported for filePathWithPartition " + fileName);
    }
  }

  /**
   * Get file group count for a metadata table partition.
   *
   * @param partitionType        - Metadata table partition type
   * @param metaClient           - Metadata table meta client
   * @param fsView               - Filesystem view
   * @param metadataConfig       - Metadata config
   * @param isBootstrapCompleted - Is bootstrap completed for the metadata table
   * @return File group count for the requested metadata partition type
   */
  public static int getPartitionFileGroupCount(final MetadataPartitionType partitionType,
                                               final Option<HoodieTableMetaClient> metaClient,
                                               final Option<HoodieTableFileSystemView> fsView,
                                               final HoodieMetadataConfig metadataConfig, boolean isBootstrapCompleted) {
    if (isBootstrapCompleted) {
      final List<FileSlice> latestFileSlices = HoodieTableMetadataUtil
          .getPartitionLatestFileSlices(metaClient.get(), fsView, partitionType.getPartitionPath());
      return Math.max(latestFileSlices.size(), 1);
    }

    switch (partitionType) {
      case BLOOM_FILTERS:
        return metadataConfig.getBloomFilterIndexFileGroupCount();
      case COLUMN_STATS:
        return metadataConfig.getColumnStatsIndexFileGroupCount();
      default:
        return 1;
    }
  }

  /**
   * Accumulates column range metadata for the given field and updates the column range map.
   *
   * @param field          - column for which statistics will be computed
   * @param filePath       - data file path
   * @param columnRangeMap - old column range statistics, which will be merged in this computation
   * @param columnToStats  - map of column to map of each stat and its value
   */
  public static void accumulateColumnRanges(Schema.Field field, String filePath,
                                            Map<String, HoodieColumnRangeMetadata<Comparable>> columnRangeMap,
                                            Map<String, Map<String, Object>> columnToStats) {
    Map<String, Object> columnStats = columnToStats.get(field.name());
    HoodieColumnRangeMetadata<Comparable> columnRangeMetadata = new HoodieColumnRangeMetadata<>(
        filePath,
        field.name(),
        String.valueOf(columnStats.get(MIN)),
        String.valueOf(columnStats.get(MAX)),
        Long.parseLong(columnStats.getOrDefault(NULL_COUNT, 0).toString()),
        Long.parseLong(columnStats.getOrDefault(VALUE_COUNT, 0).toString()),
        Long.parseLong(columnStats.getOrDefault(TOTAL_SIZE, 0).toString()),
        Long.parseLong(columnStats.getOrDefault(TOTAL_UNCOMPRESSED_SIZE, 0).toString())
    );
    columnRangeMap.merge(field.name(), columnRangeMetadata, COLUMN_RANGE_MERGE_FUNCTION);
  }

  /**
   * Aggregates column stats for each field.
   *
   * @param record                            - current record
   * @param schema                            - write schema
   * @param columnToStats                     - map of column to map of each stat and its value which gets updates in this method
   * @param consistentLogicalTimestampEnabled - flag to deal with logical timestamp type when getting column value
   */
  public static void aggregateColumnStats(IndexedRecord record, Schema schema,
                                          Map<String, Map<String, Object>> columnToStats,
                                          boolean consistentLogicalTimestampEnabled) {
    if (!(record instanceof GenericRecord)) {
      throw new HoodieIOException("Record is not a generic type to get column range metadata!");
    }

    schema.getFields().forEach(field -> {
      Map<String, Object> columnStats = columnToStats.getOrDefault(field.name(), new HashMap<>());
      final String fieldVal = getNestedFieldValAsString((GenericRecord) record, field.name(), true, consistentLogicalTimestampEnabled);
      // update stats
      final int fieldSize = fieldVal == null ? 0 : fieldVal.length();
      columnStats.put(TOTAL_SIZE, Long.parseLong(columnStats.getOrDefault(TOTAL_SIZE, 0).toString()) + fieldSize);
      columnStats.put(TOTAL_UNCOMPRESSED_SIZE, Long.parseLong(columnStats.getOrDefault(TOTAL_UNCOMPRESSED_SIZE, 0).toString()) + fieldSize);

      if (!isNullOrEmpty(fieldVal)) {
        // set the min value of the field
        if (!columnStats.containsKey(MIN)) {
          columnStats.put(MIN, fieldVal);
        }
        if (fieldVal.compareTo(String.valueOf(columnStats.get(MIN))) < 0) {
          columnStats.put(MIN, fieldVal);
        }
        // set the max value of the field
        if (fieldVal.compareTo(String.valueOf(columnStats.getOrDefault(MAX, ""))) > 0) {
          columnStats.put(MAX, fieldVal);
        }
        // increment non-null value count
        columnStats.put(VALUE_COUNT, Long.parseLong(columnStats.getOrDefault(VALUE_COUNT, 0).toString()) + 1);
      } else {
        // increment null value count
        columnStats.put(NULL_COUNT, Long.parseLong(columnStats.getOrDefault(NULL_COUNT, 0).toString()) + 1);
      }
    });
  }

  private static Option<Schema> tryResolveSchemaForTable(HoodieTableMetaClient dataTableMetaClient) {
    if (dataTableMetaClient.getCommitsTimeline().filterCompletedInstants().countInstants() == 0) {
      return Option.empty();
    }

    TableSchemaResolver schemaResolver = new TableSchemaResolver(dataTableMetaClient);
    try {
      return Option.of(schemaResolver.getTableAvroSchema());
    } catch (Exception e) {
      throw new HoodieException("Failed to get latest columns for " + dataTableMetaClient.getBasePath(), e);
    }
  }
}
