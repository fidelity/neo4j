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
package org.neo4j.storageengine.api;

import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.layout.DatabaseLayout;
import org.neo4j.kernel.database.MetadataCache;

public interface LogFilesInitializer {
    /**
     * A LogFilesInitializer instance that doesn't do anything.
     */
    LogFilesInitializer NULL = (databaseLayout, store, metadataCache, fileSystem, checkpointReason) -> {};

    /**
     * Initialize the transaction log files in the given database layout.
     * This is usually called after creating an empty, or newly imported, store.
     */
    void initializeLogFiles(
            DatabaseLayout databaseLayout,
            MetadataProvider store,
            MetadataCache metadataCache,
            FileSystemAbstraction fileSystem,
            String checkpointReason);
}
