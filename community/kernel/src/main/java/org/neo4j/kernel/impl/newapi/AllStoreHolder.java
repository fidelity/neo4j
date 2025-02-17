/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.newapi;

import static org.neo4j.function.Predicates.alwaysTrue;
import static org.neo4j.internal.helpers.collection.Iterators.singleOrNull;
import static org.neo4j.internal.kernel.api.IndexQueryConstraints.unconstrained;
import static org.neo4j.storageengine.api.txstate.TxStateVisitor.EMPTY;

import java.util.Collections;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.neo4j.collection.Dependencies;
import org.neo4j.collection.RawIterator;
import org.neo4j.common.EntityType;
import org.neo4j.exceptions.KernelException;
import org.neo4j.internal.helpers.collection.Iterators;
import org.neo4j.internal.kernel.api.IndexMonitor;
import org.neo4j.internal.kernel.api.IndexReadSession;
import org.neo4j.internal.kernel.api.InternalIndexState;
import org.neo4j.internal.kernel.api.NodeValueIndexCursor;
import org.neo4j.internal.kernel.api.PopulationProgress;
import org.neo4j.internal.kernel.api.PropertyIndexQuery;
import org.neo4j.internal.kernel.api.RelationshipDataAccessor;
import org.neo4j.internal.kernel.api.RelationshipIndexCursor;
import org.neo4j.internal.kernel.api.RelationshipScanCursor;
import org.neo4j.internal.kernel.api.RelationshipValueIndexCursor;
import org.neo4j.internal.kernel.api.SchemaReadCore;
import org.neo4j.internal.kernel.api.TokenPredicate;
import org.neo4j.internal.kernel.api.TokenRead;
import org.neo4j.internal.kernel.api.TokenReadSession;
import org.neo4j.internal.kernel.api.exceptions.ProcedureException;
import org.neo4j.internal.kernel.api.exceptions.schema.IndexNotFoundKernelException;
import org.neo4j.internal.kernel.api.procs.ProcedureCallContext;
import org.neo4j.internal.kernel.api.procs.ProcedureHandle;
import org.neo4j.internal.kernel.api.procs.ProcedureSignature;
import org.neo4j.internal.kernel.api.procs.QualifiedName;
import org.neo4j.internal.kernel.api.procs.UserAggregationReducer;
import org.neo4j.internal.kernel.api.procs.UserFunctionHandle;
import org.neo4j.internal.kernel.api.procs.UserFunctionSignature;
import org.neo4j.internal.kernel.api.security.AccessMode;
import org.neo4j.internal.kernel.api.security.SecurityAuthorizationHandler;
import org.neo4j.internal.schema.ConstraintDescriptor;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.internal.schema.IndexType;
import org.neo4j.internal.schema.SchemaDescriptor;
import org.neo4j.internal.schema.SchemaDescriptors;
import org.neo4j.internal.schema.SchemaState;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.kernel.api.AssertOpen;
import org.neo4j.kernel.api.index.IndexSample;
import org.neo4j.kernel.api.index.TokenIndexReader;
import org.neo4j.kernel.api.index.ValueIndexReader;
import org.neo4j.kernel.api.procedure.GlobalProcedures;
import org.neo4j.kernel.api.txstate.TransactionState;
import org.neo4j.kernel.impl.api.ClockContext;
import org.neo4j.kernel.impl.api.IndexReaderCache;
import org.neo4j.kernel.impl.api.KernelTransactionImplementation;
import org.neo4j.kernel.impl.api.OverridableSecurityContext;
import org.neo4j.kernel.impl.api.index.IndexingService;
import org.neo4j.kernel.impl.api.index.stats.IndexStatisticsStore;
import org.neo4j.kernel.impl.api.parallel.ParallelAccessCheck;
import org.neo4j.kernel.impl.api.parallel.ThreadExecutionContext;
import org.neo4j.kernel.impl.locking.Locks;
import org.neo4j.lock.LockTracer;
import org.neo4j.lock.ResourceTypes;
import org.neo4j.memory.MemoryTracker;
import org.neo4j.storageengine.api.CountsDelta;
import org.neo4j.storageengine.api.StorageLocks;
import org.neo4j.storageengine.api.StorageReader;
import org.neo4j.storageengine.api.StorageSchemaReader;
import org.neo4j.storageengine.api.cursor.StoreCursors;
import org.neo4j.storageengine.api.txstate.DiffSets;
import org.neo4j.storageengine.api.txstate.TransactionCountingStateVisitor;
import org.neo4j.values.AnyValue;
import org.neo4j.values.storable.Value;

/**
 * Implementation of read operation of the Kernel API.
 * <p>
 * The relation between a transaction and All Store Holder can be one to many
 * and the instances of All Store Holder related to the same transaction can be used concurrently by multiple threads.
 * The thread safety aspect of the operation performed by All Store Holder must reflect it!
 * This means that All Store Holder must not use objects shared by multiple All Store Holders that are
 * not safe for multithreaded use!!!
 * This class has two implementations.
 * {@link ForTransactionScope} has one-to-one relation with a transaction and therefore it can safely
 * use {@link KernelTransactionImplementation}, its resources and generally any transaction-scoped resource
 * {@link ForThreadExecutionContextScope} has one-to-many relation with a transaction and therefore it CANNOT safely
 * use {@link KernelTransactionImplementation}, its resources and generally any transaction-scoped
 * resource which is not designed for concurrent use.
 * It should use resources in {@link org.neo4j.kernel.impl.api.parallel.ThreadExecutionContext} scope instead.
 */
public abstract class AllStoreHolder extends Read {
    private final SchemaState schemaState;
    private final IndexingService indexingService;
    private final IndexStatisticsStore indexStatisticsStore;
    private final GlobalProcedures globalProcedures;
    private final MemoryTracker memoryTracker;
    private final IndexReaderCache<ValueIndexReader> valueIndexReaderCache;
    private final IndexReaderCache<TokenIndexReader> tokenIndexReaderCache;

    private AllStoreHolder(
            StorageReader storageReader,
            TokenRead tokenRead,
            SchemaState schemaState,
            IndexingService indexingService,
            IndexStatisticsStore indexStatisticsStore,
            GlobalProcedures globalProcedures,
            MemoryTracker memoryTracker,
            DefaultPooledCursors cursors,
            StoreCursors storageCursors,
            StorageLocks storageLocks,
            LockTracer lockTracer) {
        super(storageReader, tokenRead, cursors, storageCursors, storageLocks, lockTracer);
        this.schemaState = schemaState;
        this.valueIndexReaderCache = new IndexReaderCache<>(
                index -> indexingService.getIndexProxy(index).newValueReader());
        this.tokenIndexReaderCache = new IndexReaderCache<>(
                index -> indexingService.getIndexProxy(index).newTokenReader());
        this.indexingService = indexingService;
        this.indexStatisticsStore = indexStatisticsStore;
        this.memoryTracker = memoryTracker;
        this.globalProcedures = globalProcedures;
    }

    @Override
    public boolean nodeExists(long reference) {
        performCheckBeforeOperation();

        if (hasTxStateWithChanges()) {
            TransactionState txState = txState();
            if (txState.nodeIsDeletedInThisTx(reference)) {
                return false;
            } else if (txState.nodeIsAddedInThisTx(reference)) {
                return true;
            }
        }

        boolean existsInNodeStore = storageReader.nodeExists(reference, storageCursors);

        if (getAccessMode().allowsTraverseAllLabels()) {
            return existsInNodeStore;
        } else if (!existsInNodeStore) {
            return false;
        } else {
            try (DefaultNodeCursor node = cursors.allocateNodeCursor(cursorContext())) {
                singleNode(reference, node);
                return node.next();
            }
        }
    }

    @Override
    public boolean nodeDeletedInTransaction(long node) {
        performCheckBeforeOperation();
        return hasTxStateWithChanges() && txState().nodeIsDeletedInThisTx(node);
    }

    @Override
    public boolean relationshipDeletedInTransaction(long relationship) {
        performCheckBeforeOperation();
        return hasTxStateWithChanges() && txState().relationshipIsDeletedInThisTx(relationship);
    }

    @Override
    public Value nodePropertyChangeInTransactionOrNull(long node, int propertyKeyId) {
        performCheckBeforeOperation();
        return hasTxStateWithChanges() ? txState().getNodeState(node).propertyValue(propertyKeyId) : null;
    }

    @Override
    public Value relationshipPropertyChangeInTransactionOrNull(long relationship, int propertyKeyId) {
        performCheckBeforeOperation();
        return hasTxStateWithChanges()
                ? txState().getRelationshipState(relationship).propertyValue(propertyKeyId)
                : null;
    }

    @Override
    public long countsForNode(int labelId) {
        return countsForNodeWithoutTxState(labelId) + countsForNodeInTxState(labelId);
    }

    @Override
    public long countsForNodeWithoutTxState(int labelId) {
        if (getAccessMode().allowsTraverseAllNodesWithLabel(labelId)) {
            // All nodes with the specified label can be traversed, so the count store can be used.
            return storageReader.countsForNode(labelId, cursorContext());
        } else if (getAccessMode().disallowsTraverseLabel(labelId)) {
            // No nodes with the specified label can be traversed, so the count will be 0.
            return 0;
        } else {
            // We have a restriction on what part of the graph can be traversed, that can affect nodes with the
            // specified label.
            // This disables the count store entirely.
            // We need to calculate the counts through expensive operations.
            // We cannot use a NodeLabelScan without an expensive post-filtering, since it is not guaranteed that all
            // nodes with the label can be traversed.
            long count = 0;
            // DefaultNodeCursor already contains traversal checks within next()
            try (DefaultNodeCursor nodes = cursors.allocateNodeCursor(cursorContext())) {
                this.allNodesScan(nodes);
                while (nodes.next()) {
                    if (labelId == TokenRead.ANY_LABEL || nodes.hasLabel(labelId)) {
                        count++;
                    }
                }
            }
            return count - countsForNodeInTxState(labelId);
        }
    }

    private long countsForNodeInTxState(int labelId) {
        long count = 0;
        if (hasTxStateWithChanges()) {
            CountsDelta counts = new CountsDelta();
            try {
                TransactionState txState = txState();
                try (var countingVisitor = new TransactionCountingStateVisitor(
                        EMPTY, storageReader, txState, counts, cursorContext(), storageCursors)) {
                    txState.accept(countingVisitor);
                }
                if (counts.hasChanges()) {
                    count += counts.nodeCount(labelId, cursorContext());
                }
            } catch (KernelException e) {
                throw new IllegalArgumentException("Unexpected error: " + e.getMessage());
            }
        }
        return count;
    }

    @Override
    public long countsForRelationship(int startLabelId, int typeId, int endLabelId) {
        return countsForRelationshipWithoutTxState(startLabelId, typeId, endLabelId)
                + countsForRelationshipInTxState(startLabelId, typeId, endLabelId);
    }

    @Override
    public long countsForRelationshipWithoutTxState(int startLabelId, int typeId, int endLabelId) {
        if (getAccessMode().allowsTraverseRelType(typeId)
                && getAccessMode().allowsTraverseNode(startLabelId)
                && getAccessMode().allowsTraverseNode(endLabelId)) {
            return storageReader.countsForRelationship(startLabelId, typeId, endLabelId, cursorContext());
        }
        if (getAccessMode().disallowsTraverseRelType(typeId)
                || getAccessMode().disallowsTraverseLabel(startLabelId)
                || getAccessMode().disallowsTraverseLabel(endLabelId)) {
            // Not allowed to traverse any relationship with the specified relationship type, start node label and end
            // node label,
            // so the count will be 0.
            return 0;
        }

        // token index scan can only scan for single relationship type
        if (typeId != TokenRead.ANY_RELATIONSHIP_TYPE) {
            try {
                var index = findUsableTokenIndex(EntityType.RELATIONSHIP);
                if (index != IndexDescriptor.NO_INDEX) {
                    long count = 0;
                    try (var relationshipsWithType = cursors.allocateRelationshipTypeIndexCursor(cursorContext());
                            DefaultNodeCursor sourceNode = cursors.allocateNodeCursor(cursorContext());
                            DefaultNodeCursor targetNode = cursors.allocateNodeCursor(cursorContext())) {
                        var session = tokenReadSession(index);
                        this.relationshipTypeScan(
                                session,
                                relationshipsWithType,
                                unconstrained(),
                                new TokenPredicate(typeId),
                                cursorContext());
                        count += countRelationshipsWithEndLabels(
                                relationshipsWithType, sourceNode, targetNode, startLabelId, endLabelId);
                    }
                    return count - countsForRelationshipInTxState(startLabelId, typeId, endLabelId);
                }
            } catch (KernelException ignored) {
                // ignore, fallback to allRelationshipsScan
            }
        }

        long count;
        try (DefaultRelationshipScanCursor rels = cursors.allocateRelationshipScanCursor(cursorContext());
                DefaultNodeCursor sourceNode = cursors.allocateFullAccessNodeCursor(cursorContext());
                DefaultNodeCursor targetNode = cursors.allocateFullAccessNodeCursor(cursorContext())) {
            this.allRelationshipsScan(rels);
            Predicate<RelationshipScanCursor> predicate =
                    typeId == TokenRead.ANY_RELATIONSHIP_TYPE ? alwaysTrue() : CursorPredicates.hasType(typeId);
            var filteredCursor = new FilteringRelationshipScanCursorWrapper(rels, predicate);
            count = countRelationshipsWithEndLabels(filteredCursor, sourceNode, targetNode, startLabelId, endLabelId);
        }
        return count - countsForRelationshipInTxState(startLabelId, typeId, endLabelId);
    }

    private static long countRelationshipsWithEndLabels(
            RelationshipIndexCursor relationship,
            DefaultNodeCursor sourceNode,
            DefaultNodeCursor targetNode,
            int startLabelId,
            int endLabelId) {
        long internalCount = 0;
        while (relationship.next()) {
            if (relationship.readFromStore()
                    && matchesLabels(relationship, sourceNode, targetNode, startLabelId, endLabelId)) {
                internalCount++;
            }
        }
        return internalCount;
    }

    private static long countRelationshipsWithEndLabels(
            RelationshipScanCursor relationship,
            DefaultNodeCursor sourceNode,
            DefaultNodeCursor targetNode,
            int startLabelId,
            int endLabelId) {
        long internalCount = 0;
        while (relationship.next()) {
            if (matchesLabels(relationship, sourceNode, targetNode, startLabelId, endLabelId)) {
                internalCount++;
            }
        }
        return internalCount;
    }

    private static boolean matchesLabels(
            RelationshipDataAccessor relationship,
            DefaultNodeCursor sourceNode,
            DefaultNodeCursor targetNode,
            int startLabelId,
            int endLabelId) {
        relationship.source(sourceNode);
        relationship.target(targetNode);
        return sourceNode.next()
                && (startLabelId == TokenRead.ANY_LABEL || sourceNode.hasLabel(startLabelId))
                && targetNode.next()
                && (endLabelId == TokenRead.ANY_LABEL || targetNode.hasLabel(endLabelId));
    }

    private long countsForRelationshipInTxState(int startLabelId, int typeId, int endLabelId) {
        long count = 0;
        if (hasTxStateWithChanges()) {
            CountsDelta counts = new CountsDelta();
            try {
                TransactionState txState = txState();
                try (var countingVisitor = new TransactionCountingStateVisitor(
                        EMPTY, storageReader, txState, counts, cursorContext(), storageCursors)) {
                    txState.accept(countingVisitor);
                }
                if (counts.hasChanges()) {
                    count += counts.relationshipCount(startLabelId, typeId, endLabelId, cursorContext());
                }
            } catch (KernelException e) {
                throw new IllegalArgumentException("Unexpected error: " + e.getMessage());
            }
        }
        return count;
    }

    IndexDescriptor findUsableTokenIndex(EntityType entityType) throws IndexNotFoundKernelException {
        var descriptor = SchemaDescriptors.forAnyEntityTokens(entityType);
        var index = index(descriptor, IndexType.LOOKUP);
        if (index != IndexDescriptor.NO_INDEX && indexGetState(index) == InternalIndexState.ONLINE) {
            return index;
        }
        return IndexDescriptor.NO_INDEX;
    }

    @Override
    public boolean relationshipExists(long reference) {
        performCheckBeforeOperation();

        if (hasTxStateWithChanges()) {
            TransactionState txState = txState();
            if (txState.relationshipIsDeletedInThisTx(reference)) {
                return false;
            } else if (txState.relationshipIsAddedInThisTx(reference)) {
                return true;
            }
        }
        boolean existsInRelStore = storageReader.relationshipExists(reference, storageCursors);
        if (getAccessMode().allowsTraverseAllRelTypes()) {
            return existsInRelStore;
        } else if (!existsInRelStore) {
            return false;
        } else {
            try (DefaultRelationshipScanCursor rels = cursors.allocateRelationshipScanCursor(cursorContext())) {
                singleRelationship(reference, rels);
                return rels.next();
            }
        }
    }

    @Override
    public ValueIndexReader newValueIndexReader(IndexDescriptor index) throws IndexNotFoundKernelException {
        assertValidIndex(index);
        return indexingService.getIndexProxy(index).newValueReader();
    }

    public TokenIndexReader newTokenIndexReader(IndexDescriptor index) throws IndexNotFoundKernelException {
        assertValidIndex(index);
        return indexingService.getIndexProxy(index).newTokenReader();
    }

    @Override
    public IndexReadSession indexReadSession(IndexDescriptor index) throws IndexNotFoundKernelException {
        assertValidIndex(index);
        return new DefaultIndexReadSession(valueIndexReaderCache.getOrCreate(index), index);
    }

    @Override
    public TokenReadSession tokenReadSession(IndexDescriptor index) throws IndexNotFoundKernelException {
        assertValidIndex(index);
        return new DefaultTokenReadSession(tokenIndexReaderCache.getOrCreate(index), index);
    }

    @Override
    public Iterator<IndexDescriptor> indexForSchemaNonTransactional(SchemaDescriptor schema) {
        return storageReader.indexGetForSchema(schema);
    }

    @Override
    public IndexDescriptor indexForSchemaAndIndexTypeNonTransactional(SchemaDescriptor schema, IndexType indexType) {
        var index = storageReader.indexGetForSchemaAndType(schema, indexType);
        return index == null ? IndexDescriptor.NO_INDEX : index;
    }

    @Override
    public Iterator<IndexDescriptor> indexForSchemaNonLocking(SchemaDescriptor schema) {
        return indexGetForSchema(storageReader, schema);
    }

    @Override
    public Iterator<IndexDescriptor> getLabelIndexesNonLocking(int labelId) {
        return indexesGetForLabel(storageReader, labelId);
    }

    @Override
    public Iterator<IndexDescriptor> getRelTypeIndexesNonLocking(int relTypeId) {
        return indexesGetForRelationshipType(storageReader, relTypeId);
    }

    @Override
    public Iterator<IndexDescriptor> indexesGetAllNonLocking() {
        return indexesGetAll(storageReader);
    }

    /**
     * Lock the given index if it is valid and exists.
     * If the given index descriptor does not reference an index that exists, then {@link IndexDescriptor#NO_INDEX} is returned.
     *
     * @param index committed, transaction-added or even null.
     * @return The validated index descriptor, which is not necessarily the same as the one given as an argument.
     */
    private IndexDescriptor lockIndex(IndexDescriptor index) {
        if (index == null) {
            return IndexDescriptor.NO_INDEX;
        }
        index = acquireSharedSchemaLock(index);
        // Since the schema cache gives us snapshots views of the schema, the indexes could be dropped in-between us
        // getting the snapshot, and taking the shared schema locks.
        // Thus, after we take the lock, we need to filter out indexes that no longer exists.
        if (!indexExists(index)) {
            releaseSharedSchemaLock(index);
            index = IndexDescriptor.NO_INDEX;
        }
        return index;
    }

    /**
     * Maps all index descriptors according to {@link #lockIndex(IndexDescriptor)}.
     */
    private Iterator<IndexDescriptor> lockIndexes(Iterator<IndexDescriptor> indexes) {
        Predicate<IndexDescriptor> exists = index -> index != IndexDescriptor.NO_INDEX;
        return Iterators.filter(exists, Iterators.map(this::lockIndex, indexes));
    }

    private boolean indexExists(IndexDescriptor index) {
        if (hasTxStateWithChanges()) {
            DiffSets<IndexDescriptor> changes = txState().indexChanges();
            return changes.isAdded(index) || (storageReader.indexExists(index) && !changes.isRemoved(index));
        }
        return storageReader.indexExists(index);
    }

    public void assertIndexExists(IndexDescriptor index) throws IndexNotFoundKernelException {
        if (!indexExists(index)) {
            throw new IndexNotFoundKernelException("Index does not exist: ", index);
        }
    }

    private ConstraintDescriptor lockConstraint(ConstraintDescriptor constraint) {
        if (constraint == null) {
            return null;
        }
        constraint = acquireSharedSchemaLock(constraint);
        if (!constraintExists(constraint)) {
            releaseSharedSchemaLock(constraint);
            constraint = null;
        }
        return constraint;
    }

    private Iterator<ConstraintDescriptor> lockConstraints(Iterator<ConstraintDescriptor> constraints) {
        return Iterators.filter(Objects::nonNull, Iterators.map(this::lockConstraint, constraints));
    }

    @Override
    public boolean constraintExists(ConstraintDescriptor constraint) {
        acquireSharedSchemaLock(constraint);
        performCheckBeforeOperation();

        if (hasTxStateWithChanges()) {
            DiffSets<ConstraintDescriptor> changes = txState().constraintsChanges();
            return changes.isAdded(constraint)
                    || (storageReader.constraintExists(constraint) && !changes.isRemoved(constraint));
        }
        return storageReader.constraintExists(constraint);
    }

    @Override
    public Iterator<IndexDescriptor> index(SchemaDescriptor schema) {
        performCheckBeforeOperation();
        return lockIndexes(indexGetForSchema(storageReader, schema));
    }

    Iterator<IndexDescriptor> indexGetForSchema(StorageSchemaReader reader, SchemaDescriptor schema) {
        Iterator<IndexDescriptor> indexes = reader.indexGetForSchema(schema);
        if (hasTxStateWithChanges()) {
            DiffSets<IndexDescriptor> diffSets = txState().indexDiffSetsBySchema(schema);
            indexes = diffSets.apply(indexes);
        }

        return indexes;
    }

    @Override
    public IndexDescriptor index(SchemaDescriptor schema, IndexType type) {
        performCheckBeforeOperation();
        return lockIndex(indexGetForSchemaAndType(storageReader, schema, type));
    }

    IndexDescriptor indexGetForSchemaAndType(StorageSchemaReader reader, SchemaDescriptor schema, IndexType type) {
        var index = reader.indexGetForSchemaAndType(schema, type);
        if (hasTxStateWithChanges()) {
            var indexChanges = txState().indexChanges();
            if (index == null) {
                // check if such index was added in this tx
                var added = indexChanges
                        .filterAdded(
                                id -> id.getIndexType() == type && id.schema().equals(schema))
                        .getAdded();
                index = singleOrNull(added.iterator());
            }

            if (indexChanges.isRemoved(index)) {
                // this index was removed in this tx
                return null;
            }
        }
        return index;
    }

    @Override
    public Iterator<IndexDescriptor> indexesGetForLabel(int labelId) {
        acquireSharedLock(ResourceTypes.LABEL, labelId);
        performCheckBeforeOperation();
        return lockIndexes(indexesGetForLabel(storageReader, labelId));
    }

    Iterator<IndexDescriptor> indexesGetForLabel(StorageSchemaReader reader, int labelId) {
        if (getAccessMode().allowsTraverseNode(labelId)) {
            Iterator<IndexDescriptor> iterator = reader.indexesGetForLabel(labelId);

            if (hasTxStateWithChanges()) {
                iterator = txState().indexDiffSetsByLabel(labelId).apply(iterator);
            }

            return iterator;
        } else {
            return Collections.emptyIterator();
        }
    }

    @Override
    public Iterator<IndexDescriptor> indexesGetForRelationshipType(int relationshipType) {
        acquireSharedLock(ResourceTypes.RELATIONSHIP_TYPE, relationshipType);
        performCheckBeforeOperation();
        return lockIndexes(indexesGetForRelationshipType(storageReader, relationshipType));
    }

    Iterator<IndexDescriptor> indexesGetForRelationshipType(StorageSchemaReader reader, int relationshipType) {
        Iterator<IndexDescriptor> iterator = reader.indexesGetForRelationshipType(relationshipType);
        if (hasTxStateWithChanges()) {
            iterator =
                    txState().indexDiffSetsByRelationshipType(relationshipType).apply(iterator);
        }
        return iterator;
    }

    @Override
    public IndexDescriptor indexGetForName(String name) {
        return indexGetForName(storageReader, name);
    }

    IndexDescriptor indexGetForName(StorageSchemaReader reader, String name) {
        performCheckBeforeOperation();

        IndexDescriptor index = reader.indexGetForName(name);
        if (hasTxStateWithChanges()) {
            Predicate<IndexDescriptor> namePredicate =
                    indexDescriptor -> indexDescriptor.getName().equals(name);
            Iterator<IndexDescriptor> indexes =
                    txState().indexChanges().filterAdded(namePredicate).apply(Iterators.iterator(index));
            index = singleOrNull(indexes);
        }
        return lockIndex(index);
    }

    @Override
    public ConstraintDescriptor constraintGetForName(String name) {
        return constraintGetForName(storageReader, name);
    }

    ConstraintDescriptor constraintGetForName(StorageSchemaReader reader, String name) {
        performCheckBeforeOperation();

        ConstraintDescriptor constraint = reader.constraintGetForName(name);
        if (hasTxStateWithChanges()) {
            Predicate<ConstraintDescriptor> namePredicate =
                    constraintDescriptor -> constraintDescriptor.getName().equals(name);
            Iterator<ConstraintDescriptor> constraints =
                    txState().constraintsChanges().filterAdded(namePredicate).apply(Iterators.iterator(constraint));
            constraint = singleOrNull(constraints);
        }
        return lockConstraint(constraint);
    }

    @Override
    public Iterator<IndexDescriptor> indexesGetAll() {
        performCheckBeforeOperation();
        Iterator<IndexDescriptor> iterator = indexesGetAll(storageReader);
        return lockIndexes(iterator);
    }

    Iterator<IndexDescriptor> indexesGetAll(StorageSchemaReader reader) {
        Iterator<IndexDescriptor> iterator = reader.indexesGetAll();
        if (hasTxStateWithChanges()) {
            iterator = txState().indexChanges().apply(iterator);
        }
        return iterator;
    }

    @Override
    public InternalIndexState indexGetState(IndexDescriptor index) throws IndexNotFoundKernelException {
        assertValidIndex(index);
        acquireSharedSchemaLock(index);
        performCheckBeforeOperation();

        return indexGetStateLocked(index);
    }

    @Override
    public InternalIndexState indexGetStateNonLocking(IndexDescriptor index) throws IndexNotFoundKernelException {
        assertValidIndex(index);
        performCheckBeforeOperation();
        return indexGetStateLocked(
                index); // TODO: Can we call this method without locking(since we assert valid index)?
    }

    InternalIndexState indexGetStateLocked(IndexDescriptor index) throws IndexNotFoundKernelException {
        SchemaDescriptor schema = index.schema();
        // If index is in our state, then return populating
        if (hasTxStateWithChanges()) {
            if (checkIndexState(index, txState().indexDiffSetsBySchema(schema))) {
                return InternalIndexState.POPULATING;
            }
        }

        return indexingService.getIndexProxy(index).getState();
    }

    @Override
    public PopulationProgress indexGetPopulationProgress(IndexDescriptor index) throws IndexNotFoundKernelException {
        assertValidIndex(index);
        acquireSharedSchemaLock(index);
        performCheckBeforeOperation();
        return indexGetPopulationProgressLocked(index);
    }

    PopulationProgress indexGetPopulationProgressLocked(IndexDescriptor index) throws IndexNotFoundKernelException {
        if (hasTxStateWithChanges()) {
            if (checkIndexState(index, txState().indexDiffSetsBySchema(index.schema()))) {
                return PopulationProgress.NONE;
            }
        }

        return indexingService.getIndexProxy(index).getIndexPopulationProgress();
    }

    @Override
    public Long indexGetOwningUniquenessConstraintId(IndexDescriptor index) {
        acquireSharedSchemaLock(index);
        performCheckBeforeOperation();
        return storageReader.indexGetOwningUniquenessConstraintId(storageReader.indexGetForName(index.getName()));
    }

    @Override
    public String indexGetFailure(IndexDescriptor index) throws IndexNotFoundKernelException {
        assertValidIndex(index);
        return indexingService.getIndexProxy(index).getPopulationFailure().asString();
    }

    @Override
    public double indexUniqueValuesSelectivity(IndexDescriptor index) throws IndexNotFoundKernelException {
        performCheckBeforeOperation();
        assertValidIndex(index);
        acquireSharedSchemaLock(index);
        assertIndexExists(index); // Throws if the index has been dropped.
        final IndexSample indexSample = indexStatisticsStore.indexSample(index.getId());
        long unique = indexSample.uniqueValues();
        long size = indexSample.sampleSize();
        return size == 0 ? 1.0d : ((double) unique) / ((double) size);
    }

    @Override
    public long indexSize(IndexDescriptor index) throws IndexNotFoundKernelException {
        performCheckBeforeOperation();
        assertValidIndex(index);
        acquireSharedSchemaLock(index);
        return indexStatisticsStore.indexSample(index.getId()).indexSize();
    }

    @Override
    public long nodesGetCount() {
        return countsForNode(TokenRead.ANY_LABEL);
    }

    @Override
    public long relationshipsGetCount() {
        return countsForRelationship(TokenRead.ANY_LABEL, TokenRead.ANY_RELATIONSHIP_TYPE, TokenRead.ANY_LABEL);
    }

    @Override
    public IndexSample indexSample(IndexDescriptor index) throws IndexNotFoundKernelException {
        performCheckBeforeOperation();
        assertValidIndex(index);
        return indexStatisticsStore.indexSample(index.getId());
    }

    private static boolean checkIndexState(IndexDescriptor index, DiffSets<IndexDescriptor> diffSet)
            throws IndexNotFoundKernelException {
        if (diffSet.isAdded(index)) {
            return true;
        }
        if (diffSet.isRemoved(index)) {
            throw new IndexNotFoundKernelException("Index has been dropped in this transaction: ", index);
        }
        return false;
    }

    @Override
    public Iterator<ConstraintDescriptor> constraintsGetForSchema(SchemaDescriptor schema) {
        acquireSharedSchemaLock(() -> schema);
        return getConstraintsForSchema(schema);
    }

    @Override
    public Iterator<ConstraintDescriptor> constraintsGetForSchemaNonLocking(SchemaDescriptor schema) {
        return getConstraintsForSchema(schema);
    }

    private Iterator<ConstraintDescriptor> getConstraintsForSchema(SchemaDescriptor schema) {
        performCheckBeforeOperation();
        Iterator<ConstraintDescriptor> constraints = storageReader.constraintsGetForSchema(schema);
        if (hasTxStateWithChanges()) {
            return txState().constraintsChangesForSchema(schema).apply(constraints);
        }
        return constraints;
    }

    @Override
    public Iterator<ConstraintDescriptor> constraintsGetForLabel(int labelId) {
        performCheckBeforeOperation();
        acquireSharedLock(ResourceTypes.LABEL, labelId);
        return constraintsGetForLabel(storageReader, labelId);
    }

    @Override
    public Iterator<ConstraintDescriptor> constraintsGetForLabelNonLocking(int labelId) {
        performCheckBeforeOperation();
        return constraintsGetForLabel(storageReader, labelId);
    }

    Iterator<ConstraintDescriptor> constraintsGetForLabel(StorageSchemaReader reader, int labelId) {
        Iterator<ConstraintDescriptor> constraints = reader.constraintsGetForLabel(labelId);
        if (hasTxStateWithChanges()) {
            return txState().constraintsChangesForLabel(labelId).apply(constraints);
        }
        return constraints;
    }

    @Override
    public Iterator<ConstraintDescriptor> constraintsGetAll() {
        performCheckBeforeOperation();
        Iterator<ConstraintDescriptor> constraints = constraintsGetAll(storageReader);
        return lockConstraints(constraints);
    }

    @Override
    public Iterator<ConstraintDescriptor> constraintsGetAllNonLocking() {
        performCheckBeforeOperation();
        return constraintsGetAll(storageReader);
    }

    Iterator<ConstraintDescriptor> constraintsGetAll(StorageSchemaReader reader) {
        Iterator<ConstraintDescriptor> constraints = reader.constraintsGetAll();
        if (hasTxStateWithChanges()) {
            constraints = txState().constraintsChanges().apply(constraints);
        }
        return constraints;
    }

    @Override
    public Iterator<ConstraintDescriptor> constraintsGetForRelationshipType(int typeId) {
        performCheckBeforeOperation();
        acquireSharedLock(ResourceTypes.RELATIONSHIP_TYPE, typeId);
        return constraintsGetForRelationshipType(storageReader, typeId);
    }

    @Override
    public Iterator<ConstraintDescriptor> constraintsGetForRelationshipTypeNonLocking(int typeId) {
        performCheckBeforeOperation();
        return constraintsGetForRelationshipType(storageReader, typeId);
    }

    Iterator<ConstraintDescriptor> constraintsGetForRelationshipType(StorageSchemaReader reader, int typeId) {
        Iterator<ConstraintDescriptor> constraints = reader.constraintsGetForRelationshipType(typeId);
        if (hasTxStateWithChanges()) {
            return txState().constraintsChangesForRelationshipType(typeId).apply(constraints);
        }
        return constraints;
    }

    @Override
    public SchemaReadCore snapshot() {
        performCheckBeforeOperation();
        StorageSchemaReader snapshot = storageReader.schemaSnapshot();
        return new SchemaReadCoreSnapshot(snapshot, this);
    }

    @Override
    public <K, V> V schemaStateGetOrCreate(K key, Function<K, V> creator) {
        return schemaState.getOrCreate(key, creator);
    }

    @Override
    public void schemaStateFlush() {
        schemaState.clear();
    }

    @Override
    public boolean transactionStateHasChanges() {
        return hasTxStateWithChanges();
    }

    static void assertValidIndex(IndexDescriptor index) throws IndexNotFoundKernelException {
        if (index == IndexDescriptor.NO_INDEX) {
            throw new IndexNotFoundKernelException("No index was found");
        }
    }

    public void release() {
        valueIndexReaderCache.close();
        tokenIndexReaderCache.close();
    }

    @Override
    public MemoryTracker memoryTracker() {
        return memoryTracker;
    }

    @Override
    public IndexMonitor monitor() {
        return indexingService.getMonitor();
    }

    @Override
    public RawIterator<AnyValue[], ProcedureException> procedureCallRead(
            int id, AnyValue[] arguments, ProcedureCallContext context) throws ProcedureException {
        return procedureCaller().callProcedure(id, arguments, AccessMode.Static.READ, context);
    }

    @Override
    public RawIterator<AnyValue[], ProcedureException> procedureCallWrite(
            int id, AnyValue[] arguments, ProcedureCallContext context) throws ProcedureException {
        return procedureCaller().callProcedure(id, arguments, AccessMode.Static.TOKEN_WRITE, context);
    }

    @Override
    public RawIterator<AnyValue[], ProcedureException> procedureCallSchema(
            int id, AnyValue[] arguments, ProcedureCallContext context) throws ProcedureException {
        return procedureCaller().callProcedure(id, arguments, AccessMode.Static.SCHEMA, context);
    }

    @Override
    public RawIterator<AnyValue[], ProcedureException> procedureCallDbms(
            int id, AnyValue[] arguments, ProcedureCallContext context) throws ProcedureException {
        return procedureCaller().callProcedure(id, arguments, AccessMode.Static.ACCESS, context);
    }

    @Override
    public AnyValue functionCall(int id, AnyValue[] arguments) throws ProcedureException {
        return procedureCaller().callFunction(id, arguments);
    }

    @Override
    public AnyValue builtInFunctionCall(int id, AnyValue[] arguments) throws ProcedureException {
        return procedureCaller().callBuiltInFunction(id, arguments);
    }

    @Override
    public UserAggregationReducer aggregationFunction(int id) throws ProcedureException {
        return procedureCaller().createAggregationFunction(id);
    }

    @Override
    public UserAggregationReducer builtInAggregationFunction(int id) throws ProcedureException {
        return procedureCaller().createBuiltInAggregationFunction(id);
    }

    @Override
    public UserFunctionHandle functionGet(QualifiedName name) {
        performCheckBeforeOperation();
        return globalProcedures.function(name);
    }

    @Override
    public Stream<UserFunctionSignature> functionGetAll() {
        performCheckBeforeOperation();
        return globalProcedures.getAllNonAggregatingFunctions();
    }

    @Override
    public ProcedureHandle procedureGet(QualifiedName name) throws ProcedureException {
        performCheckBeforeOperation();
        return globalProcedures.procedure(name);
    }

    @Override
    public Set<ProcedureSignature> proceduresGetAll() {
        performCheckBeforeOperation();
        return globalProcedures.getAllProcedures();
    }

    @Override
    public UserFunctionHandle aggregationFunctionGet(QualifiedName name) {
        performCheckBeforeOperation();
        return globalProcedures.aggregationFunction(name);
    }

    @Override
    public Stream<UserFunctionSignature> aggregationFunctionGetAll() {
        performCheckBeforeOperation();
        return globalProcedures.getAllAggregatingFunctions();
    }

    abstract ProcedureCaller procedureCaller();

    public static class ForTransactionScope extends AllStoreHolder {

        private final KernelTransactionImplementation ktx;
        private final ProcedureCaller procedureCaller;

        public ForTransactionScope(
                StorageReader storageReader,
                TokenRead tokenRead,
                KernelTransactionImplementation ktx,
                StorageLocks storageLocks,
                DefaultPooledCursors cursors,
                GlobalProcedures globalProcedures,
                SchemaState schemaState,
                IndexingService indexingService,
                IndexStatisticsStore indexStatisticsStore,
                Dependencies databaseDependencies,
                MemoryTracker memoryTracker) {
            super(
                    storageReader,
                    tokenRead,
                    schemaState,
                    indexingService,
                    indexStatisticsStore,
                    globalProcedures,
                    memoryTracker,
                    cursors,
                    ktx.storeCursors(),
                    storageLocks,
                    ktx.lockTracer());

            this.ktx = ktx;
            this.procedureCaller = new ProcedureCaller.ForTransactionScope(ktx, globalProcedures, databaseDependencies);
        }

        @Override
        public TransactionState txState() {
            return ktx.txState();
        }

        @Override
        public boolean hasTxStateWithChanges() {
            return ktx.hasTxStateWithChanges();
        }

        @Override
        void performCheckBeforeOperation() {
            if (ParallelAccessCheck.shouldPerformCheck()) {
                ParallelAccessCheck.checkNotCypherWorkerThread();
            }
            ktx.assertOpen();
        }

        @Override
        AccessMode getAccessMode() {
            return ktx.securityContext().mode();
        }

        @Override
        Locks.Client getLockClient() {
            // lock client has to be accessed like this, because of KernelTransaction#freezeLocks
            return ktx.lockClient();
        }

        @Override
        public CursorContext cursorContext() {
            return ktx.cursorContext();
        }

        @Override
        ProcedureCaller procedureCaller() {
            return procedureCaller;
        }
    }

    public static class ForThreadExecutionContextScope extends AllStoreHolder {

        private final OverridableSecurityContext overridableSecurityContext;
        private final CursorContext cursorContext;
        private final Locks.Client lockClient;
        private final AssertOpen assertOpen;
        private final ProcedureCaller procedureCaller;

        public ForThreadExecutionContextScope(
                ThreadExecutionContext executionContext,
                StorageReader storageReader,
                SchemaState schemaState,
                IndexingService indexingService,
                IndexStatisticsStore indexStatisticsStore,
                GlobalProcedures globalProcedures,
                Dependencies databaseDependencies,
                DefaultPooledCursors cursors,
                StoreCursors storageCursors,
                CursorContext cursorContext,
                StorageLocks storageLocks,
                Locks.Client lockClient,
                LockTracer lockTracer,
                OverridableSecurityContext overridableSecurityContext,
                AssertOpen assertOpen,
                SecurityAuthorizationHandler securityAuthorizationHandler,
                Supplier<ClockContext> clockContextSupplier) {
            super(
                    storageReader,
                    executionContext.tokenRead(),
                    schemaState,
                    indexingService,
                    indexStatisticsStore,
                    globalProcedures,
                    executionContext.memoryTracker(),
                    cursors,
                    storageCursors,
                    storageLocks,
                    lockTracer);
            this.overridableSecurityContext = overridableSecurityContext;
            this.cursorContext = cursorContext;
            this.lockClient = lockClient;
            this.assertOpen = assertOpen;
            this.procedureCaller = new ProcedureCaller.ForThreadExecutionContextScope(
                    executionContext,
                    globalProcedures,
                    databaseDependencies,
                    overridableSecurityContext,
                    assertOpen,
                    securityAuthorizationHandler,
                    clockContextSupplier);
        }

        @Override
        public long lockingNodeUniqueIndexSeek(
                IndexDescriptor index, NodeValueIndexCursor cursor, PropertyIndexQuery.ExactPredicate... predicates) {
            // This is currently a problematic operation for parallel execution, because it takes exclusive locks.
            // In transactions deadlocks is a problem for another day :) .
            throw new UnsupportedOperationException("Locking unique index seek not allowed during parallel execution");
        }

        @Override
        public long lockingRelationshipUniqueIndexSeek(
                IndexDescriptor index,
                RelationshipValueIndexCursor cursor,
                PropertyIndexQuery.ExactPredicate... predicates)
                throws KernelException {
            // This is currently a problematic operation for parallel execution, because it takes exclusive locks.
            // In transactions deadlocks is a problem for another day :) .
            throw new UnsupportedOperationException("Locking unique index seek not allowed during parallel execution");
        }

        @Override
        public RawIterator<AnyValue[], ProcedureException> procedureCallWrite(
                int id, AnyValue[] arguments, ProcedureCallContext context) {
            throw new UnsupportedOperationException(
                    "Invoking procedure with WRITE access mode is not allowed during parallel execution.");
        }

        @Override
        public RawIterator<AnyValue[], ProcedureException> procedureCallSchema(
                int id, AnyValue[] arguments, ProcedureCallContext context) {
            throw new UnsupportedOperationException(
                    "Invoking procedure with SCHEMA access mode is not allowed during parallel execution.");
        }

        @Override
        public TransactionState txState() {
            throw new UnsupportedOperationException(
                    "Accessing transaction state is not allowed during parallel execution");
        }

        @Override
        public boolean hasTxStateWithChanges() {
            return false;
        }

        @Override
        void performCheckBeforeOperation() {
            assertOpen.assertOpen();
        }

        @Override
        AccessMode getAccessMode() {
            return overridableSecurityContext.currentSecurityContext().mode();
        }

        @Override
        Locks.Client getLockClient() {
            return lockClient;
        }

        @Override
        public CursorContext cursorContext() {
            return cursorContext;
        }

        @Override
        ProcedureCaller procedureCaller() {
            return procedureCaller;
        }
    }
}
