/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio.table.under.hive;

import alluxio.AlluxioURI;
import alluxio.Constants;
import alluxio.exception.AlluxioException;
import alluxio.exception.InvalidPathException;
import alluxio.exception.status.NotFoundException;
import alluxio.grpc.CreateDirectoryPOptions;
import alluxio.grpc.MountPOptions;
import alluxio.grpc.table.ColumnStatisticsInfo;
import alluxio.grpc.table.Layout;
import alluxio.grpc.table.layout.hive.PartitionInfo;
import alluxio.master.table.DatabaseInfo;
import alluxio.resource.CloseableResource;
import alluxio.table.common.UdbPartition;
import alluxio.table.common.layout.HiveLayout;
import alluxio.table.common.udb.UdbConfiguration;
import alluxio.table.common.udb.UdbContext;
import alluxio.table.common.udb.UdbTable;
import alluxio.table.common.udb.UnderDatabase;
import alluxio.table.under.hive.util.HiveClientPool;
import alluxio.table.under.hive.util.PathTranslator;
import alluxio.util.io.PathUtils;

import org.apache.hadoop.hive.common.FileUtils;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.hadoop.hive.metastore.Warehouse;
import org.apache.hadoop.hive.metastore.api.ColumnStatisticsObj;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.PrincipalType;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Hive database implementation.
 */
public class HiveDatabase implements UnderDatabase {
  private static final Logger LOG = LoggerFactory.getLogger(HiveDatabase.class);

  private final UdbContext mUdbContext;
  private final UdbConfiguration mConfiguration;
  /** the connection uri for the hive metastore. */
  private final String mConnectionUri;
  /** the name of the hive db. */
  private final String mHiveDbName;
  /** Hive client is not thread-safe, so use a client pool for concurrency. */
  private final HiveClientPool mClientPool;

  private HiveDatabase(UdbContext udbContext, UdbConfiguration configuration,
      String connectionUri, String hiveDbName) {
    mUdbContext = udbContext;
    mConfiguration = configuration;
    mConnectionUri = connectionUri;
    mHiveDbName = hiveDbName;
    mClientPool = new HiveClientPool(mConnectionUri, mHiveDbName);
  }

  /**
   * Creates an instance of the Hive database UDB.
   *
   * @param udbContext the db context
   * @param configuration the configuration
   * @return the new instance
   */
  public static HiveDatabase create(UdbContext udbContext, UdbConfiguration configuration) {
    String connectionUri = udbContext.getConnectionUri();
    if (connectionUri == null || connectionUri.isEmpty()) {
      throw new IllegalArgumentException(
          "Hive udb connection uri cannot be empty: " + connectionUri);
    }
    String hiveDbName = udbContext.getUdbDbName();
    if (hiveDbName == null || hiveDbName.isEmpty()) {
      throw new IllegalArgumentException("Hive database name cannot be empty: " + hiveDbName);
    }

    return new HiveDatabase(udbContext, configuration, connectionUri, hiveDbName);
  }

  @Override
  public UdbContext getUdbContext() {
    return mUdbContext;
  }

  @Override
  public DatabaseInfo getDatabaseInfo() throws IOException {
    try (CloseableResource<IMetaStoreClient> client = mClientPool.acquireClientResource()) {
      Database hiveDb = client.get().getDatabase(mHiveDbName);
      alluxio.grpc.table.PrincipalType type = alluxio.grpc.table.PrincipalType.USER;
      if (hiveDb.getOwnerType().equals(PrincipalType.ROLE)) {
        type = alluxio.grpc.table.PrincipalType.ROLE;
      }
      return new DatabaseInfo(hiveDb.getLocationUri(), hiveDb.getOwnerName(), type,
          hiveDb.getDescription(), hiveDb.getParameters());
    } catch (TException  e) {
      throw new IOException("Failed to get hive database " + mHiveDbName
          + ". " + e.getMessage(), e);
    }
  }

  @Override
  public String getType() {
    return HiveDatabaseFactory.TYPE;
  }

  @Override
  public String getName() {
    return mHiveDbName;
  }

  @Override
  public List<String> getTableNames() throws IOException {
    try (CloseableResource<IMetaStoreClient> client = mClientPool.acquireClientResource()) {
      return client.get().getAllTables(mHiveDbName);
    } catch (TException  e) {
      throw new IOException("Failed to get hive tables: " + e.getMessage(), e);
    }
  }

  private String mountAlluxioPath(String tableName, AlluxioURI ufsUri, AlluxioURI tableUri)
      throws IOException, AlluxioException {
    if (Objects.equals(ufsUri.getScheme(), Constants.SCHEME)) {
      // already an alluxio uri, return the alluxio uri
      return ufsUri.toString();
    }
    try {
      tableUri = mUdbContext.getFileSystem().reverseResolve(ufsUri);
      LOG.debug("Trying to mount table {} location {}, but it is already mounted at location {}",
          tableName, ufsUri, tableUri);
      return tableUri.getPath();
    } catch (InvalidPathException e) {
      // ufs path not mounted, continue
    }
    // make sure the parent exists
    mUdbContext.getFileSystem().createDirectory(tableUri.getParent(),
        CreateDirectoryPOptions.newBuilder().setRecursive(true).setAllowExists(true).build());
    Map<String, String> mountOptionMap = mConfiguration.getMountOption(
        String.format("%s://%s/", ufsUri.getScheme(), ufsUri.getAuthority().toString()));
    MountPOptions.Builder option = MountPOptions.newBuilder();
    for (Map.Entry<String, String> entry : mountOptionMap.entrySet()) {
      if (entry.getKey().equals(UdbConfiguration.READ_ONLY_OPTION)) {
        option.setReadOnly(Boolean.parseBoolean(entry.getValue()));
      } else if (entry.getKey().equals(UdbConfiguration.SHARED_OPTION)) {
        option.setShared(Boolean.parseBoolean(entry.getValue()));
      } else {
        option.putProperties(entry.getKey(), entry.getValue());
      }
    }
    mUdbContext.getFileSystem().mount(tableUri, ufsUri, option.build());

    LOG.info("mounted table {} location {} to Alluxio location {} with mountOption {}",
        tableName, ufsUri, tableUri, option.build());
    return tableUri.getPath();
  }

  private PathTranslator mountAlluxioPaths(Table table, List<Partition> partitions)
      throws IOException {
    String tableName = table.getTableName();
    AlluxioURI ufsUri;
    AlluxioURI alluxioUri = mUdbContext.getTableLocation(tableName);
    String hiveUfsUri = table.getSd().getLocation();

    try {
      PathTranslator pathTranslator = new PathTranslator();
      ufsUri = new AlluxioURI(table.getSd().getLocation());
      pathTranslator.addMapping(mountAlluxioPath(tableName, ufsUri, alluxioUri), hiveUfsUri);

      for (Partition part : partitions) {
        AlluxioURI partitionUri;
        if (part.getSd() != null && part.getSd().getLocation() != null
            && ufsUri.isAncestorOf(partitionUri = new AlluxioURI(part.getSd().getLocation()))) {
          hiveUfsUri = part.getSd().getLocation();
          String partName = part.getValues().toString();
          try {
            partName = Warehouse.makePartName(table.getPartitionKeys(), part.getValues());
          } catch (MetaException e) {
            LOG.warn("Error making partition name for table {}, partition {}", tableName,
                part.getValues().toString());
          }
          alluxioUri = new AlluxioURI(PathUtils.concatPath(
              mUdbContext.getTableLocation(tableName).getPath(), partName));

          // mount partition path if it is not already mounted as part of the table path mount
          pathTranslator.addMapping(mountAlluxioPath(tableName, partitionUri, alluxioUri),
              hiveUfsUri);
        }
      }
      return pathTranslator;
    } catch (AlluxioException e) {
      throw new IOException(
          "Failed to mount table location. tableName: " + tableName
              + " hiveUfsLocation: " + hiveUfsUri
              + " AlluxioLocation: " + alluxioUri
              + " error: " + e.getMessage(), e);
    }
  }

  @Override
  public UdbTable getTable(String tableName) throws IOException {
    try {
      Table table;
      List<Partition> partitions;
      List<ColumnStatisticsObj> columnStats;
      List<String> partitionColumns;
      Map<String, List<ColumnStatisticsInfo>> statsMap;
      // perform all the hive client operations, and release the client early.
      try (CloseableResource<IMetaStoreClient> client = mClientPool.acquireClientResource()) {
        table = client.get().getTable(mHiveDbName, tableName);

        // Potentially expensive call
        partitions = client.get().listPartitions(mHiveDbName, table.getTableName(), (short) -1);

        List<String> colNames = table.getSd().getCols().stream().map(FieldSchema::getName)
            .collect(Collectors.toList());
        columnStats = client.get().getTableColumnStatistics(mHiveDbName, tableName, colNames);

        // construct the partition statistics
        List<String> dataColumns = table.getSd().getCols().stream()
            .map(org.apache.hadoop.hive.metastore.api.FieldSchema::getName)
            .collect(Collectors.toList());
        partitionColumns = table.getPartitionKeys().stream()
            .map(org.apache.hadoop.hive.metastore.api.FieldSchema::getName)
            .collect(Collectors.toList());
        List<String> partitionNames = partitions.stream()
            .map(partition -> FileUtils.makePartName(partitionColumns, partition.getValues()))
            .collect(Collectors.toList());
        statsMap = client.get()
            .getPartitionColumnStatistics(mHiveDbName, tableName, partitionNames, dataColumns)
            .entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey,
                e -> e.getValue().stream().map(HiveUtils::toProto).collect(Collectors.toList()),
                (e1, e2) -> e2));
      }

      PathTranslator pathTranslator = mountAlluxioPaths(table, partitions);
      List<ColumnStatisticsInfo> colStats =
          columnStats.stream().map(HiveUtils::toProto).collect(Collectors.toList());
      // construct table layout
      PartitionInfo partitionInfo = PartitionInfo.newBuilder()
          .setDbName(getUdbContext().getDbName())
          .setTableName(tableName)
          .addAllDataCols(HiveUtils.toProto(table.getSd().getCols()))
          .setStorage(HiveUtils.toProto(table.getSd(), pathTranslator))
          .putAllParameters(table.getParameters())
          // ignore partition name
          .build();
      Layout layout = Layout.newBuilder()
          .setLayoutType(HiveLayout.TYPE)
          .setLayoutData(partitionInfo.toByteString())
          // ignore spec and statistics for table layout
          .build();

      // create udb partitions info
      List<UdbPartition> udbPartitions = new ArrayList<>();
      if (partitionColumns.isEmpty()) {
        // unpartitioned table, generate a partition
        PartitionInfo.Builder pib = PartitionInfo.newBuilder()
            .setDbName(getUdbContext().getDbName())
            .setTableName(tableName)
            .addAllDataCols(HiveUtils.toProto(table.getSd().getCols()))
            .setStorage(HiveUtils.toProto(table.getSd(), pathTranslator))
            .setPartitionName(tableName)
            .putAllParameters(table.getParameters());
        udbPartitions.add(new HivePartition(
            new HiveLayout(pib.build(), Collections.emptyList())));
      } else {
        for (Partition partition : partitions) {
          String partName = FileUtils.makePartName(partitionColumns, partition.getValues());
          PartitionInfo.Builder pib = PartitionInfo.newBuilder()
              .setDbName(getUdbContext().getDbName())
              .setTableName(tableName)
              .addAllDataCols(HiveUtils.toProto(partition.getSd().getCols()))
              .setStorage(HiveUtils.toProto(partition.getSd(), pathTranslator))
              .setPartitionName(partName)
              .putAllParameters(partition.getParameters());
          if (partition.getValues() != null) {
            pib.addAllValues(partition.getValues());
          }
          udbPartitions.add(new HivePartition(new HiveLayout(pib.build(),
              statsMap.getOrDefault(partName, Collections.emptyList()))));
        }
      }

      return new HiveTable(tableName, HiveUtils.toProtoSchema(table.getSd().getCols()), colStats,
          HiveUtils.toProto(table.getPartitionKeys()), udbPartitions, layout, table);
    } catch (NoSuchObjectException e) {
      throw new NotFoundException("Table " + tableName + " does not exist.", e);
    } catch (TException e) {
      throw new IOException("Failed to get table: " + tableName + " error: " + e.getMessage(), e);
    }
  }
}