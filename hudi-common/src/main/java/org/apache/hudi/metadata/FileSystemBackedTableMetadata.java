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

import org.apache.hudi.avro.model.HoodieMetadataColumnStats;
import org.apache.hudi.common.bloom.BloomFilter;
import org.apache.hudi.common.config.SerializableConfiguration;
import org.apache.hudi.common.engine.HoodieEngineContext;
import org.apache.hudi.common.fs.FSUtils;
import org.apache.hudi.common.model.HoodiePartitionMetadata;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.common.util.collection.Pair;

import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hudi.exception.HoodieMetadataException;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

public class FileSystemBackedTableMetadata implements HoodieTableMetadata {

  private static final int DEFAULT_LISTING_PARALLELISM = 1500;

  private final transient HoodieEngineContext engineContext;
  private final SerializableConfiguration hadoopConf;
  private final String datasetBasePath;
  private final boolean assumeDatePartitioning;

  public FileSystemBackedTableMetadata(HoodieEngineContext engineContext, SerializableConfiguration conf, String datasetBasePath,
                                       boolean assumeDatePartitioning) {
    this.engineContext = engineContext;
    this.hadoopConf = conf;
    this.datasetBasePath = datasetBasePath;
    this.assumeDatePartitioning = assumeDatePartitioning;
  }

  @Override
  public FileStatus[] getAllFilesInPartition(Path partitionPath) throws IOException {
    FileSystem fs = partitionPath.getFileSystem(hadoopConf.get());
    return FSUtils.getAllDataFilesInPartition(fs, partitionPath);
  }

  @Override
  public List<String> getAllPartitionPaths() throws IOException {
    FileSystem fs = new Path(datasetBasePath).getFileSystem(hadoopConf.get());
    if (assumeDatePartitioning) {
      return FSUtils.getAllPartitionFoldersThreeLevelsDown(fs, datasetBasePath);
    }

    List<Path> pathsToList = new CopyOnWriteArrayList<>();
    pathsToList.add(new Path(datasetBasePath));
    List<String> partitionPaths = new CopyOnWriteArrayList<>();

    while (!pathsToList.isEmpty()) {
      // TODO: Get the parallelism from HoodieWriteConfig
      int listingParallelism = Math.min(DEFAULT_LISTING_PARALLELISM, pathsToList.size());

      // List all directories in parallel
      List<FileStatus[]> dirToFileListing = engineContext.map(pathsToList, path -> {
        FileSystem fileSystem = path.getFileSystem(hadoopConf.get());
        return fileSystem.listStatus(path);
      }, listingParallelism);
      pathsToList.clear();

      // if current dictionary contains PartitionMetadata, add it to result
      // if current dictionary does not contain PartitionMetadata, add it to queue
      dirToFileListing.stream().flatMap(Arrays::stream).parallel()
          .forEach(fileStatus -> {
            if (fileStatus.isDirectory()) {
              if (HoodiePartitionMetadata.hasPartitionMetadata(fs, fileStatus.getPath())) {
                partitionPaths.add(FSUtils.getRelativePartitionPath(new Path(datasetBasePath), fileStatus.getPath()));
              } else if (!fileStatus.getPath().getName().equals(HoodieTableMetaClient.METAFOLDER_NAME)) {
                pathsToList.add(fileStatus.getPath());
              }
            } else if (fileStatus.getPath().getName().equals(HoodiePartitionMetadata.HOODIE_PARTITION_METAFILE)) {
              String partitionName = FSUtils.getRelativePartitionPath(new Path(datasetBasePath), fileStatus.getPath().getParent());
              partitionPaths.add(partitionName);
            }
          });
    }
    return partitionPaths;
  }

  @Override
  public Map<String, FileStatus[]> getAllFilesInPartitions(List<String> partitionPaths)
      throws IOException {
    if (partitionPaths == null || partitionPaths.isEmpty()) {
      return Collections.emptyMap();
    }

    int parallelism = Math.min(DEFAULT_LISTING_PARALLELISM, partitionPaths.size());

    List<Pair<String, FileStatus[]>> partitionToFiles = engineContext.map(partitionPaths, partitionPathStr -> {
      Path partitionPath = new Path(partitionPathStr);
      FileSystem fs = partitionPath.getFileSystem(hadoopConf.get());
      return Pair.of(partitionPathStr, FSUtils.getAllDataFilesInPartition(fs, partitionPath));
    }, parallelism);

    return partitionToFiles.stream().collect(Collectors.toMap(Pair::getLeft, Pair::getRight));
  }

  @Override
  public Option<String> getSyncedInstantTime() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Option<String> getLatestCompactionTime() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void close() throws Exception {
    // no-op
  }

  @Override
  public void reset() {
    // no-op
  }

  public Option<BloomFilter> getBloomFilter(final String partitionName, final String fileName)
      throws HoodieMetadataException {
    throw new HoodieMetadataException("Unsupported operation: getBloomFilter for " + fileName);
  }

  @Override
  public Map<Pair<String, String>, BloomFilter> getBloomFilters(final List<Pair<String, String>> partitionNameFileNameList)
      throws HoodieMetadataException {
    throw new HoodieMetadataException("Unsupported operation: getBloomFilters!");
  }

  @Override
  public Map<Pair<String, String>, HoodieMetadataColumnStats> getColumnStats(final List<Pair<String, String>> partitionNameFileNameList, final String columnName)
      throws HoodieMetadataException {
    throw new HoodieMetadataException("Unsupported operation: getColumnsStats!");
  }
}
