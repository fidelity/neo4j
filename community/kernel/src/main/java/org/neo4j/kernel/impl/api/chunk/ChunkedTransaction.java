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
package org.neo4j.kernel.impl.api.chunk;

import static org.neo4j.kernel.impl.api.TransactionToApply.TRANSACTION_ID_NOT_SPECIFIED;

import java.io.IOException;
import java.util.Iterator;
import org.neo4j.common.Subject;
import org.neo4j.internal.helpers.collection.Visitor;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.kernel.impl.api.txid.TransactionIdGenerator;
import org.neo4j.kernel.impl.transaction.log.LogPosition;
import org.neo4j.storageengine.api.CommandBatch;
import org.neo4j.storageengine.api.CommandBatchToApply;
import org.neo4j.storageengine.api.Commitment;
import org.neo4j.storageengine.api.StorageCommand;
import org.neo4j.storageengine.api.cursor.StoreCursors;

public class ChunkedTransaction implements CommandBatchToApply {

    private CommandChunk chunk;
    // to sure for what reason we need those now here?
    private final CursorContext cursorContext;
    private final StoreCursors storeCursors;
    private final Commitment commitment;
    private final TransactionIdGenerator transactionIdGenerator;
    private boolean idGenerated;
    private LogPosition lastBatchLogPosition = LogPosition.UNSPECIFIED;
    private long transactionId = TRANSACTION_ID_NOT_SPECIFIED;
    private CommandBatchToApply next;

    public ChunkedTransaction(
            CursorContext cursorContext,
            StoreCursors storeCursors,
            Commitment commitment,
            TransactionIdGenerator transactionIdGenerator) {
        this.cursorContext = cursorContext;
        this.storeCursors = storeCursors;
        this.commitment = commitment;
        this.transactionIdGenerator = transactionIdGenerator;
    }

    public void init(CommandChunk chunk) {
        this.chunk = chunk;
    }

    @Override
    public boolean accept(Visitor<StorageCommand, IOException> visitor) throws IOException {
        return chunk.accept(visitor);
    }

    @Override
    public Subject subject() {
        return chunk.subject();
    }

    @Override
    public long transactionId() {
        if (idGenerated) {
            return transactionId;
        }
        this.transactionId = transactionIdGenerator.nextId(transactionId);
        idGenerated = true;
        return transactionId;
    }

    @Override
    public long chunkId() {
        return chunk.chunkMetadata().chunkId();
    }

    @Override
    public LogPosition previousBatchLogPosition() {
        return chunk.chunkMetadata().previousBatchLogPosition();
    }

    @Override
    public CursorContext cursorContext() {
        return cursorContext;
    }

    @Override
    public StoreCursors storeCursors() {
        return storeCursors;
    }

    @Override
    public CommandBatchToApply next() {
        return next;
    }

    @Override
    public void next(CommandBatchToApply next) {
        this.next = next;
    }

    @Override
    public void commit() {
        if (chunk.isLast()) {
            commitment.publishAsCommitted(chunk.chunkMetadata().chunkCommitTime());
        }
    }

    public LogPosition lastBatchLogPosition() {
        return lastBatchLogPosition;
    }

    @Override
    public CommandBatch commandBatch() {
        return chunk;
    }

    @Override
    public void batchAppended(LogPosition beforeStart, LogPosition positionAfter, int checksum) {
        if (chunk.isFirst()) {
            this.commitment.commit(
                    transactionId,
                    beforeStart,
                    positionAfter,
                    checksum,
                    chunk.chunkMetadata().consensusIndex());
            this.cursorContext.getVersionContext().initWrite(transactionId);
        }
        lastBatchLogPosition = beforeStart;
    }

    @Override
    public void close() {
        commitment.publishAsClosed();
    }

    @Override
    public Iterator<StorageCommand> iterator() {
        return chunk.iterator();
    }
}
