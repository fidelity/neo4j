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
package org.neo4j.cypher.internal.runtime.slotted.expressions

import org.neo4j.cypher.internal.runtime.ReadableRow
import org.neo4j.cypher.internal.runtime.interpreted.commands.AstNode
import org.neo4j.cypher.internal.runtime.interpreted.commands.expressions.Expression
import org.neo4j.cypher.internal.runtime.interpreted.commands.expressions.PathValueBuilder
import org.neo4j.cypher.internal.runtime.interpreted.pipes.QueryState
import org.neo4j.cypher.operations.CypherFunctions
import org.neo4j.cypher.operations.CypherFunctions.endNode
import org.neo4j.cypher.operations.CypherFunctions.startNode
import org.neo4j.exceptions.CypherTypeException
import org.neo4j.values.AnyValue
import org.neo4j.values.storable.Values.NO_VALUE
import org.neo4j.values.virtual.ListValue
import org.neo4j.values.virtual.VirtualRelationshipValue
import org.neo4j.values.virtual.VirtualValues

object SlottedProjectedPath {

  trait Projector {
    def apply(context: ReadableRow, state: QueryState, builder: PathValueBuilder): PathValueBuilder

    /**
     * The Expressions used in this Projector
     */
    def arguments: Seq[Expression]
  }

  object nilProjector extends Projector {
    override def apply(ctx: ReadableRow, state: QueryState, builder: PathValueBuilder): PathValueBuilder = builder

    override def arguments: Seq[Expression] = Seq.empty
  }

  case class singleNodeProjector(node: Expression, tailProjector: Projector) extends Projector {

    override def apply(ctx: ReadableRow, state: QueryState, builder: PathValueBuilder): PathValueBuilder = {
      val nodeValue = node.apply(ctx, state)
      tailProjector(ctx, state, builder.addNode(nodeValue))
    }

    override def arguments: Seq[Expression] = Seq(node) ++ tailProjector.arguments
  }

  case class singleRelationshipWithKnownTargetProjector(rel: Expression, target: Expression, tailProjector: Projector)
      extends Projector {

    override def apply(ctx: ReadableRow, state: QueryState, builder: PathValueBuilder): PathValueBuilder = {
      val relValue = rel.apply(ctx, state)
      val nodeValue = target.apply(ctx, state)
      tailProjector(ctx, state, builder.addRelationship(relValue).addNode(nodeValue))
    }

    override def arguments: Seq[Expression] = Seq(rel, target) ++ tailProjector.arguments
  }

  case class singleIncomingRelationshipProjector(rel: Expression, tailProjector: Projector) extends Projector {

    override def apply(ctx: ReadableRow, state: QueryState, builder: PathValueBuilder): PathValueBuilder =
      tailProjector(ctx, state, addIncoming(rel.apply(ctx, state), state, builder))

    override def arguments: Seq[Expression] = Seq(rel) ++ tailProjector.arguments
  }

  case class singleOutgoingRelationshipProjector(rel: Expression, tailProjector: Projector) extends Projector {

    override def apply(ctx: ReadableRow, state: QueryState, builder: PathValueBuilder): PathValueBuilder =
      tailProjector(ctx, state, addOutgoing(rel.apply(ctx, state), state, builder))

    override def arguments: Seq[Expression] = Seq(rel) ++ tailProjector.arguments
  }

  case class singleUndirectedRelationshipProjector(rel: Expression, tailProjector: Projector) extends Projector {

    override def apply(ctx: ReadableRow, state: QueryState, builder: PathValueBuilder): PathValueBuilder =
      tailProjector(ctx, state, addUndirected(rel.apply(ctx, state), state, builder))

    override def arguments: Seq[Expression] = Seq(rel) ++ tailProjector.arguments
  }

  case class multiIncomingRelationshipWithKnownTargetProjector(
    rel: Expression,
    node: Expression,
    tailProjector: Projector
  ) extends Projector {

    override def apply(ctx: ReadableRow, state: QueryState, builder: PathValueBuilder): PathValueBuilder =
      rel.apply(ctx, state) match {
        case list: ListValue if list.nonEmpty() =>
          val aggregated = addAllExceptLast(builder, list, (b, v) => b.addIncomingRelationship(v))
          tailProjector(ctx, state, aggregated.addRelationship(list.last()).addNode(node.apply(ctx, state)))

        case _: ListValue       => tailProjector(ctx, state, builder)
        case x if x eq NO_VALUE => tailProjector(ctx, state, builder.addNoValue())
        case value              => throw new CypherTypeException(s"Expected ListValue but got ${value.getTypeName}")
      }

    override def arguments: Seq[Expression] = Seq(rel, node) ++ tailProjector.arguments
  }

  case class multiOutgoingRelationshipWithKnownTargetProjector(
    rel: Expression,
    node: Expression,
    tailProjector: Projector
  ) extends Projector {

    override def apply(ctx: ReadableRow, state: QueryState, builder: PathValueBuilder): PathValueBuilder =
      rel.apply(ctx, state) match {
        case list: ListValue if list.nonEmpty() =>
          val aggregated = addAllExceptLast(builder, list, (b, v) => b.addOutgoingRelationship(v))
          tailProjector(ctx, state, aggregated.addRelationship(list.last()).addNode(node.apply(ctx, state)))

        case _: ListValue       => tailProjector(ctx, state, builder)
        case x if x eq NO_VALUE => tailProjector(ctx, state, builder.addNoValue())
        case value              => throw new CypherTypeException(s"Expected ListValue but got ${value.getTypeName}")
      }

    override def arguments: Seq[Expression] = Seq(rel, node) ++ tailProjector.arguments
  }

  case class multiUndirectedRelationshipWithKnownTargetProjector(
    rel: Expression,
    node: Expression,
    tailProjector: Projector
  ) extends Projector {

    override def apply(ctx: ReadableRow, state: QueryState, builder: PathValueBuilder): PathValueBuilder =
      rel.apply(ctx, state) match {
        case list: ListValue if list.nonEmpty() =>
          val aggregated = addAllExceptLast(builder, list, (b, v) => b.addUndirectedRelationship(v))
          tailProjector(ctx, state, aggregated.addRelationship(list.last()).addNode(node.apply(ctx, state)))

        case _: ListValue       => tailProjector(ctx, state, builder)
        case x if x eq NO_VALUE => tailProjector(ctx, state, builder.addNoValue())
        case value              => throw new CypherTypeException(s"Expected ListValue but got ${value.getTypeName}")
      }

    override def arguments: Seq[Expression] = Seq(rel, node) ++ tailProjector.arguments
  }

  case class multiIncomingRelationshipProjector(rel: Expression, tailProjector: Projector) extends Projector {

    override def apply(ctx: ReadableRow, state: QueryState, builder: PathValueBuilder): PathValueBuilder = {
      val relListValue = rel.apply(ctx, state)
      // we know these relationships have already loaded start and end relationship
      // so we should not use CypherFunctions::[start,end]Node to look them up
      tailProjector(ctx, state, builder.addIncomingRelationships(relListValue))
    }

    override def arguments: Seq[Expression] = Seq(rel) ++ tailProjector.arguments
  }

  case class multiOutgoingRelationshipProjector(rel: Expression, tailProjector: Projector) extends Projector {

    override def apply(ctx: ReadableRow, state: QueryState, builder: PathValueBuilder): PathValueBuilder = {
      val relListValue = rel.apply(ctx, state)
      // we know these relationships have already loaded start and end relationship
      // so we should not use CypherFunctions::[start,end]Node to look them up
      tailProjector(ctx, state, builder.addOutgoingRelationships(relListValue))
    }

    override def arguments: Seq[Expression] = Seq(rel) ++ tailProjector.arguments
  }

  case class multiUndirectedRelationshipProjector(rel: Expression, tailProjector: Projector) extends Projector {

    override def apply(ctx: ReadableRow, state: QueryState, builder: PathValueBuilder): PathValueBuilder = {
      val relListValue = rel.apply(ctx, state)
      // we know these relationships have already loaded start and end relationship
      // so we should not use CypherFunctions::[start,end]Node to look them up
      tailProjector(ctx, state, builder.addUndirectedRelationships(relListValue))
    }

    override def arguments: Seq[Expression] = Seq(rel) ++ tailProjector.arguments
  }

  private def addIncoming(relValue: AnyValue, state: QueryState, builder: PathValueBuilder) = relValue match {
    case r: VirtualRelationshipValue =>
      builder.addRelationship(r).addNode(startNode(r, state.query, state.cursors.relationshipScanCursor))

    case x if x eq NO_VALUE => builder.addNoValue()
    case _ => throw new CypherTypeException(s"Expected RelationshipValue but got ${relValue.getTypeName}")
  }

  private def addOutgoing(relValue: AnyValue, state: QueryState, builder: PathValueBuilder) = relValue match {
    case r: VirtualRelationshipValue =>
      builder.addRelationship(r).addNode(endNode(r, state.query, state.cursors.relationshipScanCursor))

    case x if x eq NO_VALUE => builder.addNoValue()
    case _ => throw new CypherTypeException(s"Expected RelationshipValue but got ${relValue.getTypeName}")
  }

  private def addUndirected(relValue: AnyValue, state: QueryState, builder: PathValueBuilder) = relValue match {
    case r: VirtualRelationshipValue =>
      val previous = VirtualValues.node(builder.previousNode)
      builder.addRelationship(r).addNode(CypherFunctions.otherNode(
        r,
        state.query,
        previous,
        state.cursors.relationshipScanCursor
      ))

    case x if x eq NO_VALUE => builder.addNoValue()
    case _ => throw new CypherTypeException(s"Expected RelationshipValue but got ${relValue.getTypeName}")
  }

  private def addAllExceptLast(
    builder: PathValueBuilder,
    list: ListValue,
    f: (PathValueBuilder, AnyValue) => PathValueBuilder
  ) = {
    var aggregated = builder
    val size = list.size()
    var i = 0
    while (i < size - 1) {
      // we know these relationships have already loaded start and end relationship
      // so we should not use CypherFunctions::[start,end]Node to look them up
      aggregated = f(aggregated, list.value(i))
      i += 1
    }
    aggregated
  }
}

/*
 Expressions for materializing new paths (used by ronja)

 These expressions cannot be generated by the user directly
 */
case class SlottedProjectedPath(projector: SlottedProjectedPath.Projector) extends Expression {

  override def apply(row: ReadableRow, state: QueryState): AnyValue =
    projector(row, state, state.clearPathValueBuilder).result()

  override def arguments: Seq[Expression] = Seq.empty

  override def rewrite(f: Expression => Expression): Expression = f(this)

  override def children: Seq[AstNode[_]] = projector.arguments
}
