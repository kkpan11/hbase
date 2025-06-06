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
package org.apache.hadoop.hbase;

import com.codahale.metrics.Histogram;
import com.codahale.metrics.UniformReservoir;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.math.MathContext;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.Queue;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Append;
import org.apache.hadoop.hbase.client.AsyncConnection;
import org.apache.hadoop.hbase.client.AsyncTable;
import org.apache.hadoop.hbase.client.BufferedMutator;
import org.apache.hadoop.hbase.client.BufferedMutatorParams;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptorBuilder;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.Consistency;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Durability;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Increment;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.client.RegionInfoBuilder;
import org.apache.hadoop.hbase.client.RegionLocator;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.RowMutations;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.client.TableDescriptor;
import org.apache.hadoop.hbase.client.TableDescriptorBuilder;
import org.apache.hadoop.hbase.client.metrics.ScanMetrics;
import org.apache.hadoop.hbase.filter.BinaryComparator;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.FilterAllFilter;
import org.apache.hadoop.hbase.filter.FilterList;
import org.apache.hadoop.hbase.filter.PageFilter;
import org.apache.hadoop.hbase.filter.SingleColumnValueFilter;
import org.apache.hadoop.hbase.filter.WhileMatchFilter;
import org.apache.hadoop.hbase.io.compress.Compression;
import org.apache.hadoop.hbase.io.encoding.DataBlockEncoding;
import org.apache.hadoop.hbase.mapreduce.TableMapReduceUtil;
import org.apache.hadoop.hbase.regionserver.BloomType;
import org.apache.hadoop.hbase.regionserver.CompactingMemStore;
import org.apache.hadoop.hbase.trace.TraceUtil;
import org.apache.hadoop.hbase.util.ByteArrayHashKey;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.GsonUtil;
import org.apache.hadoop.hbase.util.Hash;
import org.apache.hadoop.hbase.util.MurmurHash;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hbase.util.RandomDistribution;
import org.apache.hadoop.hbase.util.YammerHistogramUtils;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.NLineInputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.mapreduce.lib.reduce.LongSumReducer;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hbase.thirdparty.com.google.common.base.Splitter;
import org.apache.hbase.thirdparty.com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.hbase.thirdparty.com.google.gson.Gson;

/**
 * Script used evaluating HBase performance and scalability. Runs a HBase client that steps through
 * one of a set of hardcoded tests or 'experiments' (e.g. a random reads test, a random writes test,
 * etc.). Pass on the command-line which test to run and how many clients are participating in this
 * experiment. Run {@code PerformanceEvaluation --help} to obtain usage.
 * <p>
 * This class sets up and runs the evaluation programs described in Section 7, <i>Performance
 * Evaluation</i>, of the <a href="http://labs.google.com/papers/bigtable.html">Bigtable</a> paper,
 * pages 8-10.
 * <p>
 * By default, runs as a mapreduce job where each mapper runs a single test client. Can also run as
 * a non-mapreduce, multithreaded application by specifying {@code --nomapred}. Each client does
 * about 1GB of data, unless specified otherwise.
 */
@InterfaceAudience.LimitedPrivate(HBaseInterfaceAudience.TOOLS)
public class PerformanceEvaluation extends Configured implements Tool {
  static final String RANDOM_SEEK_SCAN = "randomSeekScan";
  static final String RANDOM_READ = "randomRead";
  static final String PE_COMMAND_SHORTNAME = "pe";
  private static final Logger LOG = LoggerFactory.getLogger(PerformanceEvaluation.class.getName());
  private static final Gson GSON = GsonUtil.createGson().create();

  public static final String TABLE_NAME = "TestTable";
  public static final String FAMILY_NAME_BASE = "info";
  public static final byte[] FAMILY_ZERO = Bytes.toBytes("info0");
  public static final byte[] COLUMN_ZERO = Bytes.toBytes("" + 0);
  public static final int DEFAULT_VALUE_LENGTH = 1000;
  public static final int ROW_LENGTH = 26;

  private static final int ONE_GB = 1024 * 1024 * 1000;
  private static final int DEFAULT_ROWS_PER_GB = ONE_GB / DEFAULT_VALUE_LENGTH;
  // TODO : should we make this configurable
  private static final int TAG_LENGTH = 256;
  private static final DecimalFormat FMT = new DecimalFormat("0.##");
  private static final MathContext CXT = MathContext.DECIMAL64;
  private static final BigDecimal MS_PER_SEC = BigDecimal.valueOf(1000);
  private static final BigDecimal BYTES_PER_MB = BigDecimal.valueOf(1024 * 1024);
  private static final TestOptions DEFAULT_OPTS = new TestOptions();

  private static Map<String, CmdDescriptor> COMMANDS = new TreeMap<>();
  private static final Path PERF_EVAL_DIR = new Path("performance_evaluation");

  static {
    addCommandDescriptor(AsyncRandomReadTest.class, "asyncRandomRead",
      "Run async random read test");
    addCommandDescriptor(AsyncRandomWriteTest.class, "asyncRandomWrite",
      "Run async random write test");
    addCommandDescriptor(AsyncSequentialReadTest.class, "asyncSequentialRead",
      "Run async sequential read test");
    addCommandDescriptor(AsyncSequentialWriteTest.class, "asyncSequentialWrite",
      "Run async sequential write test");
    addCommandDescriptor(AsyncScanTest.class, "asyncScan", "Run async scan test (read every row)");
    addCommandDescriptor(RandomReadTest.class, RANDOM_READ, "Run random read test");
    addCommandDescriptor(MetaRandomReadTest.class, "metaRandomRead", "Run getRegionLocation test");
    addCommandDescriptor(RandomSeekScanTest.class, RANDOM_SEEK_SCAN,
      "Run random seek and scan 100 test");
    addCommandDescriptor(RandomScanWithRange10Test.class, "scanRange10",
      "Run random seek scan with both start and stop row (max 10 rows)");
    addCommandDescriptor(RandomScanWithRange100Test.class, "scanRange100",
      "Run random seek scan with both start and stop row (max 100 rows)");
    addCommandDescriptor(RandomScanWithRange1000Test.class, "scanRange1000",
      "Run random seek scan with both start and stop row (max 1000 rows)");
    addCommandDescriptor(RandomScanWithRange10000Test.class, "scanRange10000",
      "Run random seek scan with both start and stop row (max 10000 rows)");
    addCommandDescriptor(RandomWriteTest.class, "randomWrite", "Run random write test");
    addCommandDescriptor(RandomDeleteTest.class, "randomDelete", "Run random delete test");
    addCommandDescriptor(SequentialReadTest.class, "sequentialRead", "Run sequential read test");
    addCommandDescriptor(SequentialWriteTest.class, "sequentialWrite", "Run sequential write test");
    addCommandDescriptor(SequentialDeleteTest.class, "sequentialDelete",
      "Run sequential delete test");
    addCommandDescriptor(MetaWriteTest.class, "metaWrite",
      "Populate meta table;used with 1 thread; to be cleaned up by cleanMeta");
    addCommandDescriptor(ScanTest.class, "scan", "Run scan test (read every row)");
    addCommandDescriptor(ReverseScanTest.class, "reverseScan",
      "Run reverse scan test (read every row)");
    addCommandDescriptor(FilteredScanTest.class, "filterScan",
      "Run scan test using a filter to find a specific row based on it's value "
        + "(make sure to use --rows=20)");
    addCommandDescriptor(IncrementTest.class, "increment",
      "Increment on each row; clients overlap on keyspace so some concurrent operations");
    addCommandDescriptor(AppendTest.class, "append",
      "Append on each row; clients overlap on keyspace so some concurrent operations");
    addCommandDescriptor(CheckAndMutateTest.class, "checkAndMutate",
      "CheckAndMutate on each row; clients overlap on keyspace so some concurrent operations");
    addCommandDescriptor(CheckAndPutTest.class, "checkAndPut",
      "CheckAndPut on each row; clients overlap on keyspace so some concurrent operations");
    addCommandDescriptor(CheckAndDeleteTest.class, "checkAndDelete",
      "CheckAndDelete on each row; clients overlap on keyspace so some concurrent operations");
    addCommandDescriptor(CleanMetaTest.class, "cleanMeta",
      "Remove fake region entries on meta table inserted by metaWrite; used with 1 thread");
  }

  /**
   * Enum for map metrics. Keep it out here rather than inside in the Map inner-class so we can find
   * associated properties.
   */
  protected static enum Counter {
    /** elapsed time */
    ELAPSED_TIME,
    /** number of rows */
    ROWS
  }

  protected static class RunResult implements Comparable<RunResult> {
    public RunResult(long duration, Histogram hist) {
      this.duration = duration;
      this.hist = hist;
      numbOfReplyOverThreshold = 0;
      numOfReplyFromReplica = 0;
    }

    public RunResult(long duration, long numbOfReplyOverThreshold, long numOfReplyFromReplica,
      Histogram hist) {
      this.duration = duration;
      this.hist = hist;
      this.numbOfReplyOverThreshold = numbOfReplyOverThreshold;
      this.numOfReplyFromReplica = numOfReplyFromReplica;
    }

    public final long duration;
    public final Histogram hist;
    public final long numbOfReplyOverThreshold;
    public final long numOfReplyFromReplica;

    @Override
    public String toString() {
      return Long.toString(duration);
    }

    @Override
    public int compareTo(RunResult o) {
      return Long.compare(this.duration, o.duration);
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (obj == null || getClass() != obj.getClass()) {
        return false;
      }
      return this.compareTo((RunResult) obj) == 0;
    }

    @Override
    public int hashCode() {
      return Long.hashCode(duration);
    }
  }

  /**
   * Constructor
   * @param conf Configuration object
   */
  public PerformanceEvaluation(final Configuration conf) {
    super(conf);
  }

  protected static void addCommandDescriptor(Class<? extends TestBase> cmdClass, String name,
    String description) {
    CmdDescriptor cmdDescriptor = new CmdDescriptor(cmdClass, name, description);
    COMMANDS.put(name, cmdDescriptor);
  }

  /**
   * Implementations can have their status set.
   */
  interface Status {
    /**
     * Sets status
     * @param msg status message
     */
    void setStatus(final String msg) throws IOException;
  }

  /**
   * MapReduce job that runs a performance evaluation client in each map task.
   */
  public static class EvaluationMapTask
    extends Mapper<LongWritable, Text, LongWritable, LongWritable> {

    /** configuration parameter name that contains the command */
    public final static String CMD_KEY = "EvaluationMapTask.command";
    /** configuration parameter name that contains the PE impl */
    public static final String PE_KEY = "EvaluationMapTask.performanceEvalImpl";

    private Class<? extends Test> cmd;

    @Override
    protected void setup(Context context) throws IOException, InterruptedException {
      this.cmd = forName(context.getConfiguration().get(CMD_KEY), Test.class);

      // this is required so that extensions of PE are instantiated within the
      // map reduce task...
      Class<? extends PerformanceEvaluation> peClass =
        forName(context.getConfiguration().get(PE_KEY), PerformanceEvaluation.class);
      try {
        peClass.getConstructor(Configuration.class).newInstance(context.getConfiguration());
      } catch (Exception e) {
        throw new IllegalStateException("Could not instantiate PE instance", e);
      }
    }

    private <Type> Class<? extends Type> forName(String className, Class<Type> type) {
      try {
        return Class.forName(className).asSubclass(type);
      } catch (ClassNotFoundException e) {
        throw new IllegalStateException("Could not find class for name: " + className, e);
      }
    }

    @Override
    protected void map(LongWritable key, Text value, final Context context)
      throws IOException, InterruptedException {

      Status status = new Status() {
        @Override
        public void setStatus(String msg) {
          context.setStatus(msg);
        }
      };

      TestOptions opts = GSON.fromJson(value.toString(), TestOptions.class);
      Configuration conf = HBaseConfiguration.create(context.getConfiguration());
      final Connection con = ConnectionFactory.createConnection(conf);
      AsyncConnection asyncCon = null;
      try {
        asyncCon = ConnectionFactory.createAsyncConnection(conf).get();
      } catch (ExecutionException e) {
        throw new IOException(e);
      }

      // Evaluation task
      RunResult result =
        PerformanceEvaluation.runOneClient(this.cmd, conf, con, asyncCon, opts, status);
      // Collect how much time the thing took. Report as map output and
      // to the ELAPSED_TIME counter.
      context.getCounter(Counter.ELAPSED_TIME).increment(result.duration);
      context.getCounter(Counter.ROWS).increment(opts.perClientRunRows);
      context.write(new LongWritable(opts.startRow), new LongWritable(result.duration));
      context.progress();
    }
  }

  /*
   * If table does not already exist, create. Also create a table when {@code opts.presplitRegions}
   * is specified or when the existing table's region replica count doesn't match {@code
   * opts.replicas}.
   */
  static boolean checkTable(Admin admin, TestOptions opts) throws IOException {
    final TableName tableName = TableName.valueOf(opts.tableName);
    final boolean exists = admin.tableExists(tableName);
    final boolean isReadCmd = opts.cmdName.toLowerCase(Locale.ROOT).contains("read")
      || opts.cmdName.toLowerCase(Locale.ROOT).contains("scan");
    final boolean isDeleteCmd = opts.cmdName.toLowerCase(Locale.ROOT).contains("delete");
    final boolean needsData = isReadCmd || isDeleteCmd;
    if (!exists && needsData) {
      throw new IllegalStateException(
        "Must specify an existing table for read/delete commands. Run a write command first.");
    }
    TableDescriptor desc = exists ? admin.getDescriptor(TableName.valueOf(opts.tableName)) : null;
    final byte[][] splits = getSplits(opts);

    // recreate the table when user has requested presplit or when existing
    // {RegionSplitPolicy,replica count} does not match requested, or when the
    // number of column families does not match requested.
    final boolean regionCountChanged =
      exists && opts.presplitRegions != DEFAULT_OPTS.presplitRegions
        && opts.presplitRegions != admin.getRegions(tableName).size();
    final boolean splitPolicyChanged =
      exists && !StringUtils.equals(desc.getRegionSplitPolicyClassName(), opts.splitPolicy);
    final boolean regionReplicationChanged = exists && desc.getRegionReplication() != opts.replicas;
    final boolean columnFamilyCountChanged = exists && desc.getColumnFamilyCount() != opts.families;

    boolean needsDelete = regionCountChanged || splitPolicyChanged || regionReplicationChanged
      || columnFamilyCountChanged;

    if (needsDelete) {
      final List<String> errors = new ArrayList<>();
      if (columnFamilyCountChanged) {
        final String error = String.format("--families=%d, but found %d column families",
          opts.families, desc.getColumnFamilyCount());
        if (needsData) {
          // We can't proceed the test in this case
          throw new IllegalStateException(
            "Cannot proceed the test. Run a write command first: " + error);
        }
        errors.add(error);
      }
      if (regionCountChanged) {
        errors.add(String.format("--presplit=%d, but found %d regions", opts.presplitRegions,
          admin.getRegions(tableName).size()));
      }
      if (splitPolicyChanged) {
        errors.add(String.format("--splitPolicy=%s, but current policy is %s", opts.splitPolicy,
          desc.getRegionSplitPolicyClassName()));
      }
      if (regionReplicationChanged) {
        errors.add(String.format("--replicas=%d, but found %d replicas", opts.replicas,
          desc.getRegionReplication()));
      }
      final String reason =
        errors.stream().map(s -> '[' + s + ']').collect(Collectors.joining(", "));

      if (needsData) {
        LOG.warn("Unexpected or incorrect options provided for {}. "
          + "Please verify whether the detected inconsistencies are expected or ignorable: {}. "
          + "The test will proceed, but the results may not be reliable.", opts.cmdName, reason);
        needsDelete = false;
      } else {
        LOG.info("Table will be recreated: " + reason);
      }
    }

    // remove an existing table
    if (needsDelete) {
      if (admin.isTableEnabled(tableName)) {
        admin.disableTable(tableName);
      }
      admin.deleteTable(tableName);
    }

    // table creation is necessary
    if (!exists || needsDelete) {
      desc = getTableDescriptor(opts);
      if (splits != null) {
        if (LOG.isDebugEnabled()) {
          for (int i = 0; i < splits.length; i++) {
            LOG.debug(" split " + i + ": " + Bytes.toStringBinary(splits[i]));
          }
        }
      }
      if (splits != null) {
        admin.createTable(desc, splits);
      } else {
        admin.createTable(desc);
      }
      LOG.info("Table " + desc + " created");
    }
    return admin.tableExists(tableName);
  }

  /**
   * Create an HTableDescriptor from provided TestOptions.
   */
  protected static TableDescriptor getTableDescriptor(TestOptions opts) {
    TableDescriptorBuilder builder =
      TableDescriptorBuilder.newBuilder(TableName.valueOf(opts.tableName));

    for (int family = 0; family < opts.families; family++) {
      byte[] familyName = Bytes.toBytes(FAMILY_NAME_BASE + family);
      ColumnFamilyDescriptorBuilder cfBuilder =
        ColumnFamilyDescriptorBuilder.newBuilder(familyName);
      cfBuilder.setDataBlockEncoding(opts.blockEncoding);
      cfBuilder.setCompressionType(opts.compression);
      cfBuilder.setEncryptionType(opts.encryption);
      cfBuilder.setBloomFilterType(opts.bloomType);
      cfBuilder.setBlocksize(opts.blockSize);
      if (opts.inMemoryCF) {
        cfBuilder.setInMemory(true);
      }
      cfBuilder.setInMemoryCompaction(opts.inMemoryCompaction);
      builder.setColumnFamily(cfBuilder.build());
    }
    if (opts.replicas != DEFAULT_OPTS.replicas) {
      builder.setRegionReplication(opts.replicas);
    }
    if (opts.splitPolicy != null && !opts.splitPolicy.equals(DEFAULT_OPTS.splitPolicy)) {
      builder.setRegionSplitPolicyClassName(opts.splitPolicy);
    }
    return builder.build();
  }

  /**
   * generates splits based on total number of rows and specified split regions
   */
  protected static byte[][] getSplits(TestOptions opts) {
    if (opts.presplitRegions == DEFAULT_OPTS.presplitRegions) return null;

    int numSplitPoints = opts.presplitRegions - 1;
    byte[][] splits = new byte[numSplitPoints][];
    long jump = opts.totalRows / opts.presplitRegions;
    for (int i = 0; i < numSplitPoints; i++) {
      long rowkey = jump * (1 + i);
      splits[i] = format(rowkey);
    }
    return splits;
  }

  static void setupConnectionCount(final TestOptions opts) {
    if (opts.oneCon) {
      opts.connCount = 1;
    } else {
      if (opts.connCount == -1) {
        // set to thread number if connCount is not set
        opts.connCount = opts.numClientThreads;
      }
    }
  }

  /*
   * Run all clients in this vm each to its own thread.
   */
  static RunResult[] doLocalClients(final TestOptions opts, final Configuration conf)
    throws IOException, InterruptedException, ExecutionException {
    final Class<? extends TestBase> cmd = determineCommandClass(opts.cmdName);
    assert cmd != null;
    @SuppressWarnings("unchecked")
    Future<RunResult>[] threads = new Future[opts.numClientThreads];
    RunResult[] results = new RunResult[opts.numClientThreads];
    ExecutorService pool = Executors.newFixedThreadPool(opts.numClientThreads,
      new ThreadFactoryBuilder().setNameFormat("TestClient-%s").build());
    setupConnectionCount(opts);
    final Connection[] cons = new Connection[opts.connCount];
    final AsyncConnection[] asyncCons = new AsyncConnection[opts.connCount];
    for (int i = 0; i < opts.connCount; i++) {
      cons[i] = ConnectionFactory.createConnection(conf);
      asyncCons[i] = ConnectionFactory.createAsyncConnection(conf).get();
    }
    LOG
      .info("Created " + opts.connCount + " connections for " + opts.numClientThreads + " threads");
    for (int i = 0; i < threads.length; i++) {
      final int index = i;
      threads[i] = pool.submit(new Callable<RunResult>() {
        @Override
        public RunResult call() throws Exception {
          TestOptions threadOpts = new TestOptions(opts);
          final Connection con = cons[index % cons.length];
          final AsyncConnection asyncCon = asyncCons[index % asyncCons.length];
          if (threadOpts.startRow == 0) threadOpts.startRow = index * threadOpts.perClientRunRows;
          RunResult run = runOneClient(cmd, conf, con, asyncCon, threadOpts, new Status() {
            @Override
            public void setStatus(final String msg) throws IOException {
              LOG.info(msg);
            }
          });
          LOG.info("Finished " + Thread.currentThread().getName() + " in " + run.duration
            + "ms over " + threadOpts.perClientRunRows + " rows");
          if (opts.latencyThreshold > 0) {
            LOG.info("Number of replies over latency threshold " + opts.latencyThreshold
              + "(ms) is " + run.numbOfReplyOverThreshold);
          }
          return run;
        }
      });
    }
    pool.shutdown();

    for (int i = 0; i < threads.length; i++) {
      try {
        results[i] = threads[i].get();
      } catch (ExecutionException e) {
        throw new IOException(e.getCause());
      }
    }
    final String test = cmd.getSimpleName();
    LOG.info("[" + test + "] Summary of timings (ms): " + Arrays.toString(results));
    Arrays.sort(results);
    long total = 0;
    float avgLatency = 0;
    float avgTPS = 0;
    long replicaWins = 0;
    for (RunResult result : results) {
      total += result.duration;
      avgLatency += result.hist.getSnapshot().getMean();
      avgTPS += opts.perClientRunRows * 1.0f / result.duration;
      replicaWins += result.numOfReplyFromReplica;
    }
    avgTPS *= 1000; // ms to second
    avgLatency = avgLatency / results.length;
    LOG.info("[" + test + " duration ]" + "\tMin: " + results[0] + "ms" + "\tMax: "
      + results[results.length - 1] + "ms" + "\tAvg: " + (total / results.length) + "ms");
    LOG.info("[ Avg latency (us)]\t" + Math.round(avgLatency));
    LOG.info("[ Avg TPS/QPS]\t" + Math.round(avgTPS) + "\t row per second");
    if (opts.replicas > 1) {
      LOG.info("[results from replica regions] " + replicaWins);
    }

    for (int i = 0; i < opts.connCount; i++) {
      cons[i].close();
      asyncCons[i].close();
    }

    return results;
  }

  /*
   * Run a mapreduce job. Run as many maps as asked-for clients. Before we start up the job, write
   * out an input file with instruction per client regards which row they are to start on.
   * @param cmd Command to run.
   */
  static Job doMapReduce(TestOptions opts, final Configuration conf)
    throws IOException, InterruptedException, ClassNotFoundException {
    final Class<? extends TestBase> cmd = determineCommandClass(opts.cmdName);
    assert cmd != null;
    Path inputDir = writeInputFile(conf, opts);
    conf.set(EvaluationMapTask.CMD_KEY, cmd.getName());
    conf.set(EvaluationMapTask.PE_KEY, PerformanceEvaluation.class.getName());
    Job job = Job.getInstance(conf);
    job.setJarByClass(PerformanceEvaluation.class);
    job.setJobName("HBase Performance Evaluation - " + opts.cmdName);

    job.setInputFormatClass(NLineInputFormat.class);
    NLineInputFormat.setInputPaths(job, inputDir);
    // this is default, but be explicit about it just in case.
    NLineInputFormat.setNumLinesPerSplit(job, 1);

    job.setOutputKeyClass(LongWritable.class);
    job.setOutputValueClass(LongWritable.class);

    job.setMapperClass(EvaluationMapTask.class);
    job.setReducerClass(LongSumReducer.class);

    job.setNumReduceTasks(1);

    job.setOutputFormatClass(TextOutputFormat.class);
    TextOutputFormat.setOutputPath(job, new Path(inputDir.getParent(), "outputs"));

    TableMapReduceUtil.addDependencyJars(job);
    TableMapReduceUtil.addDependencyJarsForClasses(job.getConfiguration(), Histogram.class, // yammer
                                                                                            // metrics
      Gson.class, // gson
      FilterAllFilter.class // hbase-server tests jar
    );

    TableMapReduceUtil.initCredentials(job);

    job.waitForCompletion(true);
    return job;
  }

  /**
   * Each client has one mapper to do the work, and client do the resulting count in a map task.
   */

  static String JOB_INPUT_FILENAME = "input.txt";

  /*
   * Write input file of offsets-per-client for the mapreduce job.
   * @param c Configuration
   * @return Directory that contains file written whose name is JOB_INPUT_FILENAME
   */
  static Path writeInputFile(final Configuration c, final TestOptions opts) throws IOException {
    return writeInputFile(c, opts, new Path("."));
  }

  static Path writeInputFile(final Configuration c, final TestOptions opts, final Path basedir)
    throws IOException {
    SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMddHHmmss");
    Path jobdir = new Path(new Path(basedir, PERF_EVAL_DIR), formatter.format(new Date()));
    Path inputDir = new Path(jobdir, "inputs");

    FileSystem fs = FileSystem.get(c);
    fs.mkdirs(inputDir);

    Path inputFile = new Path(inputDir, JOB_INPUT_FILENAME);
    PrintStream out = new PrintStream(fs.create(inputFile));
    // Make input random.
    Map<Integer, String> m = new TreeMap<>();
    Hash h = MurmurHash.getInstance();
    long perClientRows = (opts.totalRows / opts.numClientThreads);
    try {
      for (int j = 0; j < opts.numClientThreads; j++) {
        TestOptions next = new TestOptions(opts);
        next.startRow = j * perClientRows;
        next.perClientRunRows = perClientRows;
        String s = GSON.toJson(next);
        LOG.info("Client=" + j + ", input=" + s);
        byte[] b = Bytes.toBytes(s);
        int hash = h.hash(new ByteArrayHashKey(b, 0, b.length), -1);
        m.put(hash, s);
      }
      for (Map.Entry<Integer, String> e : m.entrySet()) {
        out.println(e.getValue());
      }
    } finally {
      out.close();
    }
    return inputDir;
  }

  /**
   * Describes a command.
   */
  static class CmdDescriptor {
    private Class<? extends TestBase> cmdClass;
    private String name;
    private String description;

    CmdDescriptor(Class<? extends TestBase> cmdClass, String name, String description) {
      this.cmdClass = cmdClass;
      this.name = name;
      this.description = description;
    }

    public Class<? extends TestBase> getCmdClass() {
      return cmdClass;
    }

    public String getName() {
      return name;
    }

    public String getDescription() {
      return description;
    }
  }

  /**
   * Wraps up options passed to {@link org.apache.hadoop.hbase.PerformanceEvaluation}. This makes
   * tracking all these arguments a little easier. NOTE: ADDING AN OPTION, you need to add a data
   * member, a getter/setter (to make JSON serialization of this TestOptions class behave), and you
   * need to add to the clone constructor below copying your new option from the 'that' to the
   * 'this'. Look for 'clone' below.
   */
  static class TestOptions {
    String cmdName = null;
    boolean nomapred = false;
    boolean filterAll = false;
    long startRow = 0;
    float size = 1.0f;
    long perClientRunRows = DEFAULT_ROWS_PER_GB;
    int numClientThreads = 1;
    long totalRows = DEFAULT_ROWS_PER_GB;
    int measureAfter = 0;
    float sampleRate = 1.0f;
    /**
     * @deprecated Useless after switching to OpenTelemetry
     */
    @Deprecated
    double traceRate = 0.0;
    String tableName = TABLE_NAME;
    boolean flushCommits = true;
    boolean writeToWAL = true;
    boolean autoFlush = false;
    boolean oneCon = false;
    int connCount = -1; // wil decide the actual num later
    boolean useTags = false;
    int noOfTags = 1;
    boolean reportLatency = false;
    int multiGet = 0;
    int multiPut = 0;
    int randomSleep = 0;
    boolean inMemoryCF = false;
    int presplitRegions = 0;
    int replicas = TableDescriptorBuilder.DEFAULT_REGION_REPLICATION;
    String splitPolicy = null;
    Compression.Algorithm compression = Compression.Algorithm.NONE;
    String encryption = null;
    BloomType bloomType = BloomType.ROW;
    int blockSize = HConstants.DEFAULT_BLOCKSIZE;
    DataBlockEncoding blockEncoding = DataBlockEncoding.NONE;
    boolean valueRandom = false;
    boolean valueZipf = false;
    int valueSize = DEFAULT_VALUE_LENGTH;
    long period = (this.perClientRunRows / 10) == 0 ? perClientRunRows : perClientRunRows / 10;
    int cycles = 1;
    int columns = 1;
    int families = 1;
    int caching = 30;
    int latencyThreshold = 0; // in millsecond
    boolean addColumns = true;
    MemoryCompactionPolicy inMemoryCompaction =
      MemoryCompactionPolicy.valueOf(CompactingMemStore.COMPACTING_MEMSTORE_TYPE_DEFAULT);
    boolean asyncPrefetch = false;
    boolean cacheBlocks = true;
    Scan.ReadType scanReadType = Scan.ReadType.DEFAULT;
    long bufferSize = 2l * 1024l * 1024l;
    Properties commandProperties;

    public TestOptions() {
    }

    /**
     * Clone constructor.
     * @param that Object to copy from.
     */
    public TestOptions(TestOptions that) {
      this.cmdName = that.cmdName;
      this.cycles = that.cycles;
      this.nomapred = that.nomapred;
      this.startRow = that.startRow;
      this.size = that.size;
      this.perClientRunRows = that.perClientRunRows;
      this.numClientThreads = that.numClientThreads;
      this.totalRows = that.totalRows;
      this.sampleRate = that.sampleRate;
      this.traceRate = that.traceRate;
      this.tableName = that.tableName;
      this.flushCommits = that.flushCommits;
      this.writeToWAL = that.writeToWAL;
      this.autoFlush = that.autoFlush;
      this.oneCon = that.oneCon;
      this.connCount = that.connCount;
      this.useTags = that.useTags;
      this.noOfTags = that.noOfTags;
      this.reportLatency = that.reportLatency;
      this.latencyThreshold = that.latencyThreshold;
      this.multiGet = that.multiGet;
      this.multiPut = that.multiPut;
      this.inMemoryCF = that.inMemoryCF;
      this.presplitRegions = that.presplitRegions;
      this.replicas = that.replicas;
      this.splitPolicy = that.splitPolicy;
      this.compression = that.compression;
      this.encryption = that.encryption;
      this.blockEncoding = that.blockEncoding;
      this.filterAll = that.filterAll;
      this.bloomType = that.bloomType;
      this.blockSize = that.blockSize;
      this.valueRandom = that.valueRandom;
      this.valueZipf = that.valueZipf;
      this.valueSize = that.valueSize;
      this.period = that.period;
      this.randomSleep = that.randomSleep;
      this.measureAfter = that.measureAfter;
      this.addColumns = that.addColumns;
      this.columns = that.columns;
      this.families = that.families;
      this.caching = that.caching;
      this.inMemoryCompaction = that.inMemoryCompaction;
      this.asyncPrefetch = that.asyncPrefetch;
      this.cacheBlocks = that.cacheBlocks;
      this.scanReadType = that.scanReadType;
      this.bufferSize = that.bufferSize;
      this.commandProperties = that.commandProperties;
    }

    public Properties getCommandProperties() {
      return commandProperties;
    }

    public int getCaching() {
      return this.caching;
    }

    public void setCaching(final int caching) {
      this.caching = caching;
    }

    public int getColumns() {
      return this.columns;
    }

    public void setColumns(final int columns) {
      this.columns = columns;
    }

    public int getFamilies() {
      return this.families;
    }

    public void setFamilies(final int families) {
      this.families = families;
    }

    public int getCycles() {
      return this.cycles;
    }

    public void setCycles(final int cycles) {
      this.cycles = cycles;
    }

    public boolean isValueZipf() {
      return valueZipf;
    }

    public void setValueZipf(boolean valueZipf) {
      this.valueZipf = valueZipf;
    }

    public String getCmdName() {
      return cmdName;
    }

    public void setCmdName(String cmdName) {
      this.cmdName = cmdName;
    }

    public int getRandomSleep() {
      return randomSleep;
    }

    public void setRandomSleep(int randomSleep) {
      this.randomSleep = randomSleep;
    }

    public int getReplicas() {
      return replicas;
    }

    public void setReplicas(int replicas) {
      this.replicas = replicas;
    }

    public String getSplitPolicy() {
      return splitPolicy;
    }

    public void setSplitPolicy(String splitPolicy) {
      this.splitPolicy = splitPolicy;
    }

    public void setNomapred(boolean nomapred) {
      this.nomapred = nomapred;
    }

    public void setFilterAll(boolean filterAll) {
      this.filterAll = filterAll;
    }

    public void setStartRow(long startRow) {
      this.startRow = startRow;
    }

    public void setSize(float size) {
      this.size = size;
    }

    public void setPerClientRunRows(int perClientRunRows) {
      this.perClientRunRows = perClientRunRows;
    }

    public void setNumClientThreads(int numClientThreads) {
      this.numClientThreads = numClientThreads;
    }

    public void setTotalRows(long totalRows) {
      this.totalRows = totalRows;
    }

    public void setSampleRate(float sampleRate) {
      this.sampleRate = sampleRate;
    }

    public void setTraceRate(double traceRate) {
      this.traceRate = traceRate;
    }

    public void setTableName(String tableName) {
      this.tableName = tableName;
    }

    public void setFlushCommits(boolean flushCommits) {
      this.flushCommits = flushCommits;
    }

    public void setWriteToWAL(boolean writeToWAL) {
      this.writeToWAL = writeToWAL;
    }

    public void setAutoFlush(boolean autoFlush) {
      this.autoFlush = autoFlush;
    }

    public void setOneCon(boolean oneCon) {
      this.oneCon = oneCon;
    }

    public int getConnCount() {
      return connCount;
    }

    public void setConnCount(int connCount) {
      this.connCount = connCount;
    }

    public void setUseTags(boolean useTags) {
      this.useTags = useTags;
    }

    public void setNoOfTags(int noOfTags) {
      this.noOfTags = noOfTags;
    }

    public void setReportLatency(boolean reportLatency) {
      this.reportLatency = reportLatency;
    }

    public void setMultiGet(int multiGet) {
      this.multiGet = multiGet;
    }

    public void setMultiPut(int multiPut) {
      this.multiPut = multiPut;
    }

    public void setInMemoryCF(boolean inMemoryCF) {
      this.inMemoryCF = inMemoryCF;
    }

    public void setPresplitRegions(int presplitRegions) {
      this.presplitRegions = presplitRegions;
    }

    public void setCompression(Compression.Algorithm compression) {
      this.compression = compression;
    }

    public void setEncryption(String encryption) {
      this.encryption = encryption;
    }

    public void setBloomType(BloomType bloomType) {
      this.bloomType = bloomType;
    }

    public void setBlockSize(int blockSize) {
      this.blockSize = blockSize;
    }

    public void setBlockEncoding(DataBlockEncoding blockEncoding) {
      this.blockEncoding = blockEncoding;
    }

    public void setValueRandom(boolean valueRandom) {
      this.valueRandom = valueRandom;
    }

    public void setValueSize(int valueSize) {
      this.valueSize = valueSize;
    }

    public void setBufferSize(long bufferSize) {
      this.bufferSize = bufferSize;
    }

    public void setPeriod(int period) {
      this.period = period;
    }

    public boolean isNomapred() {
      return nomapred;
    }

    public boolean isFilterAll() {
      return filterAll;
    }

    public long getStartRow() {
      return startRow;
    }

    public float getSize() {
      return size;
    }

    public long getPerClientRunRows() {
      return perClientRunRows;
    }

    public int getNumClientThreads() {
      return numClientThreads;
    }

    public long getTotalRows() {
      return totalRows;
    }

    public float getSampleRate() {
      return sampleRate;
    }

    public double getTraceRate() {
      return traceRate;
    }

    public String getTableName() {
      return tableName;
    }

    public boolean isFlushCommits() {
      return flushCommits;
    }

    public boolean isWriteToWAL() {
      return writeToWAL;
    }

    public boolean isAutoFlush() {
      return autoFlush;
    }

    public boolean isUseTags() {
      return useTags;
    }

    public int getNoOfTags() {
      return noOfTags;
    }

    public boolean isReportLatency() {
      return reportLatency;
    }

    public int getMultiGet() {
      return multiGet;
    }

    public int getMultiPut() {
      return multiPut;
    }

    public boolean isInMemoryCF() {
      return inMemoryCF;
    }

    public int getPresplitRegions() {
      return presplitRegions;
    }

    public Compression.Algorithm getCompression() {
      return compression;
    }

    public String getEncryption() {
      return encryption;
    }

    public DataBlockEncoding getBlockEncoding() {
      return blockEncoding;
    }

    public boolean isValueRandom() {
      return valueRandom;
    }

    public int getValueSize() {
      return valueSize;
    }

    public long getPeriod() {
      return period;
    }

    public BloomType getBloomType() {
      return bloomType;
    }

    public int getBlockSize() {
      return blockSize;
    }

    public boolean isOneCon() {
      return oneCon;
    }

    public int getMeasureAfter() {
      return measureAfter;
    }

    public void setMeasureAfter(int measureAfter) {
      this.measureAfter = measureAfter;
    }

    public boolean getAddColumns() {
      return addColumns;
    }

    public void setAddColumns(boolean addColumns) {
      this.addColumns = addColumns;
    }

    public void setInMemoryCompaction(MemoryCompactionPolicy inMemoryCompaction) {
      this.inMemoryCompaction = inMemoryCompaction;
    }

    public MemoryCompactionPolicy getInMemoryCompaction() {
      return this.inMemoryCompaction;
    }

    public long getBufferSize() {
      return this.bufferSize;
    }
  }

  /*
   * A test. Subclass to particularize what happens per row.
   */
  static abstract class TestBase {
    private final long everyN;

    protected final Configuration conf;
    protected final TestOptions opts;

    protected final Status status;

    private String testName;
    protected Histogram latencyHistogram;
    private Histogram replicaLatencyHistogram;
    private Histogram valueSizeHistogram;
    private Histogram rpcCallsHistogram;
    private Histogram remoteRpcCallsHistogram;
    private Histogram millisBetweenNextHistogram;
    private Histogram regionsScannedHistogram;
    private Histogram bytesInResultsHistogram;
    private Histogram bytesInRemoteResultsHistogram;
    private RandomDistribution.Zipf zipf;
    private long numOfReplyOverLatencyThreshold = 0;
    private long numOfReplyFromReplica = 0;

    /**
     * Note that all subclasses of this class must provide a public constructor that has the exact
     * same list of arguments.
     */
    TestBase(final Configuration conf, final TestOptions options, final Status status) {
      this.conf = conf;
      this.opts = options;
      this.status = status;
      this.testName = this.getClass().getSimpleName();
      everyN = (long) (1 / opts.sampleRate);
      if (options.isValueZipf()) {
        this.zipf =
          new RandomDistribution.Zipf(ThreadLocalRandom.current(), 1, options.getValueSize(), 1.2);
      }
      LOG.info("Sampling 1 every " + everyN + " out of " + opts.perClientRunRows + " total rows.");
    }

    int getValueLength() {
      if (this.opts.isValueRandom()) {
        return ThreadLocalRandom.current().nextInt(opts.valueSize);
      } else if (this.opts.isValueZipf()) {
        return Math.abs(this.zipf.nextInt());
      } else {
        return opts.valueSize;
      }
    }

    void updateValueSize(final Result[] rs) throws IOException {
      updateValueSize(rs, 0);
    }

    void updateValueSize(final Result[] rs, final long latency) throws IOException {
      if (rs == null || (latency == 0)) return;
      for (Result r : rs)
        updateValueSize(r, latency);
    }

    void updateValueSize(final Result r) throws IOException {
      updateValueSize(r, 0);
    }

    void updateValueSize(final Result r, final long latency) throws IOException {
      if (r == null || (latency == 0)) return;
      int size = 0;
      // update replicaHistogram
      if (r.isStale()) {
        replicaLatencyHistogram.update(latency / 1000);
        numOfReplyFromReplica++;
      }
      if (!isRandomValueSize()) return;

      for (CellScanner scanner = r.cellScanner(); scanner.advance();) {
        size += scanner.current().getValueLength();
      }
      updateValueSize(size);
    }

    void updateValueSize(final int valueSize) {
      if (!isRandomValueSize()) return;
      this.valueSizeHistogram.update(valueSize);
    }

    void updateScanMetrics(final ScanMetrics metrics) {
      if (metrics == null) return;
      Map<String, Long> metricsMap = metrics.getMetricsMap();
      Long rpcCalls = metricsMap.get(ScanMetrics.RPC_CALLS_METRIC_NAME);
      if (rpcCalls != null) {
        this.rpcCallsHistogram.update(rpcCalls.longValue());
      }
      Long remoteRpcCalls = metricsMap.get(ScanMetrics.REMOTE_RPC_CALLS_METRIC_NAME);
      if (remoteRpcCalls != null) {
        this.remoteRpcCallsHistogram.update(remoteRpcCalls.longValue());
      }
      Long millisBetweenNext = metricsMap.get(ScanMetrics.MILLIS_BETWEEN_NEXTS_METRIC_NAME);
      if (millisBetweenNext != null) {
        this.millisBetweenNextHistogram.update(millisBetweenNext.longValue());
      }
      Long regionsScanned = metricsMap.get(ScanMetrics.REGIONS_SCANNED_METRIC_NAME);
      if (regionsScanned != null) {
        this.regionsScannedHistogram.update(regionsScanned.longValue());
      }
      Long bytesInResults = metricsMap.get(ScanMetrics.BYTES_IN_RESULTS_METRIC_NAME);
      if (bytesInResults != null && bytesInResults.longValue() > 0) {
        this.bytesInResultsHistogram.update(bytesInResults.longValue());
      }
      Long bytesInRemoteResults = metricsMap.get(ScanMetrics.BYTES_IN_REMOTE_RESULTS_METRIC_NAME);
      if (bytesInRemoteResults != null && bytesInRemoteResults.longValue() > 0) {
        this.bytesInRemoteResultsHistogram.update(bytesInRemoteResults.longValue());
      }
    }

    String generateStatus(final long sr, final long i, final long lr) {
      return "row [start=" + sr + ", current=" + i + ", last=" + lr + "], latency ["
        + getShortLatencyReport() + "]"
        + (!isRandomValueSize() ? "" : ", value size [" + getShortValueSizeReport() + "]");
    }

    boolean isRandomValueSize() {
      return opts.valueRandom;
    }

    protected long getReportingPeriod() {
      return opts.period;
    }

    /**
     * Populated by testTakedown. Only implemented by RandomReadTest at the moment.
     */
    public Histogram getLatencyHistogram() {
      return latencyHistogram;
    }

    void testSetup() throws IOException {
      // test metrics
      latencyHistogram = YammerHistogramUtils.newHistogram(new UniformReservoir(1024 * 500));
      // If it is a replica test, set up histogram for replica.
      if (opts.replicas > 1) {
        replicaLatencyHistogram =
          YammerHistogramUtils.newHistogram(new UniformReservoir(1024 * 500));
      }
      valueSizeHistogram = YammerHistogramUtils.newHistogram(new UniformReservoir(1024 * 500));
      // scan metrics
      rpcCallsHistogram = YammerHistogramUtils.newHistogram(new UniformReservoir(1024 * 500));
      remoteRpcCallsHistogram = YammerHistogramUtils.newHistogram(new UniformReservoir(1024 * 500));
      millisBetweenNextHistogram =
        YammerHistogramUtils.newHistogram(new UniformReservoir(1024 * 500));
      regionsScannedHistogram = YammerHistogramUtils.newHistogram(new UniformReservoir(1024 * 500));
      bytesInResultsHistogram = YammerHistogramUtils.newHistogram(new UniformReservoir(1024 * 500));
      bytesInRemoteResultsHistogram =
        YammerHistogramUtils.newHistogram(new UniformReservoir(1024 * 500));

      onStartup();
    }

    abstract void onStartup() throws IOException;

    void testTakedown() throws IOException {
      onTakedown();
      // Print all stats for this thread continuously.
      // Synchronize on Test.class so different threads don't intermingle the
      // output. We can't use 'this' here because each thread has its own instance of Test class.
      synchronized (Test.class) {
        status.setStatus("Test : " + testName + ", Thread : " + Thread.currentThread().getName());
        status
          .setStatus("Latency (us) : " + YammerHistogramUtils.getHistogramReport(latencyHistogram));
        if (opts.replicas > 1) {
          status.setStatus("Latency (us) from Replica Regions: "
            + YammerHistogramUtils.getHistogramReport(replicaLatencyHistogram));
        }
        status.setStatus("Num measures (latency) : " + latencyHistogram.getCount());
        status.setStatus(YammerHistogramUtils.getPrettyHistogramReport(latencyHistogram));
        if (valueSizeHistogram.getCount() > 0) {
          status.setStatus(
            "ValueSize (bytes) : " + YammerHistogramUtils.getHistogramReport(valueSizeHistogram));
          status.setStatus("Num measures (ValueSize): " + valueSizeHistogram.getCount());
          status.setStatus(YammerHistogramUtils.getPrettyHistogramReport(valueSizeHistogram));
        } else {
          status.setStatus("No valueSize statistics available");
        }
        if (rpcCallsHistogram.getCount() > 0) {
          status.setStatus(
            "rpcCalls (count): " + YammerHistogramUtils.getHistogramReport(rpcCallsHistogram));
        }
        if (remoteRpcCallsHistogram.getCount() > 0) {
          status.setStatus("remoteRpcCalls (count): "
            + YammerHistogramUtils.getHistogramReport(remoteRpcCallsHistogram));
        }
        if (millisBetweenNextHistogram.getCount() > 0) {
          status.setStatus("millisBetweenNext (latency): "
            + YammerHistogramUtils.getHistogramReport(millisBetweenNextHistogram));
        }
        if (regionsScannedHistogram.getCount() > 0) {
          status.setStatus("regionsScanned (count): "
            + YammerHistogramUtils.getHistogramReport(regionsScannedHistogram));
        }
        if (bytesInResultsHistogram.getCount() > 0) {
          status.setStatus("bytesInResults (size): "
            + YammerHistogramUtils.getHistogramReport(bytesInResultsHistogram));
        }
        if (bytesInRemoteResultsHistogram.getCount() > 0) {
          status.setStatus("bytesInRemoteResults (size): "
            + YammerHistogramUtils.getHistogramReport(bytesInRemoteResultsHistogram));
        }
      }
    }

    abstract void onTakedown() throws IOException;

    /*
     * Run test
     * @return Elapsed time.
     */
    long test() throws IOException, InterruptedException {
      testSetup();
      LOG.info("Timed test starting in thread " + Thread.currentThread().getName());
      final long startTime = System.nanoTime();
      try {
        testTimed();
      } finally {
        testTakedown();
      }
      return (System.nanoTime() - startTime) / 1000000;
    }

    long getStartRow() {
      return opts.startRow;
    }

    long getLastRow() {
      return getStartRow() + opts.perClientRunRows;
    }

    /**
     * Provides an extension point for tests that don't want a per row invocation.
     */
    void testTimed() throws IOException, InterruptedException {
      long startRow = getStartRow();
      long lastRow = getLastRow();
      // Report on completion of 1/10th of total.
      for (int ii = 0; ii < opts.cycles; ii++) {
        if (opts.cycles > 1) LOG.info("Cycle=" + ii + " of " + opts.cycles);
        for (long i = startRow; i < lastRow; i++) {
          if (i % everyN != 0) continue;
          long startTime = System.nanoTime();
          boolean requestSent = false;
          Span span = TraceUtil.getGlobalTracer().spanBuilder("test row").startSpan();
          try (Scope scope = span.makeCurrent()) {
            requestSent = testRow(i, startTime);
          } finally {
            span.end();
          }
          if ((i - startRow) > opts.measureAfter) {
            // If multiget or multiput is enabled, say set to 10, testRow() returns immediately
            // first 9 times and sends the actual get request in the 10th iteration.
            // We should only set latency when actual request is sent because otherwise
            // it turns out to be 0.
            if (requestSent) {
              long latency = (System.nanoTime() - startTime) / 1000;
              latencyHistogram.update(latency);
              if ((opts.latencyThreshold > 0) && (latency / 1000 >= opts.latencyThreshold)) {
                numOfReplyOverLatencyThreshold++;
              }
            }
            if (status != null && i > 0 && (i % getReportingPeriod()) == 0) {
              status.setStatus(generateStatus(startRow, i, lastRow));
            }
          }
        }
      }
    }

    /** Returns Subset of the histograms' calculation. */
    public String getShortLatencyReport() {
      return YammerHistogramUtils.getShortHistogramReport(this.latencyHistogram);
    }

    /** Returns Subset of the histograms' calculation. */
    public String getShortValueSizeReport() {
      return YammerHistogramUtils.getShortHistogramReport(this.valueSizeHistogram);
    }

    /**
     * Test for individual row.
     * @param i Row index.
     * @return true if the row was sent to server and need to record metrics. False if not, multiGet
     *         and multiPut e.g., the rows are sent to server only if enough gets/puts are gathered.
     */
    abstract boolean testRow(final long i, final long startTime)
      throws IOException, InterruptedException;
  }

  static abstract class Test extends TestBase {
    protected Connection connection;

    Test(final Connection con, final TestOptions options, final Status status) {
      super(con == null ? HBaseConfiguration.create() : con.getConfiguration(), options, status);
      this.connection = con;
    }
  }

  static abstract class AsyncTest extends TestBase {
    protected AsyncConnection connection;

    AsyncTest(final AsyncConnection con, final TestOptions options, final Status status) {
      super(con == null ? HBaseConfiguration.create() : con.getConfiguration(), options, status);
      this.connection = con;
    }
  }

  static abstract class TableTest extends Test {
    protected Table table;

    TableTest(Connection con, TestOptions options, Status status) {
      super(con, options, status);
    }

    @Override
    void onStartup() throws IOException {
      this.table = connection.getTable(TableName.valueOf(opts.tableName));
    }

    @Override
    void onTakedown() throws IOException {
      table.close();
    }
  }

  /*
   * Parent class for all meta tests: MetaWriteTest, MetaRandomReadTest and CleanMetaTest
   */
  static abstract class MetaTest extends TableTest {
    protected int keyLength;

    MetaTest(Connection con, TestOptions options, Status status) {
      super(con, options, status);
      keyLength = Long.toString(opts.perClientRunRows).length();
    }

    @Override
    void onTakedown() throws IOException {
      // No clean up
    }

    /*
     * Generates Lexicographically ascending strings
     */
    protected byte[] getSplitKey(final long i) {
      return Bytes.toBytes(String.format("%0" + keyLength + "d", i));
    }

  }

  static abstract class AsyncTableTest extends AsyncTest {
    protected AsyncTable<?> table;

    AsyncTableTest(AsyncConnection con, TestOptions options, Status status) {
      super(con, options, status);
    }

    @Override
    void onStartup() throws IOException {
      this.table = connection.getTable(TableName.valueOf(opts.tableName));
    }

    @Override
    void onTakedown() throws IOException {
    }
  }

  static class AsyncRandomReadTest extends AsyncTableTest {
    private final Consistency consistency;
    private ArrayList<Get> gets;

    AsyncRandomReadTest(AsyncConnection con, TestOptions options, Status status) {
      super(con, options, status);
      consistency = options.replicas == DEFAULT_OPTS.replicas ? null : Consistency.TIMELINE;
      if (opts.multiGet > 0) {
        LOG.info("MultiGet enabled. Sending GETs in batches of " + opts.multiGet + ".");
        this.gets = new ArrayList<>(opts.multiGet);
      }
    }

    @Override
    boolean testRow(final long i, final long startTime) throws IOException, InterruptedException {
      if (opts.randomSleep > 0) {
        Thread.sleep(ThreadLocalRandom.current().nextInt(opts.randomSleep));
      }
      Get get = new Get(getRandomRow(opts.totalRows));
      for (int family = 0; family < opts.families; family++) {
        byte[] familyName = Bytes.toBytes(FAMILY_NAME_BASE + family);
        if (opts.addColumns) {
          for (int column = 0; column < opts.columns; column++) {
            byte[] qualifier = column == 0 ? COLUMN_ZERO : Bytes.toBytes("" + column);
            get.addColumn(familyName, qualifier);
          }
        } else {
          get.addFamily(familyName);
        }
      }
      if (opts.filterAll) {
        get.setFilter(new FilterAllFilter());
      }
      get.setConsistency(consistency);
      if (LOG.isTraceEnabled()) LOG.trace(get.toString());
      try {
        if (opts.multiGet > 0) {
          this.gets.add(get);
          if (this.gets.size() == opts.multiGet) {
            Result[] rs =
              this.table.get(this.gets).stream().map(f -> propagate(f::get)).toArray(Result[]::new);
            updateValueSize(rs);
            this.gets.clear();
          } else {
            return false;
          }
        } else {
          updateValueSize(this.table.get(get).get());
        }
      } catch (ExecutionException e) {
        throw new IOException(e);
      }
      return true;
    }

    public static RuntimeException runtime(Throwable e) {
      if (e instanceof RuntimeException) {
        return (RuntimeException) e;
      }
      return new RuntimeException(e);
    }

    public static <V> V propagate(Callable<V> callable) {
      try {
        return callable.call();
      } catch (Exception e) {
        throw runtime(e);
      }
    }

    @Override
    protected long getReportingPeriod() {
      long period = opts.perClientRunRows / 10;
      return period == 0 ? opts.perClientRunRows : period;
    }

    @Override
    protected void testTakedown() throws IOException {
      if (this.gets != null && this.gets.size() > 0) {
        this.table.get(gets);
        this.gets.clear();
      }
      super.testTakedown();
    }
  }

  static class AsyncRandomWriteTest extends AsyncSequentialWriteTest {

    AsyncRandomWriteTest(AsyncConnection con, TestOptions options, Status status) {
      super(con, options, status);
    }

    @Override
    protected byte[] generateRow(final long i) {
      return getRandomRow(opts.totalRows);
    }
  }

  static class AsyncScanTest extends AsyncTableTest {
    private ResultScanner testScanner;
    private AsyncTable<?> asyncTable;

    AsyncScanTest(AsyncConnection con, TestOptions options, Status status) {
      super(con, options, status);
    }

    @Override
    void onStartup() throws IOException {
      this.asyncTable = connection.getTable(TableName.valueOf(opts.tableName),
        Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()));
    }

    @Override
    void testTakedown() throws IOException {
      if (this.testScanner != null) {
        updateScanMetrics(this.testScanner.getScanMetrics());
        this.testScanner.close();
      }
      super.testTakedown();
    }

    @Override
    boolean testRow(final long i, final long startTime) throws IOException {
      if (this.testScanner == null) {
        Scan scan = new Scan().withStartRow(format(opts.startRow)).setCaching(opts.caching)
          .setCacheBlocks(opts.cacheBlocks).setAsyncPrefetch(opts.asyncPrefetch)
          .setReadType(opts.scanReadType).setScanMetricsEnabled(true);
        for (int family = 0; family < opts.families; family++) {
          byte[] familyName = Bytes.toBytes(FAMILY_NAME_BASE + family);
          if (opts.addColumns) {
            for (int column = 0; column < opts.columns; column++) {
              byte[] qualifier = column == 0 ? COLUMN_ZERO : Bytes.toBytes("" + column);
              scan.addColumn(familyName, qualifier);
            }
          } else {
            scan.addFamily(familyName);
          }
        }
        if (opts.filterAll) {
          scan.setFilter(new FilterAllFilter());
        }
        this.testScanner = asyncTable.getScanner(scan);
      }
      Result r = testScanner.next();
      updateValueSize(r);
      return true;
    }
  }

  static class AsyncSequentialReadTest extends AsyncTableTest {
    AsyncSequentialReadTest(AsyncConnection con, TestOptions options, Status status) {
      super(con, options, status);
    }

    @Override
    boolean testRow(final long i, final long startTime) throws IOException, InterruptedException {
      Get get = new Get(format(i));
      for (int family = 0; family < opts.families; family++) {
        byte[] familyName = Bytes.toBytes(FAMILY_NAME_BASE + family);
        if (opts.addColumns) {
          for (int column = 0; column < opts.columns; column++) {
            byte[] qualifier = column == 0 ? COLUMN_ZERO : Bytes.toBytes("" + column);
            get.addColumn(familyName, qualifier);
          }
        } else {
          get.addFamily(familyName);
        }
      }
      if (opts.filterAll) {
        get.setFilter(new FilterAllFilter());
      }
      try {
        updateValueSize(table.get(get).get());
      } catch (ExecutionException e) {
        throw new IOException(e);
      }
      return true;
    }
  }

  static class AsyncSequentialWriteTest extends AsyncTableTest {
    private ArrayList<Put> puts;

    AsyncSequentialWriteTest(AsyncConnection con, TestOptions options, Status status) {
      super(con, options, status);
      if (opts.multiPut > 0) {
        LOG.info("MultiPut enabled. Sending PUTs in batches of " + opts.multiPut + ".");
        this.puts = new ArrayList<>(opts.multiPut);
      }
    }

    protected byte[] generateRow(final long i) {
      return format(i);
    }

    @Override
    @SuppressWarnings("ReturnValueIgnored")
    boolean testRow(final long i, final long startTime) throws IOException, InterruptedException {
      byte[] row = generateRow(i);
      Put put = new Put(row);
      for (int family = 0; family < opts.families; family++) {
        byte[] familyName = Bytes.toBytes(FAMILY_NAME_BASE + family);
        for (int column = 0; column < opts.columns; column++) {
          byte[] qualifier = column == 0 ? COLUMN_ZERO : Bytes.toBytes("" + column);
          byte[] value = generateData(getValueLength());
          if (opts.useTags) {
            byte[] tag = generateData(TAG_LENGTH);
            Tag[] tags = new Tag[opts.noOfTags];
            for (int n = 0; n < opts.noOfTags; n++) {
              Tag t = new ArrayBackedTag((byte) n, tag);
              tags[n] = t;
            }
            KeyValue kv =
              new KeyValue(row, familyName, qualifier, HConstants.LATEST_TIMESTAMP, value, tags);
            put.add(kv);
            updateValueSize(kv.getValueLength());
          } else {
            put.addColumn(familyName, qualifier, value);
            updateValueSize(value.length);
          }
        }
      }
      put.setDurability(opts.writeToWAL ? Durability.SYNC_WAL : Durability.SKIP_WAL);
      try {
        table.put(put).get();
        if (opts.multiPut > 0) {
          this.puts.add(put);
          if (this.puts.size() == opts.multiPut) {
            this.table.put(puts).stream().map(f -> AsyncRandomReadTest.propagate(f::get));
            this.puts.clear();
          } else {
            return false;
          }
        } else {
          table.put(put).get();
        }
      } catch (ExecutionException e) {
        throw new IOException(e);
      }
      return true;
    }
  }

  static abstract class BufferedMutatorTest extends Test {
    protected BufferedMutator mutator;
    protected Table table;

    BufferedMutatorTest(Connection con, TestOptions options, Status status) {
      super(con, options, status);
    }

    @Override
    void onStartup() throws IOException {
      BufferedMutatorParams p = new BufferedMutatorParams(TableName.valueOf(opts.tableName));
      p.writeBufferSize(opts.bufferSize);
      this.mutator = connection.getBufferedMutator(p);
      this.table = connection.getTable(TableName.valueOf(opts.tableName));
    }

    @Override
    void onTakedown() throws IOException {
      mutator.close();
      table.close();
    }
  }

  static class RandomSeekScanTest extends TableTest {
    RandomSeekScanTest(Connection con, TestOptions options, Status status) {
      super(con, options, status);
    }

    @Override
    boolean testRow(final long i, final long startTime) throws IOException {
      Scan scan = new Scan().withStartRow(getRandomRow(opts.totalRows)).setCaching(opts.caching)
        .setCacheBlocks(opts.cacheBlocks).setAsyncPrefetch(opts.asyncPrefetch)
        .setReadType(opts.scanReadType).setScanMetricsEnabled(true);
      FilterList list = new FilterList();
      for (int family = 0; family < opts.families; family++) {
        byte[] familyName = Bytes.toBytes(FAMILY_NAME_BASE + family);
        if (opts.addColumns) {
          for (int column = 0; column < opts.columns; column++) {
            byte[] qualifier = column == 0 ? COLUMN_ZERO : Bytes.toBytes("" + column);
            scan.addColumn(familyName, qualifier);
          }
        } else {
          scan.addFamily(familyName);
        }
      }
      if (opts.filterAll) {
        list.addFilter(new FilterAllFilter());
      }
      list.addFilter(new WhileMatchFilter(new PageFilter(120)));
      scan.setFilter(list);
      ResultScanner s = this.table.getScanner(scan);
      try {
        for (Result rr; (rr = s.next()) != null;) {
          updateValueSize(rr);
        }
      } finally {
        updateScanMetrics(s.getScanMetrics());
        s.close();
      }
      return true;
    }

    @Override
    protected long getReportingPeriod() {
      long period = opts.perClientRunRows / 100;
      return period == 0 ? opts.perClientRunRows : period;
    }

  }

  static abstract class RandomScanWithRangeTest extends TableTest {
    RandomScanWithRangeTest(Connection con, TestOptions options, Status status) {
      super(con, options, status);
    }

    @Override
    boolean testRow(final long i, final long startTime) throws IOException {
      Pair<byte[], byte[]> startAndStopRow = getStartAndStopRow();
      Scan scan = new Scan().withStartRow(startAndStopRow.getFirst())
        .withStopRow(startAndStopRow.getSecond()).setCaching(opts.caching)
        .setCacheBlocks(opts.cacheBlocks).setAsyncPrefetch(opts.asyncPrefetch)
        .setReadType(opts.scanReadType).setScanMetricsEnabled(true);
      for (int family = 0; family < opts.families; family++) {
        byte[] familyName = Bytes.toBytes(FAMILY_NAME_BASE + family);
        if (opts.addColumns) {
          for (int column = 0; column < opts.columns; column++) {
            byte[] qualifier = column == 0 ? COLUMN_ZERO : Bytes.toBytes("" + column);
            scan.addColumn(familyName, qualifier);
          }
        } else {
          scan.addFamily(familyName);
        }
      }
      if (opts.filterAll) {
        scan.setFilter(new FilterAllFilter());
      }
      Result r = null;
      int count = 0;
      ResultScanner s = this.table.getScanner(scan);
      try {
        for (; (r = s.next()) != null;) {
          updateValueSize(r);
          count++;
        }
        if (i % 100 == 0) {
          LOG.info(String.format("Scan for key range %s - %s returned %s rows",
            Bytes.toString(startAndStopRow.getFirst()), Bytes.toString(startAndStopRow.getSecond()),
            count));
        }
      } finally {
        updateScanMetrics(s.getScanMetrics());
        s.close();
      }
      return true;
    }

    protected abstract Pair<byte[], byte[]> getStartAndStopRow();

    protected Pair<byte[], byte[]> generateStartAndStopRows(long maxRange) {
      long start = ThreadLocalRandom.current().nextLong(Long.MAX_VALUE) % opts.totalRows;
      long stop = start + maxRange;
      return new Pair<>(format(start), format(stop));
    }

    @Override
    protected long getReportingPeriod() {
      long period = opts.perClientRunRows / 100;
      return period == 0 ? opts.perClientRunRows : period;
    }
  }

  static class RandomScanWithRange10Test extends RandomScanWithRangeTest {
    RandomScanWithRange10Test(Connection con, TestOptions options, Status status) {
      super(con, options, status);
    }

    @Override
    protected Pair<byte[], byte[]> getStartAndStopRow() {
      return generateStartAndStopRows(10);
    }
  }

  static class RandomScanWithRange100Test extends RandomScanWithRangeTest {
    RandomScanWithRange100Test(Connection con, TestOptions options, Status status) {
      super(con, options, status);
    }

    @Override
    protected Pair<byte[], byte[]> getStartAndStopRow() {
      return generateStartAndStopRows(100);
    }
  }

  static class RandomScanWithRange1000Test extends RandomScanWithRangeTest {
    RandomScanWithRange1000Test(Connection con, TestOptions options, Status status) {
      super(con, options, status);
    }

    @Override
    protected Pair<byte[], byte[]> getStartAndStopRow() {
      return generateStartAndStopRows(1000);
    }
  }

  static class RandomScanWithRange10000Test extends RandomScanWithRangeTest {
    RandomScanWithRange10000Test(Connection con, TestOptions options, Status status) {
      super(con, options, status);
    }

    @Override
    protected Pair<byte[], byte[]> getStartAndStopRow() {
      return generateStartAndStopRows(10000);
    }
  }

  static class RandomReadTest extends TableTest {
    private final Consistency consistency;
    private ArrayList<Get> gets;

    RandomReadTest(Connection con, TestOptions options, Status status) {
      super(con, options, status);
      consistency = options.replicas == DEFAULT_OPTS.replicas ? null : Consistency.TIMELINE;
      if (opts.multiGet > 0) {
        LOG.info("MultiGet enabled. Sending GETs in batches of " + opts.multiGet + ".");
        this.gets = new ArrayList<>(opts.multiGet);
      }
    }

    @Override
    boolean testRow(final long i, final long startTime) throws IOException, InterruptedException {
      if (opts.randomSleep > 0) {
        Thread.sleep(ThreadLocalRandom.current().nextInt(opts.randomSleep));
      }
      Get get = new Get(getRandomRow(opts.totalRows));
      for (int family = 0; family < opts.families; family++) {
        byte[] familyName = Bytes.toBytes(FAMILY_NAME_BASE + family);
        if (opts.addColumns) {
          for (int column = 0; column < opts.columns; column++) {
            byte[] qualifier = column == 0 ? COLUMN_ZERO : Bytes.toBytes("" + column);
            get.addColumn(familyName, qualifier);
          }
        } else {
          get.addFamily(familyName);
        }
      }
      if (opts.filterAll) {
        get.setFilter(new FilterAllFilter());
      }
      get.setConsistency(consistency);
      if (LOG.isTraceEnabled()) LOG.trace(get.toString());
      if (opts.multiGet > 0) {
        this.gets.add(get);
        if (this.gets.size() == opts.multiGet) {
          Result[] rs = this.table.get(this.gets);
          if (opts.replicas > 1) {
            long latency = System.nanoTime() - startTime;
            updateValueSize(rs, latency);
          } else {
            updateValueSize(rs);
          }
          this.gets.clear();
        } else {
          return false;
        }
      } else {
        if (opts.replicas > 1) {
          Result r = this.table.get(get);
          long latency = System.nanoTime() - startTime;
          updateValueSize(r, latency);
        } else {
          updateValueSize(this.table.get(get));
        }
      }
      return true;
    }

    @Override
    protected long getReportingPeriod() {
      long period = opts.perClientRunRows / 10;
      return period == 0 ? opts.perClientRunRows : period;
    }

    @Override
    protected void testTakedown() throws IOException {
      if (this.gets != null && this.gets.size() > 0) {
        this.table.get(gets);
        this.gets.clear();
      }
      super.testTakedown();
    }
  }

  /*
   * Send random reads against fake regions inserted by MetaWriteTest
   */
  static class MetaRandomReadTest extends MetaTest {
    private RegionLocator regionLocator;

    MetaRandomReadTest(Connection con, TestOptions options, Status status) {
      super(con, options, status);
      LOG.info("call getRegionLocation");
    }

    @Override
    void onStartup() throws IOException {
      super.onStartup();
      this.regionLocator = connection.getRegionLocator(table.getName());
    }

    @Override
    boolean testRow(final long i, final long startTime) throws IOException, InterruptedException {
      if (opts.randomSleep > 0) {
        Thread.sleep(ThreadLocalRandom.current().nextInt(opts.randomSleep));
      }
      HRegionLocation hRegionLocation = regionLocator.getRegionLocation(
        getSplitKey(ThreadLocalRandom.current().nextLong(opts.perClientRunRows)), true);
      LOG.debug("get location for region: " + hRegionLocation);
      return true;
    }

    @Override
    protected long getReportingPeriod() {
      long period = opts.perClientRunRows / 10;
      return period == 0 ? opts.perClientRunRows : period;
    }

    @Override
    protected void testTakedown() throws IOException {
      super.testTakedown();
    }
  }

  static class RandomWriteTest extends SequentialWriteTest {
    RandomWriteTest(Connection con, TestOptions options, Status status) {
      super(con, options, status);
    }

    @Override
    protected byte[] generateRow(final long i) {
      return getRandomRow(opts.totalRows);
    }

  }

  static class RandomDeleteTest extends SequentialDeleteTest {
    RandomDeleteTest(Connection con, TestOptions options, Status status) {
      super(con, options, status);
    }

    @Override
    protected byte[] generateRow(final long i) {
      return getRandomRow(opts.totalRows);
    }

  }

  static class ScanTest extends TableTest {
    private ResultScanner testScanner;

    ScanTest(Connection con, TestOptions options, Status status) {
      super(con, options, status);
    }

    @Override
    void testTakedown() throws IOException {
      if (this.testScanner != null) {
        this.testScanner.close();
      }
      super.testTakedown();
    }

    @Override
    boolean testRow(final long i, final long startTime) throws IOException {
      if (this.testScanner == null) {
        Scan scan = new Scan().withStartRow(format(opts.startRow)).setCaching(opts.caching)
          .setCacheBlocks(opts.cacheBlocks).setAsyncPrefetch(opts.asyncPrefetch)
          .setReadType(opts.scanReadType).setScanMetricsEnabled(true);
        for (int family = 0; family < opts.families; family++) {
          byte[] familyName = Bytes.toBytes(FAMILY_NAME_BASE + family);
          if (opts.addColumns) {
            for (int column = 0; column < opts.columns; column++) {
              byte[] qualifier = column == 0 ? COLUMN_ZERO : Bytes.toBytes("" + column);
              scan.addColumn(familyName, qualifier);
            }
          } else {
            scan.addFamily(familyName);
          }
        }
        if (opts.filterAll) {
          scan.setFilter(new FilterAllFilter());
        }
        this.testScanner = table.getScanner(scan);
      }
      Result r = testScanner.next();
      updateValueSize(r);
      return true;
    }
  }

  static class ReverseScanTest extends TableTest {
    private ResultScanner testScanner;

    ReverseScanTest(Connection con, TestOptions options, Status status) {
      super(con, options, status);
    }

    @Override
    void testTakedown() throws IOException {
      if (this.testScanner != null) {
        this.testScanner.close();
      }
      super.testTakedown();
    }

    @Override
    boolean testRow(final long i, final long startTime) throws IOException {
      if (this.testScanner == null) {
        Scan scan = new Scan().setCaching(opts.caching).setCacheBlocks(opts.cacheBlocks)
          .setAsyncPrefetch(opts.asyncPrefetch).setReadType(opts.scanReadType)
          .setScanMetricsEnabled(true).setReversed(true);
        for (int family = 0; family < opts.families; family++) {
          byte[] familyName = Bytes.toBytes(FAMILY_NAME_BASE + family);
          if (opts.addColumns) {
            for (int column = 0; column < opts.columns; column++) {
              byte[] qualifier = column == 0 ? COLUMN_ZERO : Bytes.toBytes("" + column);
              scan.addColumn(familyName, qualifier);
            }
          } else {
            scan.addFamily(familyName);
          }
        }
        if (opts.filterAll) {
          scan.setFilter(new FilterAllFilter());
        }
        this.testScanner = table.getScanner(scan);
      }
      Result r = testScanner.next();
      updateValueSize(r);
      return true;
    }
  }

  /**
   * Base class for operations that are CAS-like; that read a value and then set it based off what
   * they read. In this category is increment, append, checkAndPut, etc.
   * <p>
   * These operations also want some concurrency going on. Usually when these tests run, they
   * operate in their own part of the key range. In CASTest, we will have them all overlap on the
   * same key space. We do this with our getStartRow and getLastRow overrides.
   */
  static abstract class CASTableTest extends TableTest {
    private final byte[] qualifier;

    CASTableTest(Connection con, TestOptions options, Status status) {
      super(con, options, status);
      qualifier = Bytes.toBytes(this.getClass().getSimpleName());
    }

    byte[] getQualifier() {
      return this.qualifier;
    }

    @Override
    long getStartRow() {
      return 0;
    }

    @Override
    long getLastRow() {
      return opts.perClientRunRows;
    }
  }

  static class IncrementTest extends CASTableTest {
    IncrementTest(Connection con, TestOptions options, Status status) {
      super(con, options, status);
    }

    @Override
    boolean testRow(final long i, final long startTime) throws IOException {
      Increment increment = new Increment(format(i));
      // unlike checkAndXXX tests, which make most sense to do on a single value,
      // if multiple families are specified for an increment test we assume it is
      // meant to raise the work factor
      for (int family = 0; family < opts.families; family++) {
        byte[] familyName = Bytes.toBytes(FAMILY_NAME_BASE + family);
        increment.addColumn(familyName, getQualifier(), 1l);
      }
      updateValueSize(this.table.increment(increment));
      return true;
    }
  }

  static class AppendTest extends CASTableTest {
    AppendTest(Connection con, TestOptions options, Status status) {
      super(con, options, status);
    }

    @Override
    boolean testRow(final long i, final long startTime) throws IOException {
      byte[] bytes = format(i);
      Append append = new Append(bytes);
      // unlike checkAndXXX tests, which make most sense to do on a single value,
      // if multiple families are specified for an append test we assume it is
      // meant to raise the work factor
      for (int family = 0; family < opts.families; family++) {
        byte[] familyName = Bytes.toBytes(FAMILY_NAME_BASE + family);
        append.addColumn(familyName, getQualifier(), bytes);
      }
      updateValueSize(this.table.append(append));
      return true;
    }
  }

  static class CheckAndMutateTest extends CASTableTest {
    CheckAndMutateTest(Connection con, TestOptions options, Status status) {
      super(con, options, status);
    }

    @Override
    boolean testRow(final long i, final long startTime) throws IOException {
      final byte[] bytes = format(i);
      // checkAndXXX tests operate on only a single value
      // Put a known value so when we go to check it, it is there.
      Put put = new Put(bytes);
      put.addColumn(FAMILY_ZERO, getQualifier(), bytes);
      this.table.put(put);
      RowMutations mutations = new RowMutations(bytes);
      mutations.add(put);
      this.table.checkAndMutate(bytes, FAMILY_ZERO).qualifier(getQualifier()).ifEquals(bytes)
        .thenMutate(mutations);
      return true;
    }
  }

  static class CheckAndPutTest extends CASTableTest {
    CheckAndPutTest(Connection con, TestOptions options, Status status) {
      super(con, options, status);
    }

    @Override
    boolean testRow(final long i, final long startTime) throws IOException {
      final byte[] bytes = format(i);
      // checkAndXXX tests operate on only a single value
      // Put a known value so when we go to check it, it is there.
      Put put = new Put(bytes);
      put.addColumn(FAMILY_ZERO, getQualifier(), bytes);
      this.table.put(put);
      this.table.checkAndMutate(bytes, FAMILY_ZERO).qualifier(getQualifier()).ifEquals(bytes)
        .thenPut(put);
      return true;
    }
  }

  static class CheckAndDeleteTest extends CASTableTest {
    CheckAndDeleteTest(Connection con, TestOptions options, Status status) {
      super(con, options, status);
    }

    @Override
    boolean testRow(final long i, final long startTime) throws IOException {
      final byte[] bytes = format(i);
      // checkAndXXX tests operate on only a single value
      // Put a known value so when we go to check it, it is there.
      Put put = new Put(bytes);
      put.addColumn(FAMILY_ZERO, getQualifier(), bytes);
      this.table.put(put);
      Delete delete = new Delete(put.getRow());
      delete.addColumn(FAMILY_ZERO, getQualifier());
      this.table.checkAndMutate(bytes, FAMILY_ZERO).qualifier(getQualifier()).ifEquals(bytes)
        .thenDelete(delete);
      return true;
    }
  }

  /*
   * Delete all fake regions inserted to meta table by MetaWriteTest.
   */
  static class CleanMetaTest extends MetaTest {
    CleanMetaTest(Connection con, TestOptions options, Status status) {
      super(con, options, status);
    }

    @Override
    boolean testRow(final long i, final long startTime) throws IOException {
      try {
        RegionInfo regionInfo = connection.getRegionLocator(table.getName())
          .getRegionLocation(getSplitKey(i), false).getRegion();
        LOG.debug("deleting region from meta: " + regionInfo);

        Delete delete =
          MetaTableAccessor.makeDeleteFromRegionInfo(regionInfo, HConstants.LATEST_TIMESTAMP);
        try (Table t = MetaTableAccessor.getMetaHTable(connection)) {
          t.delete(delete);
        }
      } catch (IOException ie) {
        // Log and continue
        LOG.error("cannot find region with start key: " + i);
      }
      return true;
    }
  }

  static class SequentialReadTest extends TableTest {
    SequentialReadTest(Connection con, TestOptions options, Status status) {
      super(con, options, status);
    }

    @Override
    boolean testRow(final long i, final long startTime) throws IOException {
      Get get = new Get(format(i));
      for (int family = 0; family < opts.families; family++) {
        byte[] familyName = Bytes.toBytes(FAMILY_NAME_BASE + family);
        if (opts.addColumns) {
          for (int column = 0; column < opts.columns; column++) {
            byte[] qualifier = column == 0 ? COLUMN_ZERO : Bytes.toBytes("" + column);
            get.addColumn(familyName, qualifier);
          }
        } else {
          get.addFamily(familyName);
        }
      }
      if (opts.filterAll) {
        get.setFilter(new FilterAllFilter());
      }
      updateValueSize(table.get(get));
      return true;
    }
  }

  static class SequentialWriteTest extends BufferedMutatorTest {
    private ArrayList<Put> puts;

    SequentialWriteTest(Connection con, TestOptions options, Status status) {
      super(con, options, status);
      if (opts.multiPut > 0) {
        LOG.info("MultiPut enabled. Sending PUTs in batches of " + opts.multiPut + ".");
        this.puts = new ArrayList<>(opts.multiPut);
      }
    }

    protected byte[] generateRow(final long i) {
      return format(i);
    }

    @Override
    boolean testRow(final long i, final long startTime) throws IOException {
      byte[] row = generateRow(i);
      Put put = new Put(row);
      for (int family = 0; family < opts.families; family++) {
        byte familyName[] = Bytes.toBytes(FAMILY_NAME_BASE + family);
        for (int column = 0; column < opts.columns; column++) {
          byte[] qualifier = column == 0 ? COLUMN_ZERO : Bytes.toBytes("" + column);
          byte[] value = generateData(getValueLength());
          if (opts.useTags) {
            byte[] tag = generateData(TAG_LENGTH);
            Tag[] tags = new Tag[opts.noOfTags];
            for (int n = 0; n < opts.noOfTags; n++) {
              Tag t = new ArrayBackedTag((byte) n, tag);
              tags[n] = t;
            }
            KeyValue kv =
              new KeyValue(row, familyName, qualifier, HConstants.LATEST_TIMESTAMP, value, tags);
            put.add(kv);
            updateValueSize(kv.getValueLength());
          } else {
            put.addColumn(familyName, qualifier, value);
            updateValueSize(value.length);
          }
        }
      }
      put.setDurability(opts.writeToWAL ? Durability.SYNC_WAL : Durability.SKIP_WAL);
      if (opts.autoFlush) {
        if (opts.multiPut > 0) {
          this.puts.add(put);
          if (this.puts.size() == opts.multiPut) {
            table.put(this.puts);
            this.puts.clear();
          } else {
            return false;
          }
        } else {
          table.put(put);
        }
      } else {
        mutator.mutate(put);
      }
      return true;
    }
  }

  static class SequentialDeleteTest extends BufferedMutatorTest {

    SequentialDeleteTest(Connection con, TestOptions options, Status status) {
      super(con, options, status);
    }

    protected byte[] generateRow(final long i) {
      return format(i);
    }

    @Override
    boolean testRow(final long i, final long startTime) throws IOException {
      byte[] row = generateRow(i);
      Delete delete = new Delete(row);
      for (int family = 0; family < opts.families; family++) {
        byte[] familyName = Bytes.toBytes(FAMILY_NAME_BASE + family);
        delete.addFamily(familyName);
      }
      delete.setDurability(opts.writeToWAL ? Durability.SYNC_WAL : Durability.SKIP_WAL);
      if (opts.autoFlush) {
        table.delete(delete);
      } else {
        mutator.mutate(delete);
      }
      return true;
    }
  }

  /*
   * Insert fake regions into meta table with contiguous split keys.
   */
  static class MetaWriteTest extends MetaTest {

    MetaWriteTest(Connection con, TestOptions options, Status status) {
      super(con, options, status);
    }

    @Override
    boolean testRow(final long i, final long startTime) throws IOException {
      List<RegionInfo> regionInfos = new ArrayList<RegionInfo>();
      RegionInfo regionInfo = (RegionInfoBuilder.newBuilder(TableName.valueOf(TABLE_NAME))
        .setStartKey(getSplitKey(i)).setEndKey(getSplitKey(i + 1)).build());
      regionInfos.add(regionInfo);
      MetaTableAccessor.addRegionsToMeta(connection, regionInfos, 1);

      // write the serverName columns
      MetaTableAccessor.updateRegionLocation(connection, regionInfo,
        ServerName.valueOf("localhost", 60010, ThreadLocalRandom.current().nextLong()), i,
        EnvironmentEdgeManager.currentTime());
      return true;
    }
  }

  static class FilteredScanTest extends TableTest {
    protected static final Logger LOG = LoggerFactory.getLogger(FilteredScanTest.class.getName());

    FilteredScanTest(Connection con, TestOptions options, Status status) {
      super(con, options, status);
      if (opts.perClientRunRows == DEFAULT_ROWS_PER_GB) {
        LOG.warn("Option \"rows\" unspecified. Using default value " + DEFAULT_ROWS_PER_GB
          + ". This could take a very long time.");
      }
    }

    @Override
    boolean testRow(long i, final long startTime) throws IOException {
      byte[] value = generateData(getValueLength());
      Scan scan = constructScan(value);
      ResultScanner scanner = null;
      try {
        scanner = this.table.getScanner(scan);
        for (Result r = null; (r = scanner.next()) != null;) {
          updateValueSize(r);
        }
      } finally {
        if (scanner != null) {
          updateScanMetrics(scanner.getScanMetrics());
          scanner.close();
        }
      }
      return true;
    }

    protected Scan constructScan(byte[] valuePrefix) throws IOException {
      FilterList list = new FilterList();
      Filter filter = new SingleColumnValueFilter(FAMILY_ZERO, COLUMN_ZERO, CompareOperator.EQUAL,
        new BinaryComparator(valuePrefix));
      list.addFilter(filter);
      if (opts.filterAll) {
        list.addFilter(new FilterAllFilter());
      }
      Scan scan = new Scan().setCaching(opts.caching).setCacheBlocks(opts.cacheBlocks)
        .setAsyncPrefetch(opts.asyncPrefetch).setReadType(opts.scanReadType)
        .setScanMetricsEnabled(true);
      if (opts.addColumns) {
        for (int column = 0; column < opts.columns; column++) {
          byte[] qualifier = column == 0 ? COLUMN_ZERO : Bytes.toBytes("" + column);
          scan.addColumn(FAMILY_ZERO, qualifier);
        }
      } else {
        scan.addFamily(FAMILY_ZERO);
      }
      scan.setFilter(list);
      return scan;
    }
  }

  /**
   * Compute a throughput rate in MB/s.
   * @param rows   Number of records consumed.
   * @param timeMs Time taken in milliseconds.
   * @return String value with label, ie '123.76 MB/s'
   */
  private static String calculateMbps(long rows, long timeMs, final int valueSize, int families,
    int columns) {
    BigDecimal rowSize = BigDecimal.valueOf(ROW_LENGTH
      + ((valueSize + (FAMILY_NAME_BASE.length() + 1) + COLUMN_ZERO.length) * columns) * families);
    BigDecimal mbps = BigDecimal.valueOf(rows).multiply(rowSize, CXT)
      .divide(BigDecimal.valueOf(timeMs), CXT).multiply(MS_PER_SEC, CXT).divide(BYTES_PER_MB, CXT);
    return FMT.format(mbps) + " MB/s";
  }

  /*
   * Format passed integer.
   * @return Returns zero-prefixed ROW_LENGTH-byte wide decimal version of passed number (Does
   * absolute in case number is negative).
   */
  public static byte[] format(final long number) {
    byte[] b = new byte[ROW_LENGTH];
    long d = Math.abs(number);
    for (int i = b.length - 1; i >= 0; i--) {
      b[i] = (byte) ((d % 10) + '0');
      d /= 10;
    }
    return b;
  }

  /*
   * This method takes some time and is done inline uploading data. For example, doing the mapfile
   * test, generation of the key and value consumes about 30% of CPU time.
   * @return Generated random value to insert into a table cell.
   */
  public static byte[] generateData(int length) {
    byte[] b = new byte[length];
    int i;

    Random r = ThreadLocalRandom.current();
    for (i = 0; i < (length - 8); i += 8) {
      b[i] = (byte) (65 + r.nextInt(26));
      b[i + 1] = b[i];
      b[i + 2] = b[i];
      b[i + 3] = b[i];
      b[i + 4] = b[i];
      b[i + 5] = b[i];
      b[i + 6] = b[i];
      b[i + 7] = b[i];
    }

    byte a = (byte) (65 + r.nextInt(26));
    for (; i < length; i++) {
      b[i] = a;
    }
    return b;
  }

  static byte[] getRandomRow(final long totalRows) {
    return format(generateRandomRow(totalRows));
  }

  static long generateRandomRow(final long totalRows) {
    return ThreadLocalRandom.current().nextLong(Long.MAX_VALUE) % totalRows;
  }

  static RunResult runOneClient(final Class<? extends TestBase> cmd, Configuration conf,
    Connection con, AsyncConnection asyncCon, TestOptions opts, final Status status)
    throws IOException, InterruptedException {
    status.setStatus(
      "Start " + cmd + " at offset " + opts.startRow + " for " + opts.perClientRunRows + " rows");
    long totalElapsedTime;

    final TestBase t;
    try {
      if (AsyncTest.class.isAssignableFrom(cmd)) {
        Class<? extends AsyncTest> newCmd = (Class<? extends AsyncTest>) cmd;
        Constructor<? extends AsyncTest> constructor =
          newCmd.getDeclaredConstructor(AsyncConnection.class, TestOptions.class, Status.class);
        t = constructor.newInstance(asyncCon, opts, status);
      } else {
        Class<? extends Test> newCmd = (Class<? extends Test>) cmd;
        Constructor<? extends Test> constructor =
          newCmd.getDeclaredConstructor(Connection.class, TestOptions.class, Status.class);
        t = constructor.newInstance(con, opts, status);
      }
    } catch (NoSuchMethodException e) {
      throw new IllegalArgumentException("Invalid command class: " + cmd.getName()
        + ".  It does not provide a constructor as described by "
        + "the javadoc comment.  Available constructors are: "
        + Arrays.toString(cmd.getConstructors()));
    } catch (Exception e) {
      throw new IllegalStateException("Failed to construct command class", e);
    }
    totalElapsedTime = t.test();

    status.setStatus("Finished " + cmd + " in " + totalElapsedTime + "ms at offset " + opts.startRow
      + " for " + opts.perClientRunRows + " rows" + " ("
      + calculateMbps((long) (opts.perClientRunRows * opts.sampleRate), totalElapsedTime,
        getAverageValueLength(opts), opts.families, opts.columns)
      + ")");

    return new RunResult(totalElapsedTime, t.numOfReplyOverLatencyThreshold,
      t.numOfReplyFromReplica, t.getLatencyHistogram());
  }

  private static int getAverageValueLength(final TestOptions opts) {
    return opts.valueRandom ? opts.valueSize / 2 : opts.valueSize;
  }

  private void runTest(final Class<? extends TestBase> cmd, TestOptions opts)
    throws IOException, InterruptedException, ClassNotFoundException, ExecutionException {
    // Log the configuration we're going to run with. Uses JSON mapper because lazy. It'll do
    // the TestOptions introspection for us and dump the output in a readable format.
    LOG.info(cmd.getSimpleName() + " test run options=" + GSON.toJson(opts));
    Admin admin = null;
    Connection connection = null;
    try {
      connection = ConnectionFactory.createConnection(getConf());
      admin = connection.getAdmin();
      checkTable(admin, opts);
    } finally {
      if (admin != null) admin.close();
      if (connection != null) connection.close();
    }
    if (opts.nomapred) {
      doLocalClients(opts, getConf());
    } else {
      doMapReduce(opts, getConf());
    }
  }

  protected void printUsage() {
    printUsage(PE_COMMAND_SHORTNAME, null);
  }

  protected static void printUsage(final String message) {
    printUsage(PE_COMMAND_SHORTNAME, message);
  }

  protected static void printUsageAndExit(final String message, final int exitCode) {
    printUsage(message);
    System.exit(exitCode);
  }

  protected static void printUsage(final String shortName, final String message) {
    if (message != null && message.length() > 0) {
      System.err.println(message);
    }
    System.err.print("Usage: hbase " + shortName);
    System.err.println("  <OPTIONS> [-D<property=value>]* <command|class> <nclients>");
    System.err.println();
    System.err.println("General Options:");
    System.err.println(
      " nomapred        Run multiple clients using threads " + "(rather than use mapreduce)");
    System.err
      .println(" oneCon          all the threads share the same connection. Default: False");
    System.err.println(" connCount          connections all threads share. "
      + "For example, if set to 2, then all thread share 2 connection. "
      + "Default: depend on oneCon parameter. if oneCon set to true, then connCount=1, "
      + "if not, connCount=thread number");

    System.err.println(" sampleRate      Execute test on a sample of total rows. Default: 1.0");
    System.err.println(" period          Report every 'period' rows: "
      + "Default: opts.perClientRunRows / 10 = " + DEFAULT_OPTS.getPerClientRunRows() / 10);
    System.err.println(" cycles          How many times to cycle the test. Defaults: 1.");
    System.err.println(
      " traceRate       Enable HTrace spans. Initiate tracing every N rows. " + "Default: 0");
    System.err.println(" latency         Set to report operation latencies. Default: False");
    System.err.println(" latencyThreshold  Set to report number of operations with latency "
      + "over lantencyThreshold, unit in millisecond, default 0");
    System.err.println(" measureAfter    Start to measure the latency once 'measureAfter'"
      + " rows have been treated. Default: 0");
    System.err
      .println(" valueSize       Pass value size to use: Default: " + DEFAULT_OPTS.getValueSize());
    System.err.println(" valueRandom     Set if we should vary value size between 0 and "
      + "'valueSize'; set on read for stats on size: Default: Not set.");
    System.err.println(" blockEncoding   Block encoding to use. Value should be one of "
      + Arrays.toString(DataBlockEncoding.values()) + ". Default: NONE");
    System.err.println();
    System.err.println("Table Creation / Write Tests:");
    System.err.println(" table           Alternate table name. Default: 'TestTable'");
    System.err.println(
      " rows            Rows each client runs. Default: " + DEFAULT_OPTS.getPerClientRunRows()
        + ".  In case of randomReads and randomSeekScans this could"
        + " be specified along with --size to specify the number of rows to be scanned within"
        + " the total range specified by the size.");
    System.err.println(
      " size            Total size in GiB. Mutually exclusive with --rows for writes and scans"
        + ". But for randomReads and randomSeekScans when you use size with --rows you could"
        + " use size to specify the end range and --rows"
        + " specifies the number of rows within that range. " + "Default: 1.0.");
    System.err.println(" compress        Compression type to use (GZ, LZO, ...). Default: 'NONE'");
    System.err.println(" encryption      Encryption type to use (AES, ...). Default: 'NONE'");
    System.err.println(
      " flushCommits    Used to determine if the test should flush the table. " + "Default: false");
    System.err.println(" valueZipf       Set if we should vary value size between 0 and "
      + "'valueSize' in zipf form: Default: Not set.");
    System.err.println(" writeToWAL      Set writeToWAL on puts. Default: True");
    System.err.println(" autoFlush       Set autoFlush on htable. Default: False");
    System.err.println(" multiPut        Batch puts together into groups of N. Only supported "
      + "by write. If multiPut is bigger than 0, autoFlush need to set to true. Default: 0");
    System.err.println(" presplit        Create presplit table. If a table with same name exists,"
      + " it'll be deleted and recreated (instead of verifying count of its existing regions). "
      + "Recommended for accurate perf analysis (see guide). Default: disabled");
    System.err.println(
      " usetags         Writes tags along with KVs. Use with HFile V3. " + "Default: false");
    System.err.println(" numoftags       Specify the no of tags that would be needed. "
      + "This works only if usetags is true. Default: " + DEFAULT_OPTS.noOfTags);
    System.err.println(" splitPolicy     Specify a custom RegionSplitPolicy for the table.");
    System.err.println(" columns         Columns to write per row. Default: 1");
    System.err
      .println(" families        Specify number of column families for the table. Default: 1");
    System.err.println();
    System.err.println("Read Tests:");
    System.err.println(" filterAll       Helps to filter out all the rows on the server side"
      + " there by not returning any thing back to the client.  Helps to check the server side"
      + " performance.  Uses FilterAllFilter internally. ");
    System.err.println(" multiGet        Batch gets together into groups of N. Only supported "
      + "by randomRead. Default: disabled");
    System.err.println(" inmemory        Tries to keep the HFiles of the CF "
      + "inmemory as far as possible. Not guaranteed that reads are always served "
      + "from memory.  Default: false");
    System.err
      .println(" bloomFilter     Bloom filter type, one of " + Arrays.toString(BloomType.values()));
    System.err.println(" blockSize       Blocksize to use when writing out hfiles. ");
    System.err
      .println(" inmemoryCompaction  Makes the column family to do inmemory flushes/compactions. "
        + "Uses the CompactingMemstore");
    System.err.println(" addColumns      Adds columns to scans/gets explicitly. Default: true");
    System.err.println(" replicas        Enable region replica testing. Defaults: 1.");
    System.err.println(
      " randomSleep     Do a random sleep before each get between 0 and entered value. Defaults: 0");
    System.err.println(" caching         Scan caching to use. Default: 30");
    System.err.println(" asyncPrefetch   Enable asyncPrefetch for scan");
    System.err.println(" cacheBlocks     Set the cacheBlocks option for scan. Default: true");
    System.err.println(
      " scanReadType    Set the readType option for scan, stream/pread/default. Default: default");
    System.err.println(" bufferSize      Set the value of client side buffering. Default: 2MB");
    System.err.println();
    System.err.println(" Note: -D properties will be applied to the conf used. ");
    System.err.println("  For example: ");
    System.err.println("   -Dmapreduce.output.fileoutputformat.compress=true");
    System.err.println("   -Dmapreduce.task.timeout=60000");
    System.err.println();
    System.err.println("Command:");
    for (CmdDescriptor command : COMMANDS.values()) {
      System.err.println(String.format(" %-20s %s", command.getName(), command.getDescription()));
    }
    System.err.println();
    System.err.println("Class:");
    System.err.println("To run any custom implementation of PerformanceEvaluation.Test, "
      + "provide the classname of the implementaion class in place of "
      + "command name and it will be loaded at runtime from classpath.:");
    System.err.println("Please consider to contribute back "
      + "this custom test impl into a builtin PE command for the benefit of the community");
    System.err.println();
    System.err.println("Args:");
    System.err.println(" nclients        Integer. Required. Total number of clients "
      + "(and HRegionServers) running. 1 <= value <= 500");
    System.err.println("Examples:");
    System.err.println(" To run a single client doing the default 1M sequentialWrites:");
    System.err.println(" $ hbase " + shortName + " sequentialWrite 1");
    System.err.println(" To run 10 clients doing increments over ten rows:");
    System.err.println(" $ hbase " + shortName + " --rows=10 --nomapred increment 10");
  }

  /**
   * Parse options passed in via an arguments array. Assumes that array has been split on
   * white-space and placed into a {@code Queue}. Any unknown arguments will remain in the queue at
   * the conclusion of this method call. It's up to the caller to deal with these unrecognized
   * arguments.
   */
  static TestOptions parseOpts(Queue<String> args) {
    final TestOptions opts = new TestOptions();

    final Map<String, Consumer<Boolean>> flagHandlers = new HashMap<>();
    flagHandlers.put("nomapred", v -> opts.nomapred = v);
    flagHandlers.put("flushCommits", v -> opts.flushCommits = v);
    flagHandlers.put("writeToWAL", v -> opts.writeToWAL = v);
    flagHandlers.put("inmemory", v -> opts.inMemoryCF = v);
    flagHandlers.put("autoFlush", v -> opts.autoFlush = v);
    flagHandlers.put("oneCon", v -> opts.oneCon = v);
    flagHandlers.put("latency", v -> opts.reportLatency = v);
    flagHandlers.put("usetags", v -> opts.useTags = v);
    flagHandlers.put("filterAll", v -> opts.filterAll = v);
    flagHandlers.put("valueRandom", v -> opts.valueRandom = v);
    flagHandlers.put("valueZipf", v -> opts.valueZipf = v);
    flagHandlers.put("addColumns", v -> opts.addColumns = v);
    flagHandlers.put("asyncPrefetch", v -> opts.asyncPrefetch = v);
    flagHandlers.put("cacheBlocks", v -> opts.cacheBlocks = v);

    final Map<String, Consumer<String>> handlers = new HashMap<>();
    handlers.put("rows", v -> opts.perClientRunRows = Long.parseLong(v));
    handlers.put("cycles", v -> opts.cycles = Integer.parseInt(v));
    handlers.put("sampleRate", v -> opts.sampleRate = Float.parseFloat(v));
    handlers.put("table", v -> opts.tableName = v);
    handlers.put("startRow", v -> opts.startRow = Long.parseLong(v));
    handlers.put("compress", v -> opts.compression = Compression.Algorithm.valueOf(v));
    handlers.put("encryption", v -> opts.encryption = v);
    handlers.put("traceRate", v -> opts.traceRate = Double.parseDouble(v));
    handlers.put("blockEncoding", v -> opts.blockEncoding = DataBlockEncoding.valueOf(v));
    handlers.put("presplit", v -> opts.presplitRegions = Integer.parseInt(v));
    handlers.put("connCount", v -> opts.connCount = Integer.parseInt(v));
    handlers.put("latencyThreshold", v -> opts.latencyThreshold = Integer.parseInt(v));
    handlers.put("multiGet", v -> opts.multiGet = Integer.parseInt(v));
    handlers.put("multiPut", v -> opts.multiPut = Integer.parseInt(v));
    handlers.put("numoftags", v -> opts.noOfTags = Integer.parseInt(v));
    handlers.put("replicas", v -> opts.replicas = Integer.parseInt(v));
    handlers.put("size", v -> {
      opts.size = Float.parseFloat(v);
      if (opts.size <= 1.0f) {
        throw new IllegalStateException("Size must be > 1; i.e. 1GB");
      }
    });
    handlers.put("splitPolicy", v -> opts.splitPolicy = v);
    handlers.put("randomSleep", v -> opts.randomSleep = Integer.parseInt(v));
    handlers.put("measureAfter", v -> opts.measureAfter = Integer.parseInt(v));
    handlers.put("bloomFilter", v -> opts.bloomType = BloomType.valueOf(v));
    handlers.put("blockSize", v -> opts.blockSize = Integer.parseInt(v));
    handlers.put("valueSize", v -> opts.valueSize = Integer.parseInt(v));
    handlers.put("period", v -> opts.period = Integer.parseInt(v));
    handlers.put("inmemoryCompaction",
      v -> opts.inMemoryCompaction = MemoryCompactionPolicy.valueOf(v));
    handlers.put("columns", v -> opts.columns = Integer.parseInt(v));
    handlers.put("families", v -> opts.families = Integer.parseInt(v));
    handlers.put("caching", v -> opts.caching = Integer.parseInt(v));
    handlers.put("scanReadType", v -> opts.scanReadType = Scan.ReadType.valueOf(v.toUpperCase()));
    handlers.put("bufferSize", v -> opts.bufferSize = Long.parseLong(v));
    handlers.put("commandPropertiesFile", fileName -> {
      try {
        final Properties properties = new Properties();
        final InputStream resourceStream =
          PerformanceEvaluation.class.getClassLoader().getResourceAsStream(fileName);
        if (resourceStream == null) {
          throw new IllegalArgumentException("Resource file not found: " + fileName);
        }
        properties.load(resourceStream);
        opts.commandProperties = properties;
      } catch (IOException e) {
        throw new IllegalStateException("Failed to load command properties from file: " + fileName,
          e);
      }
    });

    String cmd = null;
    final Splitter splitter = Splitter.on("=").limit(2).trimResults();
    while ((cmd = args.poll()) != null) {
      if (cmd.equals("-h") || cmd.startsWith("--h")) {
        // place item back onto queue so that caller knows parsing was incomplete
        args.add(cmd);
        break;
      }

      if (cmd.startsWith("--")) {
        final List<String> parts = splitter.splitToList(cmd.substring(2));
        final String key = parts.get(0);

        try {
          // Boolean options can be specified as --flag or --flag=true/false
          final Consumer<Boolean> flagHandler = flagHandlers.get(key);
          if (flagHandler != null) {
            flagHandler.accept(parts.size() > 1 ? parseBoolean(parts.get(1)) : true);
            continue;
          }

          // Options that require a value followed by an equals sign
          final Consumer<String> handler = handlers.get(key);
          if (handler != null) {
            if (parts.size() < 2) {
              throw new IllegalArgumentException("--" + key + " requires a value");
            }
            handler.accept(parts.get(1));
            continue;
          }
        } catch (RuntimeException e) {
          throw new IllegalArgumentException("Invalid option: " + cmd, e);
        }
      }

      if (isCommandClass(cmd)) {
        opts.cmdName = cmd;
        try {
          opts.numClientThreads = Integer.parseInt(args.remove());
        } catch (NoSuchElementException | NumberFormatException e) {
          throw new IllegalArgumentException("Command " + cmd + " does not have threads number", e);
        }
        calculateRowsAndSize(opts);
        break;
      }

      printUsageAndExit("ERROR: Unrecognized option/command: " + cmd, -1);
    }

    validateParsedOpts(opts);
    return opts;
  }

  // Boolean.parseBoolean is not strict enough for our needs, so we implement our own
  private static boolean parseBoolean(String value) {
    if ("true".equalsIgnoreCase(value)) {
      return true;
    }
    if ("false".equalsIgnoreCase(value)) {
      return false;
    }
    throw new IllegalArgumentException("Invalid boolean value: " + value);
  }

  /**
   * Validates opts after all the opts are parsed, so that caller need not to maintain order of opts
   */
  private static void validateParsedOpts(TestOptions opts) {

    if (!opts.autoFlush && opts.multiPut > 0) {
      throw new IllegalArgumentException("autoFlush must be true when multiPut is more than 0");
    }

    if (opts.oneCon && opts.connCount > 1) {
      throw new IllegalArgumentException(
        "oneCon is set to true, " + "connCount should not bigger than 1");
    }

    if (opts.valueZipf && opts.valueRandom) {
      throw new IllegalStateException("Either valueZipf or valueRandom but not both");
    }
  }

  static TestOptions calculateRowsAndSize(final TestOptions opts) {
    int rowsPerGB = getRowsPerGB(opts);
    if (
      (opts.getCmdName() != null
        && (opts.getCmdName().equals(RANDOM_READ) || opts.getCmdName().equals(RANDOM_SEEK_SCAN)))
        && opts.size != DEFAULT_OPTS.size && opts.perClientRunRows != DEFAULT_OPTS.perClientRunRows
    ) {
      opts.totalRows = (long) (opts.size * rowsPerGB);
    } else if (opts.size != DEFAULT_OPTS.size) {
      // total size in GB specified
      opts.totalRows = (long) (opts.size * rowsPerGB);
      opts.perClientRunRows = opts.totalRows / opts.numClientThreads;
    } else {
      opts.totalRows = opts.perClientRunRows * opts.numClientThreads;
      // Cast to float to ensure floating-point division
      opts.size = (float) opts.totalRows / rowsPerGB;
    }
    return opts;
  }

  static int getRowsPerGB(final TestOptions opts) {
    return ONE_GB / ((opts.valueRandom ? opts.valueSize / 2 : opts.valueSize) * opts.getFamilies()
      * opts.getColumns());
  }

  @Override
  public int run(String[] args) throws Exception {
    // Process command-line args. TODO: Better cmd-line processing
    // (but hopefully something not as painful as cli options).
    int errCode = -1;
    if (args.length < 1) {
      printUsage();
      return errCode;
    }

    try {
      LinkedList<String> argv = new LinkedList<>();
      argv.addAll(Arrays.asList(args));
      TestOptions opts = parseOpts(argv);

      // args remaining, print help and exit
      if (!argv.isEmpty()) {
        errCode = 0;
        printUsage();
        return errCode;
      }

      // must run at least 1 client
      if (opts.numClientThreads <= 0) {
        throw new IllegalArgumentException("Number of clients must be > 0");
      }

      // cmdName should not be null, print help and exit
      if (opts.cmdName == null) {
        printUsage();
        return errCode;
      }

      Class<? extends TestBase> cmdClass = determineCommandClass(opts.cmdName);
      if (cmdClass != null) {
        runTest(cmdClass, opts);
        errCode = 0;
      }

    } catch (Exception e) {
      e.printStackTrace();
    }

    return errCode;
  }

  private static boolean isCommandClass(String cmd) {
    return COMMANDS.containsKey(cmd) || isCustomTestClass(cmd);
  }

  private static boolean isCustomTestClass(String cmd) {
    Class<? extends Test> cmdClass;
    try {
      cmdClass =
        (Class<? extends Test>) PerformanceEvaluation.class.getClassLoader().loadClass(cmd);
      addCommandDescriptor(cmdClass, cmd, "custom command");
      return true;
    } catch (Throwable th) {
      LOG.info("No class found for command: " + cmd, th);
      return false;
    }
  }

  private static Class<? extends TestBase> determineCommandClass(String cmd) {
    CmdDescriptor descriptor = COMMANDS.get(cmd);
    return descriptor != null ? descriptor.getCmdClass() : null;
  }

  public static void main(final String[] args) throws Exception {
    int res = ToolRunner.run(new PerformanceEvaluation(HBaseConfiguration.create()), args);
    System.exit(res);
  }
}
