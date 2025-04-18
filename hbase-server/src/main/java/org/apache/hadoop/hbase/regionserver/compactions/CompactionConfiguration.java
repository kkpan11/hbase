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
package org.apache.hadoop.hbase.regionserver.compactions;

import static org.apache.hadoop.hbase.regionserver.StoreFileWriter.shouldEnableHistoricalCompactionFiles;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.conf.ConfigKey;
import org.apache.hadoop.hbase.regionserver.StoreConfigInformation;
import org.apache.hadoop.util.StringUtils;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * Compaction configuration for a particular instance of HStore. Takes into account both global
 * settings and ones set on the column family/store. Control knobs for default compaction algorithm:
 * </p>
 * <p>
 * maxCompactSize - upper bound on file size to be included in minor compactions minCompactSize -
 * lower bound below which compaction is selected without ratio test minFilesToCompact - lower bound
 * on number of files in any minor compaction maxFilesToCompact - upper bound on number of files in
 * any minor compaction compactionRatio - Ratio used for compaction minLocalityToForceCompact -
 * Locality threshold for a store file to major compact (HBASE-11195)
 * </p>
 * Set parameter as "hbase.hstore.compaction.&lt;attribute&gt;"
 */

@InterfaceAudience.Private
public class CompactionConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger(CompactionConfiguration.class);

  public static final String HBASE_HSTORE_COMPACTION_RATIO_KEY =
    ConfigKey.FLOAT("hbase.hstore.compaction.ratio");
  public static final String HBASE_HSTORE_COMPACTION_RATIO_OFFPEAK_KEY =
    ConfigKey.FLOAT("hbase.hstore.compaction.ratio.offpeak");
  public static final String HBASE_HSTORE_COMPACTION_MIN_KEY_OLD =
    ConfigKey.INT("hbase.hstore.compactionThreshold");
  public static final String HBASE_HSTORE_COMPACTION_MIN_KEY =
    ConfigKey.INT("hbase.hstore.compaction.min");
  public static final String HBASE_HSTORE_COMPACTION_MIN_SIZE_KEY =
    ConfigKey.LONG("hbase.hstore.compaction.min.size");
  public static final String HBASE_HSTORE_COMPACTION_MAX_KEY =
    ConfigKey.INT("hbase.hstore.compaction.max");
  public static final String HBASE_HSTORE_COMPACTION_MAX_SIZE_KEY =
    ConfigKey.LONG("hbase.hstore.compaction.max.size");
  public static final String HBASE_HSTORE_COMPACTION_MAX_SIZE_OFFPEAK_KEY =
    ConfigKey.LONG("hbase.hstore.compaction.max.size.offpeak");
  public static final String HBASE_HSTORE_OFFPEAK_END_HOUR =
    ConfigKey.INT("hbase.offpeak.end.hour");
  public static final String HBASE_HSTORE_OFFPEAK_START_HOUR =
    ConfigKey.INT("hbase.offpeak.start.hour");
  public static final String HBASE_HSTORE_MIN_LOCALITY_TO_SKIP_MAJOR_COMPACT =
    ConfigKey.FLOAT("hbase.hstore.min.locality.to.skip.major.compact");

  public static final String HBASE_HFILE_COMPACTION_DISCHARGER_THREAD_COUNT =
    ConfigKey.INT("hbase.hfile.compaction.discharger.thread.count");

  /*
   * The epoch time length for the windows we no longer compact
   */
  public static final String DATE_TIERED_MAX_AGE_MILLIS_KEY =
    ConfigKey.LONG("hbase.hstore.compaction.date.tiered.max.storefile.age.millis");
  public static final String DATE_TIERED_INCOMING_WINDOW_MIN_KEY =
    ConfigKey.INT("hbase.hstore.compaction.date.tiered.incoming.window.min");
  public static final String COMPACTION_POLICY_CLASS_FOR_DATE_TIERED_WINDOWS_KEY = ConfigKey.CLASS(
    "hbase.hstore.compaction.date.tiered.window.policy.class", RatioBasedCompactionPolicy.class);
  public static final String DATE_TIERED_SINGLE_OUTPUT_FOR_MINOR_COMPACTION_KEY =
    ConfigKey.BOOLEAN("hbase.hstore.compaction.date.tiered.single.output.for.minor.compaction");

  private static final Class<
    ? extends RatioBasedCompactionPolicy> DEFAULT_COMPACTION_POLICY_CLASS_FOR_DATE_TIERED_WINDOWS =
      ExploringCompactionPolicy.class;

  public static final String DATE_TIERED_COMPACTION_WINDOW_FACTORY_CLASS_KEY = ConfigKey.CLASS(
    "hbase.hstore.compaction.date.tiered.window.factory.class", CompactionWindowFactory.class);

  private static final Class<
    ? extends CompactionWindowFactory> DEFAULT_DATE_TIERED_COMPACTION_WINDOW_FACTORY_CLASS =
      ExponentialCompactionWindowFactory.class;

  public static final String DATE_TIERED_STORAGE_POLICY_ENABLE_KEY =
    "hbase.hstore.compaction.date.tiered.storage.policy.enable";
  public static final String DATE_TIERED_HOT_WINDOW_AGE_MILLIS_KEY =
    ConfigKey.LONG("hbase.hstore.compaction.date.tiered.hot.window.age.millis");
  public static final String DATE_TIERED_HOT_WINDOW_STORAGE_POLICY_KEY =
    "hbase.hstore.compaction.date.tiered.hot.window.storage.policy";
  public static final String DATE_TIERED_WARM_WINDOW_AGE_MILLIS_KEY =
    ConfigKey.LONG("hbase.hstore.compaction.date.tiered.warm.window.age.millis");
  public static final String DATE_TIERED_WARM_WINDOW_STORAGE_POLICY_KEY =
    "hbase.hstore.compaction.date.tiered.warm.window.storage.policy";
  /** Windows older than warm age belong to COLD_WINDOW **/
  public static final String DATE_TIERED_COLD_WINDOW_STORAGE_POLICY_KEY =
    "hbase.hstore.compaction.date.tiered.cold.window.storage.policy";

  Configuration conf;
  StoreConfigInformation storeConfigInfo;

  private final double offPeakCompactionRatio;
  /** Since all these properties can change online, they are volatile **/
  private final long maxCompactSize;
  private final long offPeakMaxCompactSize;
  private final long minCompactSize;
  /** This one can be update **/
  private int minFilesToCompact;
  private final int maxFilesToCompact;
  private final double compactionRatio;
  private final long throttlePoint;
  private final long majorCompactionPeriod;
  private final float majorCompactionJitter;
  private final float minLocalityToForceCompact;
  private final long dateTieredMaxStoreFileAgeMillis;
  private final int dateTieredIncomingWindowMin;
  private final String compactionPolicyForDateTieredWindow;
  private final boolean dateTieredSingleOutputForMinorCompaction;
  private final String dateTieredCompactionWindowFactory;
  private final boolean dateTieredStoragePolicyEnable;
  private long hotWindowAgeMillis;
  private long warmWindowAgeMillis;
  private String hotWindowStoragePolicy;
  private String warmWindowStoragePolicy;
  private String coldWindowStoragePolicy;

  CompactionConfiguration(Configuration conf, StoreConfigInformation storeConfigInfo) {
    this.conf = conf;
    this.storeConfigInfo = storeConfigInfo;

    maxCompactSize = conf.getLong(HBASE_HSTORE_COMPACTION_MAX_SIZE_KEY, Long.MAX_VALUE);
    offPeakMaxCompactSize =
      conf.getLong(HBASE_HSTORE_COMPACTION_MAX_SIZE_OFFPEAK_KEY, maxCompactSize);
    minCompactSize =
      conf.getLong(HBASE_HSTORE_COMPACTION_MIN_SIZE_KEY, storeConfigInfo.getMemStoreFlushSize());
    minFilesToCompact = Math.max(2, conf.getInt(HBASE_HSTORE_COMPACTION_MIN_KEY,
      conf.getInt(HBASE_HSTORE_COMPACTION_MIN_KEY_OLD, 3)));
    if (shouldEnableHistoricalCompactionFiles(conf)) {
      // If historical file writing is enabled, we bump up the min value by one as DualFileWriter
      // compacts files into two files, live and historical, instead of one. This also eliminates
      // infinite re-compaction when the min value is set to 2
      minFilesToCompact += 1;
    }
    maxFilesToCompact = conf.getInt(HBASE_HSTORE_COMPACTION_MAX_KEY, 10);
    compactionRatio = conf.getFloat(HBASE_HSTORE_COMPACTION_RATIO_KEY, 1.2F);
    offPeakCompactionRatio = conf.getFloat(HBASE_HSTORE_COMPACTION_RATIO_OFFPEAK_KEY, 5.0F);

    throttlePoint = conf.getLong("hbase.regionserver.thread.compaction.throttle",
      2 * maxFilesToCompact * storeConfigInfo.getMemStoreFlushSize());
    majorCompactionPeriod =
      conf.getLong(HConstants.MAJOR_COMPACTION_PERIOD, HConstants.DEFAULT_MAJOR_COMPACTION_PERIOD);
    majorCompactionJitter =
      conf.getFloat(HConstants.MAJOR_COMPACTION_JITTER, HConstants.DEFAULT_MAJOR_COMPACTION_JITTER);
    minLocalityToForceCompact = conf.getFloat(HBASE_HSTORE_MIN_LOCALITY_TO_SKIP_MAJOR_COMPACT, 0f);

    dateTieredMaxStoreFileAgeMillis = conf.getLong(DATE_TIERED_MAX_AGE_MILLIS_KEY, Long.MAX_VALUE);
    dateTieredIncomingWindowMin = conf.getInt(DATE_TIERED_INCOMING_WINDOW_MIN_KEY, 6);
    compactionPolicyForDateTieredWindow =
      conf.get(COMPACTION_POLICY_CLASS_FOR_DATE_TIERED_WINDOWS_KEY,
        DEFAULT_COMPACTION_POLICY_CLASS_FOR_DATE_TIERED_WINDOWS.getName());
    dateTieredSingleOutputForMinorCompaction =
      conf.getBoolean(DATE_TIERED_SINGLE_OUTPUT_FOR_MINOR_COMPACTION_KEY, true);
    this.dateTieredCompactionWindowFactory =
      conf.get(DATE_TIERED_COMPACTION_WINDOW_FACTORY_CLASS_KEY,
        DEFAULT_DATE_TIERED_COMPACTION_WINDOW_FACTORY_CLASS.getName());
    // for Heterogeneous Storage
    dateTieredStoragePolicyEnable = conf.getBoolean(DATE_TIERED_STORAGE_POLICY_ENABLE_KEY, false);
    hotWindowAgeMillis = conf.getLong(DATE_TIERED_HOT_WINDOW_AGE_MILLIS_KEY, 86400000L);
    hotWindowStoragePolicy = conf.get(DATE_TIERED_HOT_WINDOW_STORAGE_POLICY_KEY, "ALL_SSD");
    warmWindowAgeMillis = conf.getLong(DATE_TIERED_WARM_WINDOW_AGE_MILLIS_KEY, 604800000L);
    warmWindowStoragePolicy = conf.get(DATE_TIERED_WARM_WINDOW_STORAGE_POLICY_KEY, "ONE_SSD");
    coldWindowStoragePolicy = conf.get(DATE_TIERED_COLD_WINDOW_STORAGE_POLICY_KEY, "HOT");
    LOG.info(toString());
  }

  @Override
  public String toString() {
    return String.format(
      "size [minCompactSize:%s, maxCompactSize:%s, offPeakMaxCompactSize:%s);"
        + " files [minFilesToCompact:%d, maxFilesToCompact:%d);"
        + " ratio %f; off-peak ratio %f; throttle point %d;"
        + " major period %d, major jitter %f, min locality to compact %f;"
        + " tiered compaction: max_age %d, incoming window min %d,"
        + " compaction policy for tiered window %s, single output for minor %b,"
        + " compaction window factory %s," + " region %s columnFamilyName %s",
      StringUtils.byteDesc(minCompactSize), StringUtils.byteDesc(maxCompactSize),
      StringUtils.byteDesc(offPeakMaxCompactSize), minFilesToCompact, maxFilesToCompact,
      compactionRatio, offPeakCompactionRatio, throttlePoint, majorCompactionPeriod,
      majorCompactionJitter, minLocalityToForceCompact, dateTieredMaxStoreFileAgeMillis,
      dateTieredIncomingWindowMin, compactionPolicyForDateTieredWindow,
      dateTieredSingleOutputForMinorCompaction, dateTieredCompactionWindowFactory,
      RegionInfo.prettyPrint(storeConfigInfo.getRegionInfo().getEncodedName()),
      storeConfigInfo.getColumnFamilyName());
  }

  /** Returns lower bound below which compaction is selected without ratio test */
  public long getMinCompactSize() {
    return minCompactSize;
  }

  /** Returns upper bound on file size to be included in minor compactions */
  public long getMaxCompactSize() {
    return maxCompactSize;
  }

  /** Returns lower bound on number of files to be included in minor compactions */
  public int getMinFilesToCompact() {
    return minFilesToCompact;
  }

  /**
   * Set lower bound on number of files to be included in minor compactions
   * @param threshold value to set to
   */
  public void setMinFilesToCompact(int threshold) {
    minFilesToCompact = threshold;
  }

  /** Returns upper bound on number of files to be included in minor compactions */
  public int getMaxFilesToCompact() {
    return maxFilesToCompact;
  }

  /** Returns Ratio used for compaction */
  public double getCompactionRatio() {
    return compactionRatio;
  }

  /** Returns Off peak Ratio used for compaction */
  public double getCompactionRatioOffPeak() {
    return offPeakCompactionRatio;
  }

  /** Returns ThrottlePoint used for classifying small and large compactions */
  public long getThrottlePoint() {
    return throttlePoint;
  }

  /**
   * @return Major compaction period from compaction. Major compactions are selected periodically
   *         according to this parameter plus jitter
   */
  public long getMajorCompactionPeriod() {
    return majorCompactionPeriod;
  }

  /**
   * @return Major the jitter fraction, the fraction within which the major compaction period is
   *         randomly chosen from the majorCompactionPeriod in each store.
   */
  public float getMajorCompactionJitter() {
    return majorCompactionJitter;
  }

  /**
   * @return Block locality ratio, the ratio at which we will include old regions with a single
   *         store file for major compaction. Used to improve block locality for regions that
   *         haven't had writes in a while but are still being read.
   */
  public float getMinLocalityToForceCompact() {
    return minLocalityToForceCompact;
  }

  public long getOffPeakMaxCompactSize() {
    return offPeakMaxCompactSize;
  }

  public long getMaxCompactSize(boolean mayUseOffpeak) {
    if (mayUseOffpeak) {
      return getOffPeakMaxCompactSize();
    } else {
      return getMaxCompactSize();
    }
  }

  public long getDateTieredMaxStoreFileAgeMillis() {
    return dateTieredMaxStoreFileAgeMillis;
  }

  public int getDateTieredIncomingWindowMin() {
    return dateTieredIncomingWindowMin;
  }

  public String getCompactionPolicyForDateTieredWindow() {
    return compactionPolicyForDateTieredWindow;
  }

  public boolean useDateTieredSingleOutputForMinorCompaction() {
    return dateTieredSingleOutputForMinorCompaction;
  }

  public String getDateTieredCompactionWindowFactory() {
    return dateTieredCompactionWindowFactory;
  }

  public boolean isDateTieredStoragePolicyEnable() {
    return dateTieredStoragePolicyEnable;
  }

  public long getHotWindowAgeMillis() {
    return hotWindowAgeMillis;
  }

  public long getWarmWindowAgeMillis() {
    return warmWindowAgeMillis;
  }

  public String getHotWindowStoragePolicy() {
    return hotWindowStoragePolicy.trim().toUpperCase();
  }

  public String getWarmWindowStoragePolicy() {
    return warmWindowStoragePolicy.trim().toUpperCase();
  }

  public String getColdWindowStoragePolicy() {
    return coldWindowStoragePolicy.trim().toUpperCase();
  }
}
