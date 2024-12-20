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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.backup.impl;

import com.google.errorprone.annotations.RestrictedApi;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.backup.BackupInfo;
import org.apache.hadoop.hbase.backup.BackupType;
import org.apache.hadoop.hbase.backup.HBackupFileSystem;
import org.apache.hadoop.hbase.backup.util.BackupUtils;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.hbase.shaded.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.shaded.protobuf.generated.BackupProtos;
import org.apache.hadoop.hbase.shaded.protobuf.generated.HBaseProtos;

/**
 * Backup manifest contains all the meta data of a backup image. The manifest info will be bundled
 * as manifest file together with data. So that each backup image will contain all the info needed
 * for restore. BackupManifest is a storage container for BackupImage. It is responsible for
 * storing/reading backup image data and has some additional utility methods.
 */
@InterfaceAudience.Private
public class BackupManifest {
  private static final Logger LOG = LoggerFactory.getLogger(BackupManifest.class);

  // manifest file name
  public static final String MANIFEST_FILE_NAME = ".backup.manifest";

  /**
   * Backup image, the dependency graph is made up by series of backup images BackupImage contains
   * all the relevant information to restore the backup and is used during restore operation
   */
  public static class BackupImage implements Comparable<BackupImage> {
    static class Builder {
      BackupImage image;

      Builder() {
        image = new BackupImage();
      }

      Builder withBackupId(String backupId) {
        image.setBackupId(backupId);
        return this;
      }

      Builder withType(BackupType type) {
        image.setType(type);
        return this;
      }

      Builder withRootDir(String rootDir) {
        image.setRootDir(rootDir);
        return this;
      }

      Builder withTableList(List<TableName> tableList) {
        image.setTableList(tableList);
        return this;
      }

      Builder withStartTime(long startTime) {
        image.setStartTs(startTime);
        return this;
      }

      Builder withCompleteTime(long completeTime) {
        image.setCompleteTs(completeTime);
        return this;
      }

      BackupImage build() {
        return image;
      }

    }

    private String backupId;
    private BackupType type;
    private String rootDir;
    private List<TableName> tableList;
    private long startTs;
    private long completeTs;
    private ArrayList<BackupImage> ancestors;
    private Map<TableName, Map<String, Long>> incrTimeRanges;

    static Builder newBuilder() {
      return new Builder();
    }

    public BackupImage() {
      super();
    }

    private BackupImage(String backupId, BackupType type, String rootDir, List<TableName> tableList,
      long startTs, long completeTs) {
      this.backupId = backupId;
      this.type = type;
      this.rootDir = rootDir;
      this.tableList = tableList;
      this.startTs = startTs;
      this.completeTs = completeTs;
    }

    static BackupImage fromProto(BackupProtos.BackupImage im) {
      String backupId = im.getBackupId();
      String rootDir = im.getBackupRootDir();
      long startTs = im.getStartTs();
      long completeTs = im.getCompleteTs();
      List<HBaseProtos.TableName> tableListList = im.getTableListList();
      List<TableName> tableList = new ArrayList<>();
      for (HBaseProtos.TableName tn : tableListList) {
        tableList.add(ProtobufUtil.toTableName(tn));
      }

      List<BackupProtos.BackupImage> ancestorList = im.getAncestorsList();

      BackupType type = im.getBackupType() == BackupProtos.BackupType.FULL
        ? BackupType.FULL
        : BackupType.INCREMENTAL;

      BackupImage image = new BackupImage(backupId, type, rootDir, tableList, startTs, completeTs);
      for (BackupProtos.BackupImage img : ancestorList) {
        image.addAncestor(fromProto(img));
      }
      image.setIncrTimeRanges(loadIncrementalTimestampMap(im));
      return image;
    }

    /**
     * This method deliberately does not include the backup root dir on the produced proto. This is
     * because we don't want to persist the root dir on the backup itself, so that backups can still
     * be used after they have moved locations. A restore's operator will always provide the root
     * dir.
     */
    BackupProtos.BackupImage toProto() {
      BackupProtos.BackupImage.Builder builder = BackupProtos.BackupImage.newBuilder();
      builder.setBackupId(backupId);
      builder.setCompleteTs(completeTs);
      builder.setStartTs(startTs);
      if (type == BackupType.FULL) {
        builder.setBackupType(BackupProtos.BackupType.FULL);
      } else {
        builder.setBackupType(BackupProtos.BackupType.INCREMENTAL);
      }

      for (TableName name : tableList) {
        builder.addTableList(ProtobufUtil.toProtoTableName(name));
      }

      if (ancestors != null) {
        for (BackupImage im : ancestors) {
          builder.addAncestors(im.toProto());
        }
      }

      setIncrementalTimestampMap(builder);
      return builder.build();
    }

    private static Map<TableName, Map<String, Long>>
      loadIncrementalTimestampMap(BackupProtos.BackupImage proto) {
      List<BackupProtos.TableServerTimestamp> list = proto.getTstMapList();

      Map<TableName, Map<String, Long>> incrTimeRanges = new HashMap<>();

      if (list == null || list.size() == 0) {
        return incrTimeRanges;
      }

      for (BackupProtos.TableServerTimestamp tst : list) {
        TableName tn = ProtobufUtil.toTableName(tst.getTableName());
        Map<String, Long> map = incrTimeRanges.get(tn);
        if (map == null) {
          map = new HashMap<>();
          incrTimeRanges.put(tn, map);
        }
        List<BackupProtos.ServerTimestamp> listSt = tst.getServerTimestampList();
        for (BackupProtos.ServerTimestamp stm : listSt) {
          ServerName sn = ProtobufUtil.toServerName(stm.getServerName());
          map.put(sn.getHostname() + ":" + sn.getPort(), stm.getTimestamp());
        }
      }
      return incrTimeRanges;
    }

    private void setIncrementalTimestampMap(BackupProtos.BackupImage.Builder builder) {
      if (this.incrTimeRanges == null) {
        return;
      }
      for (Entry<TableName, Map<String, Long>> entry : this.incrTimeRanges.entrySet()) {
        TableName key = entry.getKey();
        Map<String, Long> value = entry.getValue();
        BackupProtos.TableServerTimestamp.Builder tstBuilder =
          BackupProtos.TableServerTimestamp.newBuilder();
        tstBuilder.setTableName(ProtobufUtil.toProtoTableName(key));

        for (Map.Entry<String, Long> entry2 : value.entrySet()) {
          String s = entry2.getKey();
          BackupProtos.ServerTimestamp.Builder stBuilder =
            BackupProtos.ServerTimestamp.newBuilder();
          HBaseProtos.ServerName.Builder snBuilder = HBaseProtos.ServerName.newBuilder();
          ServerName sn = ServerName.parseServerName(s);
          snBuilder.setHostName(sn.getHostname());
          snBuilder.setPort(sn.getPort());
          stBuilder.setServerName(snBuilder.build());
          stBuilder.setTimestamp(entry2.getValue());
          tstBuilder.addServerTimestamp(stBuilder.build());
        }
        builder.addTstMap(tstBuilder.build());
      }
    }

    public String getBackupId() {
      return backupId;
    }

    private void setBackupId(String backupId) {
      this.backupId = backupId;
    }

    public BackupType getType() {
      return type;
    }

    private void setType(BackupType type) {
      this.type = type;
    }

    public String getRootDir() {
      return rootDir;
    }

    private void setRootDir(String rootDir) {
      this.rootDir = rootDir;
    }

    public List<TableName> getTableNames() {
      return tableList;
    }

    private void setTableList(List<TableName> tableList) {
      this.tableList = tableList;
    }

    public long getStartTs() {
      return startTs;
    }

    private void setStartTs(long startTs) {
      this.startTs = startTs;
    }

    public long getCompleteTs() {
      return completeTs;
    }

    private void setCompleteTs(long completeTs) {
      this.completeTs = completeTs;
    }

    public ArrayList<BackupImage> getAncestors() {
      if (this.ancestors == null) {
        this.ancestors = new ArrayList<>();
      }
      return this.ancestors;
    }

    public void removeAncestors(List<String> backupIds) {
      List<BackupImage> toRemove = new ArrayList<>();
      for (BackupImage im : this.ancestors) {
        if (backupIds.contains(im.getBackupId())) {
          toRemove.add(im);
        }
      }
      this.ancestors.removeAll(toRemove);
    }

    private void addAncestor(BackupImage backupImage) {
      this.getAncestors().add(backupImage);
    }

    public boolean hasAncestor(String token) {
      for (BackupImage image : this.getAncestors()) {
        if (image.getBackupId().equals(token)) {
          return true;
        }
      }
      return false;
    }

    public boolean hasTable(TableName table) {
      return tableList.contains(table);
    }

    @Override
    public int compareTo(BackupImage other) {
      String thisBackupId = this.getBackupId();
      String otherBackupId = other.getBackupId();
      int index1 = thisBackupId.lastIndexOf("_");
      int index2 = otherBackupId.lastIndexOf("_");
      String name1 = thisBackupId.substring(0, index1);
      String name2 = otherBackupId.substring(0, index2);
      if (name1.equals(name2)) {
        Long thisTS = Long.valueOf(thisBackupId.substring(index1 + 1));
        Long otherTS = Long.valueOf(otherBackupId.substring(index2 + 1));
        return thisTS.compareTo(otherTS);
      } else {
        return name1.compareTo(name2);
      }
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof BackupImage) {
        return this.compareTo((BackupImage) obj) == 0;
      }
      return false;
    }

    @Override
    public int hashCode() {
      int hash = 33 * this.getBackupId().hashCode() + type.hashCode();
      hash = 33 * hash + rootDir.hashCode();
      hash = 33 * hash + Long.valueOf(startTs).hashCode();
      hash = 33 * hash + Long.valueOf(completeTs).hashCode();
      for (TableName table : tableList) {
        hash = 33 * hash + table.hashCode();
      }
      return hash;
    }

    public Map<TableName, Map<String, Long>> getIncrTimeRanges() {
      return incrTimeRanges;
    }

    private void setIncrTimeRanges(Map<TableName, Map<String, Long>> incrTimeRanges) {
      this.incrTimeRanges = incrTimeRanges;
    }
  }

  // backup image directory
  private BackupImage backupImage;

  /**
   * Construct manifest for a ongoing backup.
   * @param backup The ongoing backup info
   */
  public BackupManifest(BackupInfo backup) {
    BackupImage.Builder builder = BackupImage.newBuilder();
    this.backupImage = builder.withBackupId(backup.getBackupId()).withType(backup.getType())
      .withRootDir(backup.getBackupRootDir()).withTableList(backup.getTableNames())
      .withStartTime(backup.getStartTs()).withCompleteTime(backup.getCompleteTs()).build();
  }

  /**
   * Construct a table level manifest for a backup of the named table.
   * @param backup The ongoing backup session info
   */
  public BackupManifest(BackupInfo backup, TableName table) {
    List<TableName> tables = new ArrayList<TableName>();
    tables.add(table);
    BackupImage.Builder builder = BackupImage.newBuilder();
    this.backupImage = builder.withBackupId(backup.getBackupId()).withType(backup.getType())
      .withRootDir(backup.getBackupRootDir()).withTableList(tables)
      .withStartTime(backup.getStartTs()).withCompleteTime(backup.getCompleteTs()).build();
  }

  /**
   * Construct manifest from a backup directory.
   * @param conf       configuration
   * @param backupPath backup path
   * @throws IOException if constructing the manifest from the backup directory fails
   */
  public BackupManifest(Configuration conf, Path backupPath) throws IOException {
    this(backupPath.getFileSystem(conf), backupPath);
  }

  /**
   * Construct manifest from a backup directory.
   * @param fs         the FileSystem
   * @param backupPath backup path
   * @throws BackupException exception
   */
  public BackupManifest(FileSystem fs, Path backupPath) throws BackupException {
    if (LOG.isDebugEnabled()) {
      LOG.debug("Loading manifest from: " + backupPath.toString());
    }
    // The input backupDir may not exactly be the backup table dir.
    // It could be the backup log dir where there is also a manifest file stored.
    // This variable's purpose is to keep the correct and original location so
    // that we can store/persist it.
    try {
      FileStatus[] subFiles = BackupUtils.listStatus(fs, backupPath, null);
      if (subFiles == null) {
        String errorMsg = backupPath.toString() + " does not exist";
        LOG.error(errorMsg);
        throw new IOException(errorMsg);
      }
      for (FileStatus subFile : subFiles) {
        if (subFile.getPath().getName().equals(MANIFEST_FILE_NAME)) {
          // load and set manifest field from file content
          long len = subFile.getLen();
          byte[] pbBytes = new byte[(int) len];
          try (FSDataInputStream in = fs.open(subFile.getPath())) {
            in.readFully(pbBytes);
          } catch (IOException e) {
            throw new BackupException(e.getMessage());
          }
          BackupProtos.BackupImage proto = null;
          try {
            proto = BackupProtos.BackupImage.parseFrom(pbBytes);
          } catch (Exception e) {
            throw new BackupException(e);
          }
          this.backupImage = hydrateRootDir(BackupImage.fromProto(proto), backupPath);
          LOG.debug("Loaded manifest instance from manifest file: "
            + BackupUtils.getPath(subFile.getPath()));
          return;
        }
      }
      String errorMsg = "No manifest file found in: " + backupPath.toString();
      throw new IOException(errorMsg);
    } catch (IOException e) {
      throw new BackupException(e.getMessage());
    }
  }

  /* Visible for testing only */
  @RestrictedApi(explanation = "Should only be called internally or in tests", link = "",
      allowedOnPath = "(.*/src/test/.*|.*/org/apache/hadoop/hbase/backup/impl/BackupManifest.java)")
  public static BackupImage hydrateRootDir(BackupImage backupImage, Path backupPath)
    throws IOException {
    String providedRootDir =
      HBackupFileSystem.getRootDirFromBackupPath(backupPath, backupImage.backupId).toString();
    backupImage.setRootDir(providedRootDir);
    for (BackupImage ancestor : backupImage.getAncestors()) {
      ancestor.setRootDir(providedRootDir);
    }
    return backupImage;
  }

  public BackupType getType() {
    return backupImage.getType();
  }

  /**
   * Get the table set of this image.
   * @return The table set list
   */
  public List<TableName> getTableList() {
    return backupImage.getTableNames();
  }

  /**
   * Persist the manifest file.
   * @throws BackupException if an error occurred while storing the manifest file.
   */
  public void store(Configuration conf) throws BackupException {
    byte[] data = backupImage.toProto().toByteArray();
    // write the file, overwrite if already exist
    Path manifestFilePath =
      new Path(HBackupFileSystem.getBackupPath(backupImage.getRootDir(), backupImage.getBackupId()),
        MANIFEST_FILE_NAME);
    try (FSDataOutputStream out =
      manifestFilePath.getFileSystem(conf).create(manifestFilePath, true)) {
      out.write(data);
    } catch (IOException e) {
      throw new BackupException(e.getMessage());
    }

    LOG.info("Manifest file stored to " + manifestFilePath);
  }

  /**
   * Get this backup image.
   * @return the backup image.
   */
  public BackupImage getBackupImage() {
    return backupImage;
  }

  /**
   * Add dependent backup image for this backup.
   * @param image The direct dependent backup image
   */
  public void addDependentImage(BackupImage image) {
    this.backupImage.addAncestor(image);
  }

  /**
   * Set the incremental timestamp map directly.
   * @param incrTimestampMap timestamp map
   */
  public void setIncrTimestampMap(Map<TableName, Map<String, Long>> incrTimestampMap) {
    this.backupImage.setIncrTimeRanges(incrTimestampMap);
  }

  public Map<TableName, Map<String, Long>> getIncrTimestampMap() {
    return backupImage.getIncrTimeRanges();
  }

  /**
   * Get the image list of this backup for restore in time order.
   * @param reverse If true, then output in reverse order, otherwise in time order from old to new
   * @return the backup image list for restore in time order
   */
  public ArrayList<BackupImage> getRestoreDependentList(boolean reverse) {
    TreeMap<Long, BackupImage> restoreImages = new TreeMap<>();
    restoreImages.put(backupImage.startTs, backupImage);
    for (BackupImage image : backupImage.getAncestors()) {
      restoreImages.put(Long.valueOf(image.startTs), image);
    }
    return new ArrayList<>(
      reverse ? restoreImages.descendingMap().values() : restoreImages.values());
  }

  /**
   * Get the dependent image list for a specific table of this backup in time order from old to new
   * if want to restore to this backup image level.
   * @param table table
   * @return the backup image list for a table in time order
   */
  public ArrayList<BackupImage> getDependentListByTable(TableName table) {
    ArrayList<BackupImage> tableImageList = new ArrayList<>();
    ArrayList<BackupImage> imageList = getRestoreDependentList(true);
    for (BackupImage image : imageList) {
      if (image.hasTable(table)) {
        tableImageList.add(image);
        if (image.getType() == BackupType.FULL) {
          break;
        }
      }
    }
    Collections.reverse(tableImageList);
    return tableImageList;
  }

  public BackupInfo toBackupInfo() {
    BackupInfo info = new BackupInfo();
    info.setType(backupImage.getType());
    List<TableName> list = backupImage.getTableNames();
    TableName[] tables = new TableName[list.size()];
    info.addTables(list.toArray(tables));
    info.setBackupId(backupImage.getBackupId());
    info.setStartTs(backupImage.getStartTs());
    info.setBackupRootDir(backupImage.getRootDir());
    if (backupImage.getType() == BackupType.INCREMENTAL) {
      info.setHLogTargetDir(
        BackupUtils.getLogBackupDir(backupImage.getRootDir(), backupImage.getBackupId()));
    }
    return info;
  }
}
