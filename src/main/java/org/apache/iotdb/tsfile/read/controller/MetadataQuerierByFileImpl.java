/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.tsfile.read.controller;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.apache.iotdb.tsfile.common.cache.LRUCache;
import org.apache.iotdb.tsfile.exception.write.NoMeasurementException;
import org.apache.iotdb.tsfile.file.metadata.ChunkMetaData;
import org.apache.iotdb.tsfile.file.metadata.TimeseriesMetaData;
import org.apache.iotdb.tsfile.file.metadata.TsFileMetaData;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.read.TsFileSequenceReader;
import org.apache.iotdb.tsfile.read.common.Path;
import org.apache.iotdb.tsfile.read.common.TimeRange;

public class MetadataQuerierByFileImpl implements IMetadataQuerier {

  private static final int CHUNK_METADATA_CACHE_SIZE = 10000;

  private TsFileMetaData fileMetaData;

  private LRUCache<Path, List<ChunkMetaData>> chunkMetaDataCache;

  private TsFileSequenceReader tsFileReader;

  /**
   * Constructor of MetadataQuerierByFileImpl.
   */
  public MetadataQuerierByFileImpl(TsFileSequenceReader tsFileReader) throws IOException {
    this.tsFileReader = tsFileReader;
    this.fileMetaData = tsFileReader.readFileMetadata();
    chunkMetaDataCache = new LRUCache<Path, List<ChunkMetaData>>(CHUNK_METADATA_CACHE_SIZE) {
      @Override
      public List<ChunkMetaData> loadObjectByKey(Path key) throws IOException {
        return loadChunkMetadata(key);
      }
    };
  }

  @Override
  public List<ChunkMetaData> getChunkMetaDataList(Path path) throws IOException {
    return chunkMetaDataCache.get(path);
  }

  @Override
  public Map<Path, List<ChunkMetaData>> getChunkMetaDataMap(List<Path> paths) throws IOException {
    Map<Path, List<ChunkMetaData>> chunkMetaDatas = new HashMap<>();
    for (Path path : paths) {
      if (!chunkMetaDatas.containsKey(path)) {
        chunkMetaDatas.put(path, new ArrayList<>());
      }
      chunkMetaDatas.get(path).addAll(getChunkMetaDataList(path));
    }
    return chunkMetaDatas;
  }

  @Override
  public TsFileMetaData getWholeFileMetadata() {
    return fileMetaData;
  }

  // @Override
  public void loadChunkMetaDatasV2(List<Path> paths) throws IOException {
    int count = 0;
    for (Path path : paths) {
      if (count >= CHUNK_METADATA_CACHE_SIZE) {
        break;
      }
      chunkMetaDataCache.put(path, tsFileReader.getChunkMetadataList(path));
      count += tsFileReader.getChunkMetadataList(path).size();
    }

  }
  
  @Override
  public void loadChunkMetaDatas(List<Path> paths) throws IOException {
    // group measurements by device
    TreeMap<String, Set<String>> deviceMeasurementsMap = new TreeMap<>();
    for (Path path : paths) {
      if (!deviceMeasurementsMap.containsKey(path.getDevice())) {
        deviceMeasurementsMap.put(path.getDevice(), new HashSet<>());
      }
      deviceMeasurementsMap.get(path.getDevice()).add(path.getMeasurement());
    }
    
    Map<Path, List<ChunkMetaData>> tempChunkMetaDatas = new HashMap<>();
    
    int count = 0;
    boolean enough = false;
    
    for (Map.Entry<String, Set<String>> deviceMeasurements : deviceMeasurementsMap.entrySet()) {
      if (enough) {
        break;
      }
      String selectedDevice = deviceMeasurements.getKey();
      // s1, s2, s3
      Set<String> selectedMeasurements = deviceMeasurements.getValue();
      if (fileMetaData.getDeviceOffsetsMap() == null 
          || !fileMetaData.getDeviceOffsetsMap().containsKey(selectedDevice)) {
        continue;
      }
      
      int[] deviceIndex = fileMetaData.getDeviceOffsetsMap().get(selectedDevice);
      int start = deviceIndex[0];
      int end = deviceIndex[1];
      List<TimeseriesMetaData> timeseriesMetaDataInDevice = tsFileReader
          .readTimeseriesMetadataInDevice(start, end);
      List<ChunkMetaData> chunkMetaDataList = new ArrayList<>();
      for (TimeseriesMetaData tsMetaData : timeseriesMetaDataInDevice) {
        if (selectedMeasurements.contains(tsMetaData.getMeasurementId())) {
          chunkMetaDataList.addAll(tsFileReader.readChunkMetaDataList(tsMetaData));
        }
      }
      // d1
      for (ChunkMetaData chunkMetaData : chunkMetaDataList) {
        String currentMeasurement = chunkMetaData.getMeasurementUid();

        // s1
        if (selectedMeasurements.contains(currentMeasurement)) {

          // d1.s1
          Path path = new Path(selectedDevice, currentMeasurement);

          // add into tempChunkMetaDatas
          if (!tempChunkMetaDatas.containsKey(path)) {
            tempChunkMetaDatas.put(path, new ArrayList<>());
          }
          tempChunkMetaDatas.get(path).add(chunkMetaData);

          // check cache size, stop when reading enough
          count++;
          if (count == CHUNK_METADATA_CACHE_SIZE) {
            enough = true;
            break;
          }
        }
      }
    }
    
    for (Map.Entry<Path, List<ChunkMetaData>> entry : tempChunkMetaDatas.entrySet()) {
      chunkMetaDataCache.put(entry.getKey(), entry.getValue());
    }
  }

  @Override
  public TSDataType getDataType(Path path) throws NoMeasurementException, IOException {
    if (tsFileReader.getChunkMetadataList(path) == null || tsFileReader.getChunkMetadataList(path).isEmpty()) {
      // throw new NoMeasurementException(String.format("%s not found.", path));
      return null;
    }
    return tsFileReader.getChunkMetadataList(path).get(0).getDataType();

  }

  private List<ChunkMetaData> loadChunkMetadata(Path path) throws IOException {
    return tsFileReader.getChunkMetadataList(path);
  }
  
  

  @Override
  public List<TimeRange> convertSpace2TimePartition(List<Path> paths, long spacePartitionStartPos,
      long spacePartitionEndPos) throws IOException {
    if (spacePartitionStartPos > spacePartitionEndPos) {
      throw new IllegalArgumentException("'spacePartitionStartPos' should not be larger than 'spacePartitionEndPos'.");
    }

    // (1) get timeRangesInCandidates and timeRangesBeforeCandidates by iterating
    // through the metadata
    ArrayList<TimeRange> timeRangesInCandidates = new ArrayList<>();
    ArrayList<TimeRange> timeRangesBeforeCandidates = new ArrayList<>();

    // group measurements by device

    TreeMap<String, Set<String>> deviceMeasurementsMap = new TreeMap<>();
    for (Path path : paths) {
      if (!deviceMeasurementsMap.containsKey(path.getDevice())) {
        deviceMeasurementsMap.put(path.getDevice(), new HashSet<>());
      }
      deviceMeasurementsMap.get(path.getDevice()).add(path.getMeasurement());
    }
    Map<String, int[]> deviceOffsetsMap = fileMetaData.getDeviceOffsetsMap();
    for (Map.Entry<String, Set<String>> deviceMeasurements : deviceMeasurementsMap.entrySet()) {
      String selectedDevice = deviceMeasurements.getKey();
      Set<String> selectedMeasurements = deviceMeasurements.getValue();
      int[] deviceOffsets = deviceOffsetsMap.get(selectedDevice);
      List<ChunkMetaData> chunkMetadataList = tsFileReader.readChunkMetadataInDevice(deviceOffsets[0],
          deviceOffsets[1]);
      for (ChunkMetaData chunkMetaData : chunkMetadataList) {
        LocateStatus mode = checkLocateStatus(chunkMetaData, spacePartitionStartPos, spacePartitionEndPos);
        if (mode == LocateStatus.after) {
          continue;
        }
        String currentMeasurement = chunkMetaData.getMeasurementUid();
        if (selectedMeasurements.contains(currentMeasurement)) {
          TimeRange timeRange = new TimeRange(chunkMetaData.getStartTime(), chunkMetaData.getEndTime());
          if (mode == LocateStatus.in) {
            timeRangesInCandidates.add(timeRange);
          } else {
            timeRangesBeforeCandidates.add(timeRange);
          }
        }
      }

    }
    
    // (2) sort and merge the timeRangesInCandidates
    ArrayList<TimeRange> timeRangesIn = new ArrayList<>(TimeRange.sortAndMerge(timeRangesInCandidates));
    if (timeRangesIn.isEmpty()) {
      return Collections.emptyList(); // return an empty list
    }

    // (3) sort and merge the timeRangesBeforeCandidates
    ArrayList<TimeRange> timeRangesBefore = new ArrayList<>(TimeRange.sortAndMerge(timeRangesBeforeCandidates));

    // (4) calculate the remaining time ranges
    List<TimeRange> resTimeRanges = new ArrayList<>();
    for (TimeRange in : timeRangesIn) {
      ArrayList<TimeRange> remains = new ArrayList<>(in.getRemains(timeRangesBefore));
      resTimeRanges.addAll(remains);
    }

    return resTimeRanges;
  }
  

  /**
   * Check the location of a given chunkGroupMetaData with respect to a space
   * partition constraint.
   *
   * @param chunkMetaData     the given chunkMetaData
   * @param spacePartitionStartPos the start position of the space partition
   * @param spacePartitionEndPos   the end position of the space partition
   * @return LocateStatus
   */
  

  private LocateStatus checkLocateStatus(ChunkMetaData chunkMetaData, 
      long spacePartitionStartPos, long spacePartitionEndPos) {
    long startOffsetOfChunk = chunkMetaData.getOffsetOfChunkHeader();
    long endOffsetOfChunk = chunkMetaData.getOffsetOfChunkHeader()+ 30;
    long middleOffsetOfChunk = (startOffsetOfChunk + endOffsetOfChunk) / 2;
    if (spacePartitionStartPos <= middleOffsetOfChunk && middleOffsetOfChunk < spacePartitionEndPos) {
      return LocateStatus.in;
    } else if (middleOffsetOfChunk < spacePartitionStartPos) {
      return LocateStatus.before;
    } else {
      return LocateStatus.after;
    }
  }

  /**
   * The location of a chunkGroupMetaData with respect to a space partition
   * constraint.
   *
   * in - the middle point of the chunkGroupMetaData is located in the current
   * space partition. before - the middle point of the chunkGroupMetaData is
   * located before the current space partition. after - the middle point of the
   * chunkGroupMetaData is located after the current space partition.
   */
  
  private enum LocateStatus {
    in, before, after
  }


  @Override
  public void clear() {
    chunkMetaDataCache.clear();
  }

}
