/*
 * Copyright (2021) The Delta Lake Project Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.delta

// scalastyle:off import.ordering.noEmptyLine
import java.nio.file.FileAlreadyExistsException
import java.util.{ConcurrentModificationException, UUID}
import java.util.concurrent.TimeUnit.NANOSECONDS

import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, HashSet}
import scala.util.control.NonFatal

import com.databricks.spark.util.TagDefinitions.TAG_LOG_STORE_CLASS
import org.apache.spark.sql.delta.DeltaOperations.{ChangeColumn, CreateTable, Operation, ReplaceColumns, ReplaceTable, UpdateSchema}
import org.apache.spark.sql.delta.RowId.RowTrackingMetadataDomain
import org.apache.spark.sql.delta.actions._
import org.apache.spark.sql.delta.catalog.DeltaTableV2
import org.apache.spark.sql.delta.commands.DeletionVectorUtils
import org.apache.spark.sql.delta.commands.cdc.CDCReader
import org.apache.spark.sql.delta.files._
import org.apache.spark.sql.delta.hooks.{CheckpointHook, GenerateSymlinkManifest, IcebergConverterHook, PostCommitHook}
import org.apache.spark.sql.delta.implicits.addFileEncoder
import org.apache.spark.sql.delta.metering.DeltaLogging
import org.apache.spark.sql.delta.schema.{SchemaMergingUtils, SchemaUtils}
import org.apache.spark.sql.delta.sources.DeltaSQLConf
import org.apache.spark.sql.delta.stats._
import org.apache.hadoop.fs.Path

import org.apache.spark.SparkException
import org.apache.spark.sql.{AnalysisException, Column, DataFrame, SparkSession}
import org.apache.spark.sql.catalyst.catalog.CatalogTable
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.types.DataTypeUtils.toAttributes
import org.apache.spark.sql.catalyst.util.{CharVarcharUtils, ResolveDefaultColumns}
import org.apache.spark.sql.types.{StructField, StructType}
import org.apache.spark.util.{Clock, Utils}

/** Record metrics about a successful commit. */
case class CommitStats(
  /** The version read by the txn when it starts. */
  startVersion: Long,
  /** The version committed by the txn. */
  commitVersion: Long,
  /** The version read by the txn right after it commits. It usually equals to commitVersion,
   * but can be larger than commitVersion when there are concurrent commits. */
  readVersion: Long,
  txnDurationMs: Long,
  commitDurationMs: Long,
  fsWriteDurationMs: Long,
  stateReconstructionDurationMs: Long,
  numAdd: Int,
  numRemove: Int,
  /** The number of [[SetTransaction]] actions in the committed actions. */
  numSetTransaction: Int,
  bytesNew: Long,
  /** The number of files in the table as of version `readVersion`. */
  numFilesTotal: Long,
  /** The table size in bytes as of version `readVersion`. */
  sizeInBytesTotal: Long,
  /** The number and size of CDC files added in this operation. */
  numCdcFiles: Long,
  cdcBytesNew: Long,
  /** The protocol as of version `readVersion`. */
  protocol: Protocol,
  /** The size of the newly committed (usually json) file */
  commitSizeBytes: Long,
  /** The size of the checkpoint committed, if present */
  checkpointSizeBytes: Long,
  totalCommitsSizeSinceLastCheckpoint: Long,
  /** Will we attempt a checkpoint after this commit is completed */
  checkpointAttempt: Boolean,
  info: CommitInfo,
  newMetadata: Option[Metadata],
  numAbsolutePathsInAdd: Int,
  numDistinctPartitionsInAdd: Int,
  numPartitionColumnsInTable: Int,
  isolationLevel: String,
  fileSizeHistogram: Option[FileSizeHistogram] = None,
  addFilesHistogram: Option[FileSizeHistogram] = None,
  removeFilesHistogram: Option[FileSizeHistogram] = None,
  numOfDomainMetadatas: Long = 0,
  txnId: Option[String] = None
)

/**
 * Represents the partition and data predicates of a query on a Delta table.
 *
 * Partition predicates can either reference the table's logical partition columns, or the
 * physical [[AddFile]]'s schema. When a predicate refers to the logical partition columns it needs
 * to be rewritten to be over the [[AddFile]]'s schema before filtering files. This is indicated
 * with shouldRewriteFilter=true.
 *
 * Currently the only path for a predicate with shouldRewriteFilter=false is through DPO
 * (dynamic partition overwrite) since we filter directly on [[AddFile.partitionValues]].
 *
 * For example, consider a table with the schema below and partition column "a"
 * |-- a: integer {physicalName = "XX"}
 * |-- b: integer {physicalName = "YY"}
 *
 * An example of a predicate that needs to be written is: (a = 0)
 * Before filtering the [[AddFile]]s, this predicate needs to be rewritten to:
 * (partitionValues.XX = 0)
 *
 * An example of a predicate that does not need to be rewritten is:
 * (partitionValues = Map(XX -> 0))
 */
private[delta] case class DeltaTableReadPredicate(
    partitionPredicates: Seq[Expression] = Seq.empty,
    dataPredicates: Seq[Expression] = Seq.empty,
    shouldRewriteFilter: Boolean = true) {

  val partitionPredicate: Expression =
    partitionPredicates.reduceLeftOption(And).getOrElse(Literal.TrueLiteral)
}

  /**
 * Used to perform a set of reads in a transaction and then commit a set of updates to the
 * state of the log.  All reads from the [[DeltaLog]], MUST go through this instance rather
 * than directly to the [[DeltaLog]] otherwise they will not be check for logical conflicts
 * with concurrent updates.
 *
 * This class is not thread-safe.
 *
 * @param deltaLog The Delta Log for the table this transaction is modifying.
 * @param snapshot The snapshot that this transaction is reading at.
 */
class OptimisticTransaction(
    override val deltaLog: DeltaLog,
    override val catalogTable: Option[CatalogTable],
    override val snapshot: Snapshot)
  extends OptimisticTransactionImpl
  with DeltaLogging {
  def this(
      deltaLog: DeltaLog,
      catalogTable: Option[CatalogTable],
      snapshotOpt: Option[Snapshot] = None) =
    this(deltaLog, catalogTable, snapshotOpt.getOrElse(deltaLog.update()))
}

object OptimisticTransaction {

  private val active = new ThreadLocal[OptimisticTransaction]

  /** Get the active transaction */
  def getActive(): Option[OptimisticTransaction] = Option(active.get())

  /**
   * Runs the passed block of code with the given active transaction. This fails if a transaction is
   * already active unless `overrideExistingTransaction` is set.
   */
  def withActive[T](
      activeTransaction: OptimisticTransaction,
      overrideExistingTransaction: Boolean = false)(block: => T): T = {
    val original = getActive()
    if (overrideExistingTransaction) {
      clearActive()
    }
    setActive(activeTransaction)
    try {
      block
    } finally {
      clearActive()
      if (original.isDefined) {
        setActive(original.get)
      }
    }
  }

  /**
   * Sets a transaction as the active transaction.
   *
   * @note This is not meant for being called directly, only from
   *       `OptimisticTransaction.withNewTransaction`. Use that to create and set active txns.
   */
  private[delta] def setActive(txn: OptimisticTransaction): Unit = {
    if (active.get != null) {
      throw DeltaErrors.activeTransactionAlreadySet()
    }
    active.set(txn)
  }

  /**
   * Clears the active transaction as the active transaction.
   *
   * @note This is not meant for being called directly, `OptimisticTransaction.withNewTransaction`.
   */
  private[delta] def clearActive(): Unit = {
    active.set(null)
  }
}

/**
 * Used to perform a set of reads in a transaction and then commit a set of updates to the
 * state of the log.  All reads from the [[DeltaLog]], MUST go through this instance rather
 * than directly to the [[DeltaLog]] otherwise they will not be check for logical conflicts
 * with concurrent updates.
 *
 * This trait is not thread-safe.
 */
trait OptimisticTransactionImpl extends TransactionalWrite
  with SQLMetricsReporting
  with DeltaScanGenerator
  with DeltaLogging {

  import org.apache.spark.sql.delta.util.FileNames._

  val deltaLog: DeltaLog
  val catalogTable: Option[CatalogTable]
  val snapshot: Snapshot
  def clock: Clock = deltaLog.clock

  protected def spark = SparkSession.active

  /** Tracks the appIds that have been seen by this transaction. */
  protected val readTxn = new ArrayBuffer[String]

  /**
   * Tracks the data that could have been seen by recording the partition
   * predicates by which files have been queried by this transaction.
   */
  protected val readPredicates = new ArrayBuffer[DeltaTableReadPredicate]

  /** Tracks specific files that have been seen by this transaction. */
  protected val readFiles = new HashSet[AddFile]

  /** Whether the whole table was read during the transaction. */
  protected var readTheWholeTable = false

  /** Tracks if this transaction has already committed. */
  protected var committed = false

  /**
   * Stores the updated metadata (if any) that will result from this txn.
   *
   * This is just one way to change metadata.
   * New metadata can also be added during commit from actions.
   * But metadata should *not* be updated via both paths.
   */
  protected var newMetadata: Option[Metadata] = None

  /** Stores the updated protocol (if any) that will result from this txn. */
  protected var newProtocol: Option[Protocol] = None

  /** The transaction start time. */
  protected val txnStartNano = System.nanoTime()

  override val snapshotToScan: Snapshot = snapshot

  /**
   * Tracks the first-access snapshots of other Delta logs read by this transaction.
   * The snapshots are keyed by the log's unique id.
   */
  protected var readSnapshots = new java.util.concurrent.ConcurrentHashMap[(String, Path), Snapshot]

  /** The transaction commit start time. */
  protected var commitStartNano = -1L

  /** The transaction commit end time. */
  protected var commitEndNano = -1L;

  protected var commitInfo: CommitInfo = _

  /** Whether the txn should trigger a checkpoint after the commit */
  private[delta] var needsCheckpoint = false

  // Whether this transaction is creating a new table.
  private var isCreatingNewTable: Boolean = false

  // Whether this transaction is overwriting the existing schema (i.e. overwriteSchema = true).
  // When overwriting schema (and data) of a table, `isCreatingNewTable` should not be true.
  private var isOverwritingSchema: Boolean = false

  // Whether this is a transaction that can select any new protocol, potentially downgrading
  // the existing protocol of the table during REPLACE table operations.
  private def canAssignAnyNewProtocol: Boolean =
    readVersion == -1 ||
      (isCreatingNewTable && spark.conf.get(DeltaSQLConf.REPLACE_TABLE_PROTOCOL_DOWNGRADE_ALLOWED))

  /**
   * Tracks the start time since we started trying to write a particular commit.
   * Used for logging duration of retried transactions.
   */
  protected var commitAttemptStartTime: Long = _

  /**
   * Tracks actions within the transaction, will commit along with the passed-in actions in the
   * commit function.
   */
  protected val actions = new ArrayBuffer[Action]

  /**
   * Record a SetTransaction action that will be committed as part of this transaction.
   */
  def updateSetTransaction(appId: String, version: Long, lastUpdate: Option[Long]): Unit = {
    actions += SetTransaction(appId, version, lastUpdate)
  }

  /** The version that this transaction is reading from. */
  def readVersion: Long = snapshot.version

  /** Creates new metadata with global Delta configuration defaults. */
  private def withGlobalConfigDefaults(metadata: Metadata): Metadata = {
    val conf = spark.sessionState.conf
    metadata.copy(configuration = DeltaConfigs.mergeGlobalConfigs(
      conf, metadata.configuration))
  }

  protected val postCommitHooks = new ArrayBuffer[PostCommitHook]()
  // The CheckpointHook will only checkpoint if necessary, so always register it to run.
  registerPostCommitHook(CheckpointHook)
  registerPostCommitHook(IcebergConverterHook)

  /** The protocol of the snapshot that this transaction is reading at. */
  def protocol: Protocol = newProtocol.getOrElse(snapshot.protocol)

  /** Start time of txn in nanoseconds */
  def txnStartTimeNs: Long = txnStartNano

  /** Unique identifier for the transaction */
  val txnId = UUID.randomUUID().toString

  /** Whether to check unsupported data type when updating the table schema */
  protected var checkUnsupportedDataType: Boolean =
    spark.sessionState.conf.getConf(DeltaSQLConf.DELTA_SCHEMA_TYPE_CHECK)

  // Some operations (e.g. stats collection) may set files with DVs back to tight bounds.
  // In that case they need to skip this check.
  protected var checkDeletionVectorFilesHaveWideBounds: Boolean = true
  /**
   * Disable the check that ensures that all files with DVs added have tightBounds set to false.
   *
   * This is necessary when recomputing the stats on a table with DVs.
   */
  def disableDeletionVectorFilesHaveWideBoundsCheck(): Unit = {
    checkDeletionVectorFilesHaveWideBounds = false
  }


  /**
   * The logSegment of the snapshot prior to the commit.
   * Will be updated only when retrying due to a conflict.
   */
  private[delta] var preCommitLogSegment: LogSegment =
    snapshot.logSegment.copy(checkpointProvider = snapshot.checkpointProvider)

  /** The end to end execution time of this transaction. */
  def txnExecutionTimeMs: Option[Long] = if (commitEndNano == -1) {
    None
  } else {
    Some(NANOSECONDS.toMillis((commitEndNano - txnStartNano)))
  }

  /** Gets the stats collector for the table at the snapshot this transaction has. */
  def statsCollector: Column = snapshot.statsCollector

  /**
   * Returns the metadata for this transaction. The metadata refers to the metadata of the snapshot
   * at the transaction's read version unless updated during the transaction.
   */
  def metadata: Metadata = newMetadata.getOrElse(snapshot.metadata)

  /**
   * Records an update to the metadata that should be committed with this transaction.
   * Note that this must be done before writing out any files so that file writing
   * and checks happen with the final metadata for the table.
   *
   * IMPORTANT: It is the responsibility of the caller to ensure that files currently
   * present in the table are still valid under the new metadata.
   */
  def updateMetadata(
      proposedNewMetadata: Metadata,
      ignoreDefaultProperties: Boolean = false): Unit = {
    assert(!hasWritten,
      "Cannot update the metadata in a transaction that has already written data.")
    assert(newMetadata.isEmpty,
      "Cannot change the metadata more than once in a transaction.")
    updateMetadataInternal(proposedNewMetadata, ignoreDefaultProperties)
  }

  /**
   * Can this transaction still update the metadata?
   * This is allowed only once per transaction.
   */
  def canUpdateMetadata: Boolean = {
    !hasWritten && newMetadata.isEmpty
  }

  /**
   * This updates the protocol for the table with a given protocol.
   * Note that the protocol set by this method can be overwritten by other methods,
   * such as [[updateMetadata]].
   */
  def updateProtocol(protocol: Protocol): Unit = {
      newProtocol = Some(protocol)
  }

  /**
   * Do the actual checks and works to update the metadata and save it into the `newMetadata`
   * field, which will be added to the actions to commit in [[prepareCommit]].
   */
  protected def updateMetadataInternal(
      proposedNewMetadata: Metadata,
      ignoreDefaultProperties: Boolean): Unit = {
    var newMetadataTmp = proposedNewMetadata
    // Validate all indexed columns are inside table's schema.
    StatisticsCollection.validateDeltaStatsColumns(newMetadataTmp)
    if (readVersion == -1 || isCreatingNewTable) {
      // We need to ignore the default properties when trying to create an exact copy of a table
      // (as in CLONE and SHALLOW CLONE).
      if (!ignoreDefaultProperties) {
        newMetadataTmp = withGlobalConfigDefaults(newMetadataTmp)
      }
      isCreatingNewTable = true
    }
    val protocolBeforeUpdate = protocol
    // The `.schema` cannot be generated correctly unless the column mapping metadata is correctly
    // filled for all the fields. Therefore, the column mapping changes need to happen first.
    newMetadataTmp = DeltaColumnMapping.verifyAndUpdateMetadataChange(
      deltaLog,
      protocolBeforeUpdate,
      snapshot.metadata,
      newMetadataTmp,
      isCreatingNewTable,
      isOverwritingSchema)

    if (newMetadataTmp.schemaString != null) {
      // Replace CHAR and VARCHAR with StringType
      val schema = CharVarcharUtils.replaceCharVarcharWithStringInSchema(
        newMetadataTmp.schema)
      newMetadataTmp = newMetadataTmp.copy(schemaString = schema.json)
    }

    newMetadataTmp = if (snapshot.metadata.schemaString == newMetadataTmp.schemaString) {
      // Shortcut when the schema hasn't changed to avoid generating spurious schema change logs.
      // It's fine if two different but semantically equivalent schema strings skip this special
      // case - that indicates that something upstream attempted to do a no-op schema change, and
      // we'll just end up doing a bit of redundant work in the else block.
      newMetadataTmp
    } else {
      val fixedSchema = SchemaUtils.removeUnenforceableNotNullConstraints(
        newMetadataTmp.schema, spark.sessionState.conf).json
      newMetadataTmp.copy(schemaString = fixedSchema)
    }


    if (canAssignAnyNewProtocol) {
      // Check for the new protocol version after the removal of the unenforceable not null
      // constraints
      newProtocol = Some(Protocol.forNewTable(spark, Some(newMetadataTmp)))
    } else if (newMetadataTmp.configuration.contains(Protocol.MIN_READER_VERSION_PROP) ||
      newMetadataTmp.configuration.contains(Protocol.MIN_WRITER_VERSION_PROP)) {
      // Table features Part 1: bump protocol version numbers
      //
      // Collect new reader and writer versions from table properties, which could be provided by
      // the user in `ALTER TABLE TBLPROPERTIES` or copied over from session defaults.
      val readerVersionAsTableProp =
        Protocol.getReaderVersionFromTableConf(newMetadataTmp.configuration)
          .getOrElse(protocolBeforeUpdate.minReaderVersion)
      val writerVersionAsTableProp =
        Protocol.getWriterVersionFromTableConf(newMetadataTmp.configuration)
          .getOrElse(protocolBeforeUpdate.minWriterVersion)

      val newProtocolForLatestMetadata =
        Protocol(readerVersionAsTableProp, writerVersionAsTableProp)
      val proposedNewProtocol = protocolBeforeUpdate.merge(newProtocolForLatestMetadata)

      if (proposedNewProtocol != protocolBeforeUpdate) {
        // The merged protocol has higher versions and/or supports more features.
        // It's a valid upgrade.
        newProtocol = Some(proposedNewProtocol)
      } else {
        // The merged protocol is identical to the original one. Two possibilities:
        // (1) the provided versions are lower than the original one, and all features supported by
        //     the provided versions are already supported. This is a no-op.
        if (readerVersionAsTableProp < protocolBeforeUpdate.minReaderVersion ||
          writerVersionAsTableProp < protocolBeforeUpdate.minWriterVersion) {
          recordProtocolChanges(
            "delta.protocol.downgradeIgnored",
            fromProtocol = protocolBeforeUpdate,
            toProtocol = newProtocolForLatestMetadata,
            isCreatingNewTable = false)
        } else {
          // (2) the new protocol versions is identical to the existing versions. Also a no-op.
        }
      }
    }

    newMetadataTmp = if (isCreatingNewTable) {
      // Creating a new table will drop all existing data, so we don't need to fix the old
      // metadata.
      newMetadataTmp
    } else {
      // This is not a new table. The new schema may be merged from the existing schema. We
      // decide whether we should keep the Generated or IDENTITY columns by checking whether the
      // protocol satisfies the requirements.
      val keepGeneratedColumns =
        GeneratedColumn.satisfyGeneratedColumnProtocol(protocolBeforeUpdate)
      val keepIdentityColumns =
        ColumnWithDefaultExprUtils.satisfiesIdentityColumnProtocol(protocolBeforeUpdate)
      if (keepGeneratedColumns && keepIdentityColumns) {
        // If a protocol satisfies both requirements, we do nothing here.
        newMetadataTmp
      } else {
        // As the protocol doesn't match, this table is created by an old version that doesn't
        // support generated columns or identity columns. We should remove the generation
        // expressions to fix the schema to avoid bumping the writer version incorrectly.
        val newSchema = ColumnWithDefaultExprUtils.removeDefaultExpressions(
          newMetadataTmp.schema,
          keepGeneratedColumns = keepGeneratedColumns,
          keepIdentityColumns = keepIdentityColumns)
        if (newSchema ne newMetadataTmp.schema) {
          newMetadataTmp.copy(schemaString = newSchema.json)
        } else {
          newMetadataTmp
        }
      }
    }

    // Table features Part 2: add manually-supported features specified in table properties, aka
    // those start with [[FEATURE_PROP_PREFIX]].
    //
    // This transaction's new metadata might contain some table properties to support some
    // features (props start with [[FEATURE_PROP_PREFIX]]). We silently add them to the `protocol`
    // action, and bump the protocol version to (3, 7) or (_, 7), depending on the existence of
    // any reader-writer feature.
    val newProtocolBeforeAddingFeatures = newProtocol.getOrElse(protocolBeforeUpdate)
    val newFeaturesFromTableConf =
      TableFeatureProtocolUtils.getSupportedFeaturesFromTableConfigs(newMetadataTmp.configuration)
    val readerVersionForNewProtocol = {
      // All features including those required features are considered to decide reader version.
      if (Protocol()
        .withFeatures(newFeaturesFromTableConf)
        .readerAndWriterFeatureNames
        .flatMap(TableFeature.featureNameToFeature)
        .exists(_.isReaderWriterFeature)) {
        TableFeatureProtocolUtils.TABLE_FEATURES_MIN_READER_VERSION
      } else {
        newProtocolBeforeAddingFeatures.minReaderVersion
      }
    }
    val existingFeatureNames = newProtocolBeforeAddingFeatures.readerAndWriterFeatureNames
    if (!newFeaturesFromTableConf.map(_.name).subsetOf(existingFeatureNames)) {
      newProtocol = Some(
        Protocol(
          readerVersionForNewProtocol,
          TableFeatureProtocolUtils.TABLE_FEATURES_MIN_WRITER_VERSION)
          .merge(newProtocolBeforeAddingFeatures)
          .withFeatures(newFeaturesFromTableConf))
    }

    // We are done with protocol versions and features, time to remove related table properties.
    val configsWithoutProtocolProps = newMetadataTmp.configuration.filterNot {
      case (k, _) => TableFeatureProtocolUtils.isTableProtocolProperty(k)
    }
    newMetadataTmp = newMetadataTmp.copy(configuration = configsWithoutProtocolProps)

    // Table features Part 3: add automatically-enabled features by looking at the new table
    // metadata.
    //
    // This code path is for existing tables and during `REPLACE` if the downgrade flag is not set.
    // The new table case has been handled by [[Protocol.forNewTable]] earlier in this method.
    if (!canAssignAnyNewProtocol) {
      setNewProtocolWithFeaturesEnabledByMetadata(newMetadataTmp)
    }

    newMetadataTmp = MaterializedRowId.updateMaterializedColumnName(
      protocol, oldMetadata = snapshot.metadata, newMetadataTmp)
    newMetadataTmp = MaterializedRowCommitVersion.updateMaterializedColumnName(
      protocol, oldMetadata = snapshot.metadata, newMetadataTmp)

    RowId.verifyMetadata(
      snapshot.protocol, protocol, snapshot.metadata, newMetadataTmp, isCreatingNewTable)

    assertMetadata(newMetadataTmp)
    logInfo(s"Updated metadata from ${newMetadata.getOrElse("-")} to $newMetadataTmp")
    newMetadata = Some(newMetadataTmp)
  }

  /**
   * Records an update to the metadata that should be committed with this transaction and when
   * this transaction is logically creating a new table, e.g. replacing a previous table with new
   * metadata. Note that this must be done before writing out any files so that file writing
   * and checks happen with the final metadata for the table.
   * IMPORTANT: It is the responsibility of the caller to ensure that files currently
   * present in the table are still valid under the new metadata.
   */
  def updateMetadataForNewTable(metadata: Metadata): Unit = {
    isCreatingNewTable = true
    updateMetadata(metadata)
  }

  /**
   * Records an update to the metadata that should be committed with this transaction and when
   * this transaction is attempt to overwrite the data and schema using .mode('overwrite') and
   * .option('overwriteSchema', true).
   * REPLACE the table is not considered in this category, because that is logically equivalent
   * to DROP and RECREATE the table.
   */
  def updateMetadataForTableOverwrite(proposedNewMetadata: Metadata): Unit = {
    isOverwritingSchema = true
    updateMetadata(proposedNewMetadata)
  }

  protected def assertMetadata(metadata: Metadata): Unit = {
    assert(!CharVarcharUtils.hasCharVarchar(metadata.schema),
      "The schema in Delta log should not contain char/varchar type.")
    SchemaMergingUtils.checkColumnNameDuplication(metadata.schema, "in the metadata update")
    if (metadata.columnMappingMode == NoMapping) {
      SchemaUtils.checkSchemaFieldNames(metadata.dataSchema, metadata.columnMappingMode)
      val partitionColCheckIsFatal =
        spark.sessionState.conf.getConf(DeltaSQLConf.DELTA_PARTITION_COLUMN_CHECK_ENABLED)
      try {
        SchemaUtils.checkFieldNames(metadata.partitionColumns)
      } catch {
        case e: AnalysisException =>
          recordDeltaEvent(
            deltaLog,
            "delta.schema.invalidPartitionColumn",
            data = Map(
              "checkEnabled" -> partitionColCheckIsFatal,
              "columns" -> metadata.partitionColumns
            )
          )
          if (partitionColCheckIsFatal) throw DeltaErrors.invalidPartitionColumn(e)
      }
    } else {
      DeltaColumnMapping.checkColumnIdAndPhysicalNameAssignments(metadata)
    }

    if (GeneratedColumn.hasGeneratedColumns(metadata.schema)) {
      recordDeltaOperation(deltaLog, "delta.generatedColumns.check") {
        GeneratedColumn.validateGeneratedColumns(spark, metadata.schema)
      }
      recordDeltaEvent(deltaLog, "delta.generatedColumns.definition")
    }

    if (checkUnsupportedDataType) {
      val unsupportedTypes = SchemaUtils.findUnsupportedDataTypes(metadata.schema)
      if (unsupportedTypes.nonEmpty) {
        throw DeltaErrors.unsupportedDataTypes(unsupportedTypes.head, unsupportedTypes.tail: _*)
      }
    }

    if (spark.conf.get(DeltaSQLConf.DELTA_TABLE_PROPERTY_CONSTRAINTS_CHECK_ENABLED)) {
      Protocol.assertTablePropertyConstraintsSatisfied(spark, metadata, snapshot)
    }
    MaterializedRowId.throwIfMaterializedColumnNameConflictsWithSchema(metadata)
    MaterializedRowCommitVersion.throwIfMaterializedColumnNameConflictsWithSchema(metadata)
  }

  private def setNewProtocolWithFeaturesEnabledByMetadata(metadata: Metadata): Unit = {
    val requiredProtocolOpt =
      Protocol.upgradeProtocolFromMetadataForExistingTable(spark, metadata, protocol)
    if (requiredProtocolOpt.isDefined) {
      newProtocol = requiredProtocolOpt
    }
  }

  /**
   * Must make sure that deletion vectors are never added to a table where that isn't allowed.
   * Note, statistics recomputation is still allowed even though DVs might be currently disabled.
   *
   * This method returns a function that can be used to validate a single Action.
   */
  protected def getAssertDeletionVectorWellFormedFunc(
      spark: SparkSession,
      op: DeltaOperations.Operation): (Action => Unit) = {
    val deletionVectorCreationAllowed =
      DeletionVectorUtils.deletionVectorsWritable(snapshot, newProtocol, newMetadata)
    val isComputeStatsOperation = op.isInstanceOf[DeltaOperations.ComputeStats]
    val commitCheckEnabled = spark.conf.get(DeltaSQLConf.DELETION_VECTORS_COMMIT_CHECK_ENABLED)

    val deletionVectorDisallowedForAddFiles =
      commitCheckEnabled && !isComputeStatsOperation && !deletionVectorCreationAllowed

    val addFileMustHaveWideBounds = deletionVectorCreationAllowed &&
      checkDeletionVectorFilesHaveWideBounds

    action => action match {
      case a: AddFile =>
        if (deletionVectorDisallowedForAddFiles && a.deletionVector != null) {
          throw DeltaErrors.addingDeletionVectorsDisallowedException()
        }
        // Protocol requirement checks:
        // 1. All files with DVs must have `stats` with `numRecords`.
        if (a.deletionVector != null && (a.stats == null || a.numPhysicalRecords.isEmpty)) {
          throw DeltaErrors.addFileWithDVsMissingNumRecordsException
        }

        // 2. All operations that add new DVs should always turn bounds to wide.
        //    Operations that only update files with existing DVs may opt-out from this rule
        //    via `disableDeletionVectorFilesHaveWideBoundsCheck()`.
        //    (e.g. stats collection, metadata-only updates.)
        //    Note, the absence of the tightBounds column when DVs exist is also an illegal state.
        if (addFileMustHaveWideBounds &&
            a.deletionVector != null &&
            // Extra inversion to also catch absent `tightBounds`.
            !a.tightBounds.contains(false)) {
          throw DeltaErrors.addFileWithDVsAndTightBoundsException()
        }
      case _ => // Not an AddFile, nothing to do.
    }
  }

  /**
   * Returns the [[DeltaScanGenerator]] for the given log, which will be used to generate
   * [[DeltaScan]]s. Every time this method is called on a log, the returned generator
   * generator will read a snapshot that is pinned on the first access for that log.
   *
   * Internally, if the given log is the same as the log associated with this
   * transaction, then it returns this transaction, otherwise it will return a snapshot of
   * given log
   */
  def getDeltaScanGenerator(index: TahoeLogFileIndex): DeltaScanGenerator = {
    if (index.deltaLog.isSameLogAs(deltaLog)) return this

    val compositeId = index.deltaLog.compositeId
    // Will be called only when the log is accessed the first time
    readSnapshots.computeIfAbsent(compositeId, _ => index.getSnapshot)
  }

  /** Returns a[[DeltaScan]] based on the given filters. */
  override def filesForScan(
    filters: Seq[Expression],
    keepNumRecords: Boolean = false
  ): DeltaScan = {
    val scan = snapshot.filesForScan(filters, keepNumRecords)
    trackReadPredicates(filters)
    trackFilesRead(scan.files)
    scan
  }

  /** Returns a[[DeltaScan]] based on the given partition filters, projections and limits. */
  override def filesForScan(
      limit: Long,
      partitionFilters: Seq[Expression]): DeltaScan = {
    partitionFilters.foreach { f =>
      assert(
        DeltaTableUtils.isPredicatePartitionColumnsOnly(f, metadata.partitionColumns, spark),
        s"Only filters on partition columns [${metadata.partitionColumns.mkString(", ")}]" +
          s" expected, found $f")
    }
    val scan = snapshot.filesForScan(limit, partitionFilters)
    trackReadPredicates(partitionFilters, partitionOnly = true)
    trackFilesRead(scan.files)
    scan
  }

  override def filesWithStatsForScan(partitionFilters: Seq[Expression]): DataFrame = {
    val metadata = snapshot.filesWithStatsForScan(partitionFilters)
    trackReadPredicates(partitionFilters, partitionOnly = true)
    trackFilesRead(filterFiles(partitionFilters))
    metadata
  }

  /** Returns files matching the given predicates. */
  def filterFiles(): Seq[AddFile] = filterFiles(Seq(Literal.TrueLiteral))

  /** Returns files matching the given predicates. */
  def filterFiles(filters: Seq[Expression], keepNumRecords: Boolean = false): Seq[AddFile] = {
    val scan = snapshot.filesForScan(filters, keepNumRecords)
    trackReadPredicates(filters)
    trackFilesRead(scan.files)
    scan.files
  }

  /**
   * Returns files within the given partitions.
   *
   * `partitions` is a set of the `partitionValues` stored in [[AddFile]]s. This means they refer to
   * the physical column names, and values are stored as strings.
   * */
  def filterFiles(partitions: Set[Map[String, String]]): Seq[AddFile] = {
    import org.apache.spark.sql.functions.col
    val df = snapshot.allFiles.toDF()
    val isFileInTouchedPartitions =
      DeltaUDF.booleanFromMap(partitions.contains)(col("partitionValues"))
    val filteredFiles = df
      .filter(isFileInTouchedPartitions)
      .withColumn("stats", DataSkippingReader.nullStringLiteral)
      .as[AddFile]
      .collect()
    trackReadPredicates(
      Seq(isFileInTouchedPartitions.expr), partitionOnly = true, shouldRewriteFilter = false)
    filteredFiles
  }

  /** Mark the entire table as tainted by this transaction. */
  def readWholeTable(): Unit = {
    trackReadPredicates(Seq.empty)
    readTheWholeTable = true
  }

  /** Mark the given files as read within this transaction. */
  def trackFilesRead(files: Seq[AddFile]): Unit = {
    readFiles ++= files
  }

  /** Mark the predicates that have been queried by this transaction. */
  def trackReadPredicates(
      filters: Seq[Expression],
      partitionOnly: Boolean = false,
      shouldRewriteFilter: Boolean = true): Unit = {
    val (partitionFilters, dataFilters) = if (partitionOnly) {
      (filters, Seq.empty[Expression])
    } else {
      filters.partition { f =>
        DeltaTableUtils.isPredicatePartitionColumnsOnly(f, metadata.partitionColumns, spark)
      }
    }

    readPredicates += DeltaTableReadPredicate(
      partitionPredicates = partitionFilters,
      dataPredicates = dataFilters,
      shouldRewriteFilter = shouldRewriteFilter)
  }

  /**
   * Returns the latest version that has committed for the idempotent transaction with given `id`.
   */
  def txnVersion(id: String): Long = {
    readTxn += id
    snapshot.transactions.getOrElse(id, -1L)
  }

  /**
   * Return the operation metrics for the operation if it is enabled
   */
  def getOperationMetrics(op: Operation): Option[Map[String, String]] = {
    if (spark.conf.get(DeltaSQLConf.DELTA_HISTORY_METRICS_ENABLED)) {
      Some(getMetricsForOperation(op))
    } else {
      None
    }
  }

  /**
   * Return the user-defined metadata for the operation.
   */
  def getUserMetadata(op: Operation): Option[String] = {
    // option wins over config if both are set
    op.userMetadata match {
      case data @ Some(_) => data
      case None => spark.sessionState.conf.getConf(DeltaSQLConf.DELTA_USER_METADATA)
    }
  }

  /**
   * Checks if the new schema contains any CDC columns (which is invalid) and throws the appropriate
   * error
   */
  protected def performCdcMetadataCheck(): Unit = {
    if (newMetadata.nonEmpty) {
      if (CDCReader.isCDCEnabledOnTable(newMetadata.get, spark)) {
        val schema = newMetadata.get.schema.fieldNames
        val reservedColumnsUsed = CDCReader.cdcReadSchema(new StructType()).fieldNames
          .intersect(schema)
        if (reservedColumnsUsed.length > 0) {
          if (!CDCReader.isCDCEnabledOnTable(snapshot.metadata, spark)) {
            // cdc was not enabled previously but reserved columns are present in the new schema.
            throw DeltaErrors.tableAlreadyContainsCDCColumns(reservedColumnsUsed)
          } else {
            // cdc was enabled but reserved columns are present in the new metadata.
            throw DeltaErrors.cdcColumnsInData(reservedColumnsUsed)
          }
        }
      }
    }
  }

  /**
   * Checks if the passed-in actions have internal SetTransaction conflicts, will throw exceptions
   * in case of conflicts. This function will also remove duplicated [[SetTransaction]]s.
   */
  protected def checkForSetTransactionConflictAndDedup(actions: Seq[Action]): Seq[Action] = {
    val finalActions = new ArrayBuffer[Action]
    val txnIdToVersionMap = new mutable.HashMap[String, Long].empty
    for (action <- actions) {
      action match {
        case st: SetTransaction =>
          txnIdToVersionMap.get(st.appId).map { version =>
            if (version != st.version) {
              throw DeltaErrors.setTransactionVersionConflict(st.appId, version, st.version)
            }
          } getOrElse {
            txnIdToVersionMap += (st.appId -> st.version)
            finalActions += action
          }
        case _ => finalActions += action
      }
    }
    finalActions.toSeq
  }

  /**
   * We want to future-proof and explicitly block any occurrences of
   * - table has CDC enabled and there are FileActions to write, AND
   * - table has column mapping enabled and there is a column mapping related metadata action
   *
   * This is because the semantics for this combination of features and file changes is undefined.
   */
  private def performCdcColumnMappingCheck(
      actions: Seq[Action],
      op: DeltaOperations.Operation): Unit = {
    if (newMetadata.nonEmpty) {
      val _newMetadata = newMetadata.get
      val _currentMetadata = snapshot.metadata

      val cdcEnabled = CDCReader.isCDCEnabledOnTable(_newMetadata, spark)

      val columnMappingEnabled = _newMetadata.columnMappingMode != NoMapping

      val isColumnMappingUpgrade = DeltaColumnMapping.isColumnMappingUpgrade(
        oldMode = _currentMetadata.columnMappingMode,
        newMode = _newMetadata.columnMappingMode
      )

      def dropColumnOp: Boolean = DeltaColumnMapping.isDropColumnOperation(
        _newMetadata, _currentMetadata)

      def renameColumnOp: Boolean = DeltaColumnMapping.isRenameColumnOperation(
        _newMetadata, _currentMetadata)

      def columnMappingChange: Boolean = isColumnMappingUpgrade || dropColumnOp || renameColumnOp

      def existsFileActions: Boolean = actions.exists { _.isInstanceOf[FileAction] }

      if (cdcEnabled && columnMappingEnabled && columnMappingChange && existsFileActions) {
        throw DeltaErrors.blockColumnMappingAndCdcOperation(op)
      }
    }
  }

  /**
   * Modifies the state of the log by adding a new commit that is based on a read at
   * [[readVersion]]. In the case of a conflict with a concurrent writer this
   * method will throw an exception.
   *
   * Also skips creating the commit if the configured [[IsolationLevel]] doesn't need us to record
   * the commit from correctness perspective.
   */
  def commitIfNeeded(
      actions: Seq[Action],
      op: DeltaOperations.Operation,
      tags: Map[String, String] = Map.empty): Unit = {
    commitImpl(actions, op, canSkipEmptyCommits = true, tags = tags)
  }

  /**
   * Modifies the state of the log by adding a new commit that is based on a read at
   * [[readVersion]]. In the case of a conflict with a concurrent writer this
   * method will throw an exception.
   *
   * @param actions     Set of actions to commit
   * @param op          Details of operation that is performing this transactional commit
   */
  def commit(
      actions: Seq[Action],
      op: DeltaOperations.Operation): Long = {
    commitImpl(actions, op, canSkipEmptyCommits = false, tags = Map.empty).getOrElse {
      throw new SparkException(s"Unknown error while trying to commit for operation $op")
    }
  }

  /**
   * Modifies the state of the log by adding a new commit that is based on a read at
   * [[readVersion]]. In the case of a conflict with a concurrent writer this
   * method will throw an exception.
   *
   * @param actions     Set of actions to commit
   * @param op          Details of operation that is performing this transactional commit
   * @param tags        Extra tags to set to the CommitInfo action
   */
  def commit(
      actions: Seq[Action],
      op: DeltaOperations.Operation,
      tags: Map[String, String]): Long = {
    commitImpl(actions, op, canSkipEmptyCommits = false, tags = tags).getOrElse {
      throw new SparkException(s"Unknown error while trying to commit for operation $op")
    }
  }

  @throws(classOf[ConcurrentModificationException])
  protected def commitImpl(
      actions: Seq[Action],
      op: DeltaOperations.Operation,
      canSkipEmptyCommits: Boolean,
      tags: Map[String, String]
  ): Option[Long] = recordDeltaOperation(deltaLog, "delta.commit") {
    commitStartNano = System.nanoTime()

    val (version, postCommitSnapshot, actualCommittedActions) = try {
      // Check for CDC metadata columns
      performCdcMetadataCheck()

      // Check for internal SetTransaction conflicts and dedup.
      val finalActions = checkForSetTransactionConflictAndDedup(actions ++ this.actions.toSeq)

      // Try to commit at the next version.
      val preparedActions = prepareCommit(finalActions, op)

      // Find the isolation level to use for this commit
      val isolationLevelToUse = getIsolationLevelToUse(preparedActions, op)

      // Check for duplicated [[MetadataAction]] with the same domain names and validate the table
      // feature is enabled if [[MetadataAction]] is submitted.
      val domainMetadata =
        DomainMetadataUtils.validateDomainMetadataSupportedAndNoDuplicate(finalActions, protocol)

      val isBlindAppend = {
        val dependsOnFiles = readPredicates.nonEmpty || readFiles.nonEmpty
        val onlyAddFiles =
          preparedActions.collect { case f: FileAction => f }.forall(_.isInstanceOf[AddFile])
        onlyAddFiles && !dependsOnFiles
      }

      val readRowIdHighWatermark =
        RowId.extractHighWatermark(snapshot).getOrElse(RowId.MISSING_HIGH_WATER_MARK)

      commitInfo = CommitInfo(
        clock.getTimeMillis(),
        op.name,
        op.jsonEncodedValues,
        Map.empty,
        Some(readVersion).filter(_ >= 0),
        Option(isolationLevelToUse.toString),
        Some(isBlindAppend),
        getOperationMetrics(op),
        getUserMetadata(op),
        tags = if (tags.nonEmpty) Some(tags) else None,
        txnId = Some(txnId))

      val currentTransactionInfo = CurrentTransactionInfo(
        txnId = txnId,
        readPredicates = readPredicates.toSeq,
        readFiles = readFiles.toSet,
        readWholeTable = readTheWholeTable,
        readAppIds = readTxn.toSet,
        metadata = metadata,
        protocol = protocol,
        actions = preparedActions,
        readSnapshot = snapshot,
        commitInfo = Option(commitInfo),
        readRowIdHighWatermark = readRowIdHighWatermark,
        domainMetadata = domainMetadata)

      // Register post-commit hooks if any
      lazy val hasFileActions = preparedActions.exists {
        case _: FileAction => true
        case _ => false
      }
      if (DeltaConfigs.SYMLINK_FORMAT_MANIFEST_ENABLED.fromMetaData(metadata) && hasFileActions) {
        registerPostCommitHook(GenerateSymlinkManifest)
      }

      commitAttemptStartTime = clock.getTimeMillis()
      if (preparedActions.isEmpty && canSkipEmptyCommits &&
          skipRecordingEmptyCommitAllowed(isolationLevelToUse)) {
        return None
      }

      val (commitVersion, postCommitSnapshot, updatedCurrentTransactionInfo) =
        doCommitRetryIteratively(
          getFirstAttemptVersion, currentTransactionInfo, isolationLevelToUse)
      logInfo(s"Committed delta #$commitVersion to ${deltaLog.logPath}")
      (commitVersion, postCommitSnapshot, updatedCurrentTransactionInfo.actions)
    } catch {
      case e: DeltaConcurrentModificationException =>
        recordDeltaEvent(deltaLog, "delta.commit.conflict." + e.conflictType)
        throw e
      case NonFatal(e) =>
        recordDeltaEvent(
          deltaLog, "delta.commit.failure", data = Map("exception" -> Utils.exceptionString(e)))
        throw e
    }

    runPostCommitHooks(version, postCommitSnapshot, actualCommittedActions)

    Some(version)
  }

  /** Whether to skip recording the commit in DeltaLog */
  protected def skipRecordingEmptyCommitAllowed(isolationLevelToUse: IsolationLevel): Boolean = {
    if (!spark.sessionState.conf.getConf(DeltaSQLConf.DELTA_SKIP_RECORDING_EMPTY_COMMITS)) {
      return false
    }
    // Recording of empty commits in deltalog can be skipped only for SnapshotIsolation and
    // Serializable mode.
    Seq(SnapshotIsolation, Serializable).contains(isolationLevelToUse)
  }

  /**
   * Create a large commit on the Delta log by directly writing an iterator of FileActions to the
   * LogStore. This function only commits the next possible version and will not check whether the
   * commit is retry-able. If the next version has already been committed, then this function
   * will fail.
   * This bypasses all optimistic concurrency checks. We assume that transaction conflicts should be
   * rare because this method is typically used to create new tables (e.g. CONVERT TO DELTA) or
   * apply some commands which rarely receive other transactions (e.g. CLONE/RESTORE).
   * In addition, the expectation is that the list of actions performed by the transaction
   * remains an iterator and is never materialized, given the nature of a large commit potentially
   * touching many files.
   */
  def commitLarge(
    spark: SparkSession,
    actions: Iterator[Action],
    op: DeltaOperations.Operation,
    context: Map[String, String],
    metrics: Map[String, String]): (Long, Snapshot) = {
    assert(!committed, "Transaction already committed.")
    commitStartNano = System.nanoTime()
    val attemptVersion = getFirstAttemptVersion
    try {
      val tags = Map.empty[String, String]
      val commitInfo = CommitInfo(
        time = clock.getTimeMillis(),
        operation = op.name,
        operationParameters = op.jsonEncodedValues,
        context,
        readVersion = Some(readVersion),
        isolationLevel = Some(Serializable.toString),
        isBlindAppend = Some(false),
        Some(metrics),
        userMetadata = getUserMetadata(op),
        tags = if (tags.nonEmpty) Some(tags) else None,
        txnId = Some(txnId))

      val extraActions = Seq(commitInfo, metadata)
      // We don't expect commits to have more than 2 billion actions
      var commitSize: Int = 0
      var numAbsolutePaths: Int = 0
      var numAddFiles: Int = 0
      var numRemoveFiles: Int = 0
      var numSetTransaction: Int = 0
      var bytesNew: Long = 0L
      var numOfDomainMetadatas: Long = 0L
      var addFilesHistogram: Option[FileSizeHistogram] = None
      var removeFilesHistogram: Option[FileSizeHistogram] = None
      val assertDeletionVectorWellFormed = getAssertDeletionVectorWellFormedFunc(spark, op)
      var allActions = (extraActions.toIterator ++ actions).map { action =>
        commitSize += 1
        action match {
          case a: AddFile =>
            numAddFiles += 1
            if (a.pathAsUri.isAbsolute) numAbsolutePaths += 1
            assertDeletionVectorWellFormed(a)
            if (a.dataChange) bytesNew += a.size
            addFilesHistogram.foreach(_.insert(a.size))
          case r: RemoveFile =>
            numRemoveFiles += 1
            removeFilesHistogram.foreach(_.insert(r.getFileSize))
          case _: SetTransaction =>
            numSetTransaction += 1
          case m: Metadata =>
            assertMetadata(m)
          case p: Protocol =>
            recordProtocolChanges(
              "delta.protocol.change",
              fromProtocol = snapshot.protocol,
              toProtocol = p,
              isCreatingNewTable)
            DeltaTableV2.withEnrichedUnsupportedTableException(catalogTable) {
              deltaLog.protocolWrite(p)
            }
          case d: DomainMetadata =>
            numOfDomainMetadatas += 1
          case _ =>
        }
        action
      }

      // Validate protocol support, specifically writer features.
      DeltaTableV2.withEnrichedUnsupportedTableException(catalogTable) {
        deltaLog.protocolWrite(snapshot.protocol)
      }

      allActions = RowId.assignFreshRowIds(protocol, snapshot, allActions)
      allActions = DefaultRowCommitVersion
        .assignIfMissing(protocol, allActions, getFirstAttemptVersion)

      if (readVersion < 0) {
        deltaLog.createLogDirectory()
      }
      val fsWriteStartNano = System.nanoTime()
      val jsonActions = allActions.map(_.json)
      deltaLog.store.write(
        deltaFile(deltaLog.logPath, attemptVersion),
        jsonActions,
        overwrite = false,
        deltaLog.newDeltaHadoopConf())

      spark.sessionState.conf.setConf(
        DeltaSQLConf.DELTA_LAST_COMMIT_VERSION_IN_SESSION,
        Some(attemptVersion))
      commitEndNano = System.nanoTime()
      committed = true
      // NOTE: commitLarge cannot run postCommitHooks (such as the CheckpointHook).
      // Instead, manually run any necessary actions in updateAndCheckpoint.
      val postCommitSnapshot =
        updateAndCheckpoint(spark, deltaLog, commitSize, attemptVersion, txnId)
      val postCommitReconstructionTime = System.nanoTime()
      var stats = CommitStats(
        startVersion = readVersion,
        commitVersion = attemptVersion,
        readVersion = postCommitSnapshot.version,
        txnDurationMs = NANOSECONDS.toMillis(commitEndNano - txnStartTimeNs),
        commitDurationMs = NANOSECONDS.toMillis(commitEndNano - commitStartNano),
        fsWriteDurationMs = NANOSECONDS.toMillis(commitEndNano - fsWriteStartNano),
        stateReconstructionDurationMs =
          NANOSECONDS.toMillis(postCommitReconstructionTime - commitEndNano),
        numAdd = numAddFiles,
        numRemove = numRemoveFiles,
        numSetTransaction = numSetTransaction,
        bytesNew = bytesNew,
        numFilesTotal = postCommitSnapshot.numOfFiles,
        sizeInBytesTotal = postCommitSnapshot.sizeInBytes,
        numCdcFiles = 0,
        cdcBytesNew = 0,
        protocol = postCommitSnapshot.protocol,
        commitSizeBytes = jsonActions.map(_.size).sum,
        checkpointSizeBytes = postCommitSnapshot.checkpointSizeInBytes(),
        totalCommitsSizeSinceLastCheckpoint = 0L,
        checkpointAttempt = true,
        info = Option(commitInfo).map(_.copy(readVersion = None, isolationLevel = None)).orNull,
        newMetadata = Some(metadata),
        numAbsolutePathsInAdd = numAbsolutePaths,
        numDistinctPartitionsInAdd = -1, // not tracking distinct partitions as of now
        numPartitionColumnsInTable = postCommitSnapshot.metadata.partitionColumns.size,
        isolationLevel = Serializable.toString,
        numOfDomainMetadatas = numOfDomainMetadatas,
        txnId = Some(txnId))

      recordDeltaEvent(deltaLog, DeltaLogging.DELTA_COMMIT_STATS_OPTYPE, data = stats)
      (attemptVersion, postCommitSnapshot)
    } catch {
      case e: java.nio.file.FileAlreadyExistsException =>
        recordDeltaEvent(
          deltaLog,
          "delta.commitLarge.failure",
          data = Map("exception" -> Utils.exceptionString(e), "operation" -> op.name))
        // Actions of a commit which went in before ours
        val logs = deltaLog.store.readAsIterator(
          deltaFile(deltaLog.logPath, attemptVersion),
          deltaLog.newDeltaHadoopConf())
        try {
          val winningCommitActions = logs.map(Action.fromJson)
          val commitInfo = winningCommitActions.collectFirst { case a: CommitInfo => a }
            .map(ci => ci.copy(version = Some(attemptVersion)))
          throw DeltaErrors.concurrentWriteException(commitInfo)
        } finally {
          logs.close()
        }

      case NonFatal(e) =>
        recordDeltaEvent(
          deltaLog,
          "delta.commitLarge.failure",
          data = Map("exception" -> Utils.exceptionString(e), "operation" -> op.name))
        throw e
    }
  }

  /** Update the table now that the commit has been made, and write a checkpoint. */
  protected def updateAndCheckpoint(
    spark: SparkSession,
    deltaLog: DeltaLog,
    commitSize: Int,
    attemptVersion: Long,
    txnId: String): Snapshot = {
    val currentSnapshot = deltaLog.update()
    if (currentSnapshot.version != attemptVersion) {
      throw DeltaErrors.invalidCommittedVersion(attemptVersion, currentSnapshot.version)
    }

    logInfo(s"Committed delta #$attemptVersion to ${deltaLog.logPath}. Wrote $commitSize actions.")

    deltaLog.checkpoint(currentSnapshot)
    currentSnapshot
  }

  /**
   * Prepare for a commit by doing all necessary pre-commit checks and modifications to the actions.
   * @return The finalized set of actions.
   */
  protected def prepareCommit(
      actions: Seq[Action],
      op: DeltaOperations.Operation): Seq[Action] = {

    assert(!committed, "Transaction already committed.")

    val (metadatasAndProtocols, otherActions) = actions
      .partition(a => a.isInstanceOf[Metadata] || a.isInstanceOf[Protocol])

    // New metadata can come either from `newMetadata` or from the `actions` there.
    val metadataChanges =
      newMetadata.toSeq ++ metadatasAndProtocols.collect { case m: Metadata => m }
    if (metadataChanges.length > 1) {
      recordDeltaEvent(deltaLog, "delta.metadataCheck.multipleMetadataActions", data = Map(
        "metadataChanges" -> metadataChanges
      ))
      assert(
        metadataChanges.length <= 1, "Cannot change the metadata more than once in a transaction.")
    }
    // There be at most one metadata entry at this point.
    metadataChanges.foreach { m =>
      assertMetadata(m)
      setNewProtocolWithFeaturesEnabledByMetadata(m)

      // Also update `newMetadata` so that the behaviour later is consistent irrespective of whether
      // metadata was set via `updateMetadata` or `actions`.
      newMetadata = Some(m)
    }

    // A protocol change can be *explicit*, i.e. specified as a Protocol action as part of the
    // commit actions, or *implicit*. Implicit protocol changes are mostly caused by setting
    // new table properties that enable features that require a protocol upgrade. These implicit
    // changes are usually captured in newProtocol. In case there is more than one protocol action,
    // it is likely that it is due to a mix of explicit and implicit changes.
    val protocolChanges =
      newProtocol.toSeq ++ metadatasAndProtocols.collect { case p: Protocol => p }
    if (protocolChanges.length > 1) {
      recordDeltaEvent(deltaLog, "delta.protocolCheck.multipleProtocolActions", data = Map(
        "protocolChanges" -> protocolChanges
      ))
      assert(protocolChanges.length <= 1, "Cannot change the protocol more than once in a " +
        "transaction. More than one protocol change in a transaction is likely due to an " +
        "explicitly specified Protocol action and an implicit protocol upgrade triggered by " +
        "a table property.")
    }
    // Update newProtocol so that the behaviour later is consistent irrespective of whether
    // the protocol was set via update/verifyMetadata or actions.
    // NOTE: There is at most one protocol change at this point.
    protocolChanges.foreach { p =>
      newProtocol = Some(p)
      recordProtocolChanges("delta.protocol.change", snapshot.protocol, p, isCreatingNewTable)
      DeltaTableV2.withEnrichedUnsupportedTableException(catalogTable) {
        deltaLog.protocolWrite(p)
      }
    }

    // Now, we know that there is at most 1 Metadata change (stored in newMetadata) and at most 1
    // Protocol change (stored in newProtocol)

    val (protocolUpdate1, metadataUpdate1) =
      UniversalFormat.enforceIcebergInvariantsAndDependencies(
        // Note: if this txn has no protocol or metadata updates, then `prev` will equal `newest`.
        prevProtocol = snapshot.protocol,
        prevMetadata = snapshot.metadata,
        newestProtocol = protocol, // Note: this will try to use `newProtocol`
        newestMetadata = metadata, // Note: this will try to use `newMetadata`
        isCreatingNewTable
      )
    newProtocol = protocolUpdate1.orElse(newProtocol)
    newMetadata = metadataUpdate1.orElse(newMetadata)

    val (protocolUpdate2, metadataUpdate2) = IcebergCompatV1.enforceInvariantsAndDependencies(
      snapshot,
      newestProtocol = protocol, // Note: this will try to use `newProtocol`
      newestMetadata = metadata, // Note: this will try to use `newMetadata`
      isCreatingNewTable,
      otherActions
    )
    newProtocol = protocolUpdate2.orElse(newProtocol)
    newMetadata = metadataUpdate2.orElse(newMetadata)

    var finalActions = newMetadata.toSeq ++ newProtocol.toSeq ++ otherActions

    // Block future cases of CDF + Column Mapping changes + file changes
    // This check requires having called
    // DeltaColumnMapping.checkColumnIdAndPhysicalNameAssignments which is done in the
    // `assertMetadata` call above.
    performCdcColumnMappingCheck(finalActions, op)

    if (snapshot.version == -1) {
      deltaLog.ensureLogDirectoryExist()
      // If this is the first commit and no protocol is specified, initialize the protocol version.
      if (!finalActions.exists(_.isInstanceOf[Protocol])) {
        finalActions = protocol +: finalActions
      }
      // If this is the first commit and no metadata is specified, throw an exception
      if (!finalActions.exists(_.isInstanceOf[Metadata])) {
        recordDeltaEvent(
          deltaLog,
          opType = "delta.metadataCheck.noMetadataInInitialCommit",
          data =
            Map("stacktrace" -> Thread.currentThread.getStackTrace.toSeq.take(20).mkString("\n\t"))
        )
        if (spark.sessionState.conf.getConf(DeltaSQLConf.DELTA_COMMIT_VALIDATION_ENABLED)) {
          throw DeltaErrors.metadataAbsentException()
        }
        logWarning("Detected no metadata in initial commit but commit validation was turned off.")
      }
    }

    val partitionColumns = metadata.physicalPartitionSchema.fieldNames.toSet
    finalActions = finalActions.map {
      case newVersion: Protocol =>
        require(newVersion.minReaderVersion > 0, "The reader version needs to be greater than 0")
        require(newVersion.minWriterVersion > 0, "The writer version needs to be greater than 0")
        if (!canAssignAnyNewProtocol) {
          val currentVersion = snapshot.protocol
          if (!currentVersion.canTransitionTo(newVersion, op)) {
            throw new ProtocolDowngradeException(currentVersion, newVersion)
          }
        }
        newVersion

      case a: AddFile if partitionColumns != a.partitionValues.keySet =>
        // If the partitioning in metadata does not match the partitioning in the AddFile
        recordDeltaEvent(deltaLog, "delta.metadataCheck.partitionMismatch", data = Map(
          "tablePartitionColumns" -> metadata.partitionColumns,
          "filePartitionValues" -> a.partitionValues
        ))
        if (spark.sessionState.conf.getConf(DeltaSQLConf.DELTA_COMMIT_VALIDATION_ENABLED)) {
          throw DeltaErrors.addFilePartitioningMismatchException(
            a.partitionValues.keySet.toSeq, partitionColumns.toSeq)
        }
        logWarning(
          s"""
             |Detected mismatch in partition values between AddFile and table metadata but
             |commit validation was turned off.
             |To turn it back on set ${DeltaSQLConf.DELTA_COMMIT_VALIDATION_ENABLED} to "true"
          """.stripMargin)
        a
      case other => other
    }

    DeltaTableV2.withEnrichedUnsupportedTableException(catalogTable) {
      newProtocol.foreach(deltaLog.protocolWrite)
      deltaLog.protocolWrite(snapshot.protocol)
    }

    finalActions = RowId.assignFreshRowIds(protocol, snapshot, finalActions.toIterator).toList
    finalActions = DefaultRowCommitVersion
      .assignIfMissing(protocol, finalActions.toIterator, getFirstAttemptVersion).toList

    // We make sure that this isn't an appendOnly table as we check if we need to delete
    // files.
    val removes = actions.collect { case r: RemoveFile => r }
    if (removes.exists(_.dataChange)) DeltaLog.assertRemovable(snapshot)

    val assertDeletionVectorWellFormed = getAssertDeletionVectorWellFormedFunc(spark, op)
    actions.foreach(assertDeletionVectorWellFormed)

    // Make sure this operation does not include default column values if the corresponding table
    // feature is not enabled.
    if (!protocol.isFeatureSupported(AllowColumnDefaultsTableFeature)) {
      checkNoColumnDefaults(op)
    }

    finalActions
  }

  // Returns the isolation level to use for committing the transaction
  protected def getIsolationLevelToUse(
      preparedActions: Seq[Action], op: DeltaOperations.Operation): IsolationLevel = {
    val isolationLevelToUse = if (canDowngradeToSnapshotIsolation(preparedActions, op)) {
      SnapshotIsolation
    } else {
      getDefaultIsolationLevel()
    }
    isolationLevelToUse
  }

  protected def canDowngradeToSnapshotIsolation(
      preparedActions: Seq[Action], op: DeltaOperations.Operation): Boolean = {

    var dataChanged = false
    var hasIncompatibleActions = false
    preparedActions.foreach {
      case f: FileAction =>
        if (f.dataChange) {
          dataChanged = true
        }
      // Row tracking is able to resolve write conflicts regardless of isolation level.
      case d: DomainMetadata if RowTrackingMetadataDomain.isRowTrackingDomain(d) =>
        // Do nothing
      case _ =>
        hasIncompatibleActions = true
    }
    val noDataChanged = !dataChanged

    if (hasIncompatibleActions) {
      // if incompatible actions are present (e.g. METADATA etc.), then don't downgrade the
      // isolation level to SnapshotIsolation.
      return false
    }

    val defaultIsolationLevel = getDefaultIsolationLevel()
    // Note-1: For no-data-change transactions such as OPTIMIZE/Auto Compaction/ZorderBY, we can
    // change the isolation level to SnapshotIsolation. SnapshotIsolation allows reduced conflict
    // detection by skipping the
    // [[ConflictChecker.checkForAddedFilesThatShouldHaveBeenReadByCurrentTxn]] check i.e.
    // don't worry about concurrent appends.
    // Note-2:
    // We can also use SnapshotIsolation for empty transactions. e.g. consider a commit:
    // t0 - Initial state of table
    // t1 - Q1, Q2 starts
    // t2 - Q1 commits
    // t3 - Q2 is empty and wants to commit.
    // In this scenario, we can always allow Q2 to commit without worrying about new files
    // generated by Q1.
    // The final order which satisfies both Serializability and WriteSerializability is: Q2, Q1
    // Note that Metadata only update transactions shouldn't be considered empty. If Q2 above has
    // a Metadata update (say schema change/identity column high watermark update), then Q2 can't
    // be moved above Q1 in the final SERIALIZABLE order. This is because if Q2 is moved above Q1,
    // then Q1 should see the updates from Q2 - which actually didn't happen.

    val allowFallbackToSnapshotIsolation = defaultIsolationLevel match {
      case Serializable => noDataChanged
      case WriteSerializable => noDataChanged && !op.changesData
      case _ => false // This case should never happen
    }
    allowFallbackToSnapshotIsolation
  }

  /** Log protocol change events. */
  private def recordProtocolChanges(
      opType: String,
      fromProtocol: Protocol,
      toProtocol: Protocol,
      isCreatingNewTable: Boolean): Unit = {
    def extract(p: Protocol): Map[String, Any] = Map(
      "minReaderVersion" -> p.minReaderVersion, // Number
      "minWriterVersion" -> p.minWriterVersion, // Number
      "supportedFeatures" ->
        p.implicitlyAndExplicitlySupportedFeatures.map(_.name).toSeq.sorted // Array[String]
    )

    val payload = if (isCreatingNewTable) {
      Map("toProtocol" -> extract(toProtocol))
    } else {
      Map("fromProtocol" -> extract(fromProtocol), "toProtocol" -> extract(toProtocol))
    }
    recordDeltaEvent(deltaLog, opType, data = payload)
  }

  /**
  * Default [[IsolationLevel]] as set in table metadata.
  */
  private[delta] def getDefaultIsolationLevel(): IsolationLevel = {
    DeltaConfigs.ISOLATION_LEVEL.fromMetaData(metadata)
  }

  /**
   * Sets needsCheckpoint if we should checkpoint the version that has just been committed.
   */
  protected def setNeedsCheckpoint(committedVersion: Long, postCommitSnapshot: Snapshot): Unit = {
    def checkpointInterval = deltaLog.checkpointInterval(postCommitSnapshot.metadata)
    needsCheckpoint = committedVersion != 0 && committedVersion % checkpointInterval == 0
  }

  private[delta] def isCommitLockEnabled: Boolean = {
    spark.sessionState.conf.getConf(DeltaSQLConf.DELTA_COMMIT_LOCK_ENABLED).getOrElse(
      deltaLog.store.isPartialWriteVisible(deltaLog.logPath, deltaLog.newDeltaHadoopConf()))
  }

  private def lockCommitIfEnabled[T](body: => T): T = {
    if (isCommitLockEnabled) {
      deltaLog.lockInterruptibly(body)
    } else {
      body
    }
  }

  /**
   * Commit the txn represented by `currentTransactionInfo` using `attemptVersion` version number.
   * If there are any conflicts that are found, we will retry a fixed number of times.
   *
   * @return the real version that was committed, the postCommitSnapshot, and the txn info
   *         NOTE: The postCommitSnapshot may not be the same as the version committed if racing
   *         commits were written while we updated the snapshot.
   */
  protected def doCommitRetryIteratively(
      attemptVersion: Long,
      currentTransactionInfo: CurrentTransactionInfo,
      isolationLevel: IsolationLevel)
    : (Long, Snapshot, CurrentTransactionInfo) = lockCommitIfEnabled {

    var commitVersion = attemptVersion
    var updatedCurrentTransactionInfo = currentTransactionInfo
    val maxRetryAttempts = spark.conf.get(DeltaSQLConf.DELTA_MAX_RETRY_COMMIT_ATTEMPTS)
    recordDeltaOperation(deltaLog, "delta.commit.allAttempts") {
      for (attemptNumber <- 0 to maxRetryAttempts) {
        try {
          val postCommitSnapshot = if (attemptNumber == 0) {
            doCommit(commitVersion, updatedCurrentTransactionInfo, attemptNumber, isolationLevel)
          } else recordDeltaOperation(deltaLog, "delta.commit.retry") {
            val (newCommitVersion, newCurrentTransactionInfo) = checkForConflicts(
              commitVersion, updatedCurrentTransactionInfo, attemptNumber, isolationLevel)
            commitVersion = newCommitVersion
            updatedCurrentTransactionInfo = newCurrentTransactionInfo
            doCommit(commitVersion, updatedCurrentTransactionInfo, attemptNumber, isolationLevel)
          }
          committed = true
          return (commitVersion, postCommitSnapshot, updatedCurrentTransactionInfo)
        } catch {
          case _: FileAlreadyExistsException => // Do nothing, retry
        }
      }
    }
    // retries all failed
    val totalCommitAttemptTime = clock.getTimeMillis() - commitAttemptStartTime
    throw DeltaErrors.maxCommitRetriesExceededException(
      maxRetryAttempts + 1,
      commitVersion,
      attemptVersion,
      updatedCurrentTransactionInfo.finalActionsToCommit.length,
      totalCommitAttemptTime)
  }

  /**
   * Commit `actions` using `attemptVersion` version number. Throws a FileAlreadyExistsException
   * if any conflicts are detected.
   *
   * @return the post-commit snapshot of the deltaLog
   */
  protected def doCommit(
      attemptVersion: Long,
      currentTransactionInfo: CurrentTransactionInfo,
      attemptNumber: Int,
      isolationLevel: IsolationLevel): Snapshot = {
    val actions = currentTransactionInfo.finalActionsToCommit
    logInfo(
      s"Attempting to commit version $attemptVersion with ${actions.size} actions with " +
        s"$isolationLevel isolation level")

    if (readVersion > -1 && metadata.id != snapshot.metadata.id) {
      val msg = s"Change in the table id detected in txn. Table id for txn on table at " +
        s"${deltaLog.dataPath} was ${snapshot.metadata.id} when the txn was created and " +
        s"is now changed to ${metadata.id}."
      logWarning(msg)
      recordDeltaEvent(deltaLog, "delta.metadataCheck.commit", data = Map(
        "readSnapshotVersion" -> snapshot.version,
        "readSnapshotMetadata" -> snapshot.metadata,
        "txnMetadata" -> metadata,
        "commitAttemptVersion" -> attemptVersion,
        "commitAttemptNumber" -> attemptNumber))
    }

    val fsWriteStartNano = System.nanoTime()
    val jsonActions = actions.map(_.json)

    val newChecksumOpt = writeCommitFile(
      attemptVersion,
      jsonActions.toIterator,
      currentTransactionInfo)

    spark.sessionState.conf.setConf(
      DeltaSQLConf.DELTA_LAST_COMMIT_VERSION_IN_SESSION,
      Some(attemptVersion))

    commitEndNano = System.nanoTime()

    val postCommitSnapshot = deltaLog.updateAfterCommit(
      attemptVersion,
      newChecksumOpt,
      preCommitLogSegment
    )
    val postCommitReconstructionTime = System.nanoTime()

    // Post stats
    // Here, we efficiently calculate various stats (number of each different action, number of
    // bytes per action, etc.) by iterating over all actions, case matching by type, and updating
    // variables. This is more efficient than a functional approach.
    var numAbsolutePaths = 0
    val distinctPartitions = new mutable.HashSet[Map[String, String]]

    var bytesNew: Long = 0L
    var numAdd: Int = 0
    var numOfDomainMetadatas: Long = 0L
    var numRemove: Int = 0
    var numSetTransaction: Int = 0
    var numCdcFiles: Int = 0
    var cdcBytesNew: Long = 0L
    actions.foreach {
      case a: AddFile =>
        numAdd += 1
        if (a.pathAsUri.isAbsolute) numAbsolutePaths += 1
        distinctPartitions += a.partitionValues
        if (a.dataChange) bytesNew += a.size
      case r: RemoveFile =>
        numRemove += 1
      case c: AddCDCFile =>
        numCdcFiles += 1
        cdcBytesNew += c.size
      case _: SetTransaction =>
        numSetTransaction += 1
      case _: DomainMetadata =>
        numOfDomainMetadatas += 1
      case _ =>
    }
    val info = currentTransactionInfo.commitInfo
      .map(_.copy(readVersion = None, isolationLevel = None)).orNull
    setNeedsCheckpoint(attemptVersion, postCommitSnapshot)
    val stats = CommitStats(
      startVersion = snapshot.version,
      commitVersion = attemptVersion,
      readVersion = postCommitSnapshot.version,
      txnDurationMs = NANOSECONDS.toMillis(commitEndNano - txnStartNano),
      commitDurationMs = NANOSECONDS.toMillis(commitEndNano - commitStartNano),
      fsWriteDurationMs = NANOSECONDS.toMillis(commitEndNano - fsWriteStartNano),
      stateReconstructionDurationMs =
        NANOSECONDS.toMillis(postCommitReconstructionTime - commitEndNano),
      numAdd = numAdd,
      numRemove = numRemove,
      numSetTransaction = numSetTransaction,
      bytesNew = bytesNew,
      numFilesTotal = postCommitSnapshot.numOfFiles,
      sizeInBytesTotal = postCommitSnapshot.sizeInBytes,
      numCdcFiles = numCdcFiles,
      cdcBytesNew = cdcBytesNew,
      protocol = postCommitSnapshot.protocol,
      commitSizeBytes = jsonActions.map(_.size).sum,
      checkpointSizeBytes = postCommitSnapshot.checkpointSizeInBytes(),
      totalCommitsSizeSinceLastCheckpoint = postCommitSnapshot.deltaFileSizeInBytes(),
      checkpointAttempt = needsCheckpoint,
      info = info,
      newMetadata = newMetadata,
      numAbsolutePathsInAdd = numAbsolutePaths,
      numDistinctPartitionsInAdd = distinctPartitions.size,
      numPartitionColumnsInTable = postCommitSnapshot.metadata.partitionColumns.size,
      isolationLevel = isolationLevel.toString,
      numOfDomainMetadatas = numOfDomainMetadatas,
      txnId = Some(txnId))
    recordDeltaEvent(deltaLog, DeltaLogging.DELTA_COMMIT_STATS_OPTYPE, data = stats)

    postCommitSnapshot
  }

  /** Writes the json actions provided to the commit file corresponding to attemptVersion */
  protected def writeCommitFile(
      attemptVersion: Long,
      jsonActions: Iterator[String],
      currentTransactionInfo: CurrentTransactionInfo): Option[VersionChecksum] = {
    deltaLog.store.write(
      deltaFile(deltaLog.logPath, attemptVersion),
      jsonActions,
      overwrite = false,
      deltaLog.newDeltaHadoopConf())
    None // No VersionChecksum available yet
  }

  /**
   * Looks at actions that have happened since the txn started and checks for logical
   * conflicts with the read/writes. Resolve conflicts and returns a tuple representing
   * the commit version to attempt next and the commit summary which we need to commit.
   */
  protected def checkForConflicts(
      checkVersion: Long,
      currentTransactionInfo: CurrentTransactionInfo,
      attemptNumber: Int,
      commitIsolationLevel: IsolationLevel)
    : (Long, CurrentTransactionInfo) = recordDeltaOperation(
        deltaLog,
        "delta.commit.retry.conflictCheck",
        tags = Map(TAG_LOG_STORE_CLASS -> deltaLog.store.getClass.getName)) {

    DeltaTableV2.withEnrichedUnsupportedTableException(catalogTable) {

      val nextAttemptVersion = getNextAttemptVersion(checkVersion)

      val logPrefixStr = s"[attempt $attemptNumber]"
      val txnDetailsLogStr = {
        var adds = 0L
        var removes = 0L
        currentTransactionInfo.actions.foreach {
          case _: AddFile => adds += 1
          case _: RemoveFile => removes += 1
          case _ =>
        }
        s"$adds adds, $removes removes, ${readPredicates.size} read predicates, " +
          s"${readFiles.size} read files"
      }

      logInfo(s"$logPrefixStr Checking for conflicts with versions " +
        s"[$checkVersion, $nextAttemptVersion) with current txn having $txnDetailsLogStr")

      var updatedCurrentTransactionInfo = currentTransactionInfo
      (checkVersion until nextAttemptVersion).foreach { otherCommitVersion =>
        updatedCurrentTransactionInfo = checkForConflictsAgainstVersion(
          updatedCurrentTransactionInfo,
          otherCommitVersion,
          commitIsolationLevel)
        logInfo(s"$logPrefixStr No conflicts in version $otherCommitVersion, " +
          s"${clock.getTimeMillis() - commitAttemptStartTime} ms since start")
      }

      logInfo(s"$logPrefixStr No conflicts with versions [$checkVersion, $nextAttemptVersion) " +
        s"with current txn having $txnDetailsLogStr, " +
        s"${clock.getTimeMillis() - commitAttemptStartTime} ms since start")
      (nextAttemptVersion, updatedCurrentTransactionInfo)
    }
  }

  protected def checkForConflictsAgainstVersion(
      currentTransactionInfo: CurrentTransactionInfo,
      otherCommitVersion: Long,
      commitIsolationLevel: IsolationLevel): CurrentTransactionInfo = {

    val conflictChecker = new ConflictChecker(
      spark,
      currentTransactionInfo,
      otherCommitVersion,
      commitIsolationLevel)
    conflictChecker.checkConflicts()
  }

  /** Returns the version that the first attempt will try to commit at. */
  protected def getFirstAttemptVersion: Long = readVersion + 1L

  /** Returns the next attempt version given the last attempted version */
  protected def getNextAttemptVersion(previousAttemptVersion: Long): Long = {
    val latestSnapshot = deltaLog.update()
    preCommitLogSegment = latestSnapshot.logSegment
    latestSnapshot.version + 1
  }

  /** Register a hook that will be executed once a commit is successful. */
  def registerPostCommitHook(hook: PostCommitHook): Unit = {
    if (!postCommitHooks.contains(hook)) {
      postCommitHooks.append(hook)
    }
  }

  def containsPostCommitHook(hook: PostCommitHook): Boolean = postCommitHooks.contains(hook)

  /** Executes the registered post commit hooks. */
  protected def runPostCommitHooks(
      version: Long,
      postCommitSnapshot: Snapshot,
      committedActions: Seq[Action]): Unit = {
    assert(committed, "Can't call post commit hooks before committing")

    // Keep track of the active txn because hooks may create more txns and overwrite the active one.
    val activeCommit = OptimisticTransaction.getActive()
    OptimisticTransaction.clearActive()

    try {
      postCommitHooks.foreach(runPostCommitHook(_, version, postCommitSnapshot, committedActions))
    } finally {
      activeCommit.foreach(OptimisticTransaction.setActive)
    }
  }

  protected def runPostCommitHook(
      hook: PostCommitHook,
      version: Long,
      postCommitSnapshot: Snapshot,
      committedActions: Seq[Action]): Unit = {
    try {
      hook.run(spark, this, version, postCommitSnapshot, committedActions)
    } catch {
      case NonFatal(e) =>
        logWarning(s"Error when executing post-commit hook ${hook.name} " +
          s"for commit $version", e)
        recordDeltaEvent(deltaLog, "delta.commit.hook.failure", data = Map(
          "hook" -> hook.name,
          "version" -> version,
          "exception" -> e.toString
        ))
        hook.handleError(e, version)
    }
  }

  private[delta] def unregisterPostCommitHooksWhere(predicate: PostCommitHook => Boolean): Unit =
    postCommitHooks --= postCommitHooks.filter(predicate)

  protected lazy val logPrefix: String = {
    def truncate(uuid: String): String = uuid.split("-").head
    s"[tableId=${truncate(snapshot.metadata.id)},txnId=${truncate(txnId)}] "
  }

  override def logInfo(msg: => String): Unit = {
    super.logInfo(logPrefix + msg)
  }

  override def logWarning(msg: => String): Unit = {
    super.logWarning(logPrefix + msg)
  }

  override def logWarning(msg: => String, throwable: Throwable): Unit = {
    super.logWarning(logPrefix + msg, throwable)
  }

  override def logError(msg: => String): Unit = {
    super.logError(logPrefix + msg)
  }

  override def logError(msg: => String, throwable: Throwable): Unit = {
    super.logError(logPrefix + msg, throwable)
  }

  /**
   * If the operation assigns or modifies column default values, this method checks that the
   * corresponding table feature is enabled and throws an error if not.
   */
  protected def checkNoColumnDefaults(op: DeltaOperations.Operation): Unit = {
    def usesDefaults(column: StructField): Boolean = {
      column.metadata.contains(ResolveDefaultColumns.CURRENT_DEFAULT_COLUMN_METADATA_KEY) ||
        column.metadata.contains(ResolveDefaultColumns.EXISTS_DEFAULT_COLUMN_METADATA_KEY)
    }

    def throwError(errorClass: String, parameters: Array[String]): Unit = {
      throw new DeltaAnalysisException(
        errorClass = errorClass,
        messageParameters = parameters)
    }

    op match {
      case change: ChangeColumn if usesDefaults(change.newColumn) =>
        throwError("WRONG_COLUMN_DEFAULTS_FOR_DELTA_FEATURE_NOT_ENABLED",
          Array("ALTER TABLE"))
      case create: CreateTable if create.metadata.schema.fields.exists(usesDefaults) =>
        throwError("WRONG_COLUMN_DEFAULTS_FOR_DELTA_FEATURE_NOT_ENABLED",
          Array("CREATE TABLE"))
      case replace: ReplaceColumns if replace.columns.exists(usesDefaults) =>
        throwError("WRONG_COLUMN_DEFAULTS_FOR_DELTA_FEATURE_NOT_ENABLED",
          Array("CREATE TABLE"))
      case replace: ReplaceTable if replace.metadata.schema.fields.exists(usesDefaults) =>
        throwError("WRONG_COLUMN_DEFAULTS_FOR_DELTA_FEATURE_NOT_ENABLED",
          Array("CREATE TABLE"))
      case update: UpdateSchema if update.newSchema.fields.exists(usesDefaults) =>
        throwError("WRONG_COLUMN_DEFAULTS_FOR_DELTA_FEATURE_NOT_ENABLED",
          Array("ALTER TABLE"))
      case _ =>
    }
  }

}
