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
package org.neo4j.cypher.internal.runtime.interpreted.commands.expressions

import org.eclipse.collections.impl.block.factory.primitive.LongPredicates
import org.neo4j.cypher.internal.runtime.ReadableRow
import org.neo4j.cypher.internal.runtime.interpreted.commands.AstNode
import org.neo4j.cypher.internal.runtime.interpreted.commands.ShortestPath
import org.neo4j.cypher.internal.runtime.interpreted.commands.SingleNode
import org.neo4j.cypher.internal.runtime.interpreted.pipes.QueryState
import org.neo4j.cypher.internal.runtime.interpreted.pipes.RelationshipTypes
import org.neo4j.cypher.internal.util.attribution.Id
import org.neo4j.exceptions.CypherTypeException
import org.neo4j.exceptions.ShortestPathCommonEndNodesForbiddenException
import org.neo4j.exceptions.SyntaxException
import org.neo4j.graphdb.NotFoundException
import org.neo4j.graphdb.Relationship
import org.neo4j.internal.kernel.api.RelationshipTraversalCursor
import org.neo4j.internal.kernel.api.helpers.BiDirectionalBFS
import org.neo4j.memory.MemoryTracker
import org.neo4j.values.AnyValue
import org.neo4j.values.storable.Values
import org.neo4j.values.virtual.VirtualNodeValue
import org.neo4j.values.virtual.VirtualValues

import scala.jdk.CollectionConverters.IteratorHasAsScala

case class ShortestPathExpression(
  shortestPathPattern: ShortestPath,
  disallowSameNode: Boolean = true,
  operatorId: Id = Id.INVALID_ID
) extends Expression {

  def apply(row: ReadableRow, state: QueryState): AnyValue = {
    apply(row, state, state.memoryTrackerForOperatorProvider.memoryTrackerForOperator(operatorId.x))
  }

  def apply(row: ReadableRow, state: QueryState, memoryTracker: MemoryTracker): AnyValue = {
    if (anyStartpointsContainNull(row)) {
      Values.NO_VALUE
    } else {
      val sourceNodeId = getEndPointId(row, state, shortestPathPattern.left)
      val targetNodeId = getEndPointId(row, state, shortestPathPattern.right)
      if (
        !shortestPathPattern.allowZeroLength && disallowSameNode && sourceNodeId
          .equals(targetNodeId)
      ) throw new ShortestPathCommonEndNodesForbiddenException
      getMatches(sourceNodeId, targetNodeId, state, memoryTracker)
    }
  }

  private def getMatches(
    sourceNodeId: Long,
    targetNodeId: Long,
    state: QueryState,
    memoryTracker: MemoryTracker
  ): AnyValue = {

    val types = RelationshipTypes(shortestPathPattern.relTypes.toArray)

    val nodeCursor = state.query.nodeCursor()
    val traversalCursor = state.query.traversalCursor()

    val biDirectionalBFS = new BiDirectionalBFS(
      sourceNodeId,
      targetNodeId,
      types.types(state.query),
      shortestPathPattern.dir,
      shortestPathPattern.maxDepth.getOrElse(Int.MaxValue),
      shortestPathPattern.single,
      state.query.transactionalContext.dataRead,
      nodeCursor,
      traversalCursor,
      memoryTracker,
      LongPredicates.alwaysTrue(),
      (_: RelationshipTraversalCursor) => true
    )
    val shortestPathIterator = biDirectionalBFS.shortestPathIterator()

    val matches =
      if (shortestPathPattern.single) {
        if (shortestPathIterator.hasNext) {
          shortestPathIterator.next()
        } else
          Values.NO_VALUE
      } else {
        VirtualValues.list(shortestPathIterator.asScala.toList: _*)
      }
    biDirectionalBFS.close()
    nodeCursor.close()
    traversalCursor.close()
    matches
  }

  private def getEndPointId(ctx: ReadableRow, state: QueryState, start: SingleNode): Long = {
    try {
      ctx.getByName(start.name) match {
        case node: VirtualNodeValue => node.id()
        case _                      => throw new CypherTypeException(s"${start.name} is not a node")
      }
    } catch {
      case _: NotFoundException =>
        throw new SyntaxException(
          s"To find a shortest path, both ends of the path need to be provided. Couldn't find `$start`"
        )
    }
  }

  private def anyStartpointsContainNull(ctx: ReadableRow): Boolean =
    (ctx.getByName(shortestPathPattern.left.name) eq Values.NO_VALUE) ||
      (ctx.getByName(shortestPathPattern.right.name) eq Values.NO_VALUE)

  override def children: Seq[AstNode[_]] = Seq(shortestPathPattern)

  override def arguments: Seq[Expression] = Seq.empty

  override def rewrite(f: Expression => Expression): Expression =
    f(ShortestPathExpression(shortestPathPattern.rewrite(f), operatorId = operatorId))
}

object ShortestPathExpression {

  def noDuplicates(relationships: Iterable[Relationship]): Boolean = {
    relationships.map(_.getId).toSet.size == relationships.size
  }
}
