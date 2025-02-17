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
package org.neo4j.cypher.internal.compiler.planner.logical.plans.rewriter.eager

import org.neo4j.cypher.internal.ast.semantics.SemanticTable
import org.neo4j.cypher.internal.compiler.helpers.MapSupport.PowerMap
import org.neo4j.cypher.internal.compiler.planner.logical.plans.rewriter.eager.ReadFinder.PlanReads
import org.neo4j.cypher.internal.compiler.planner.logical.plans.rewriter.eager.ReadFinder.collectReads
import org.neo4j.cypher.internal.compiler.planner.logical.plans.rewriter.eager.WriteFinder.CreatedNode
import org.neo4j.cypher.internal.compiler.planner.logical.plans.rewriter.eager.WriteFinder.PlanCreates
import org.neo4j.cypher.internal.compiler.planner.logical.plans.rewriter.eager.WriteFinder.PlanDeletes
import org.neo4j.cypher.internal.compiler.planner.logical.plans.rewriter.eager.WriteFinder.PlanSets
import org.neo4j.cypher.internal.compiler.planner.logical.plans.rewriter.eager.WriteFinder.PlanWrites
import org.neo4j.cypher.internal.compiler.planner.logical.plans.rewriter.eager.WriteFinder.collectWrites
import org.neo4j.cypher.internal.compiler.planner.logical.steps.QuerySolvableByGetDegree.SetExtractor
import org.neo4j.cypher.internal.expressions.Ands
import org.neo4j.cypher.internal.expressions.Expression
import org.neo4j.cypher.internal.expressions.LabelName
import org.neo4j.cypher.internal.expressions.LogicalVariable
import org.neo4j.cypher.internal.expressions.Ors
import org.neo4j.cypher.internal.expressions.PropertyKeyName
import org.neo4j.cypher.internal.expressions.SymbolicName
import org.neo4j.cypher.internal.expressions.Variable
import org.neo4j.cypher.internal.logical.plans.AbstractLetSelectOrSemiApply
import org.neo4j.cypher.internal.logical.plans.AbstractLetSemiApply
import org.neo4j.cypher.internal.logical.plans.AbstractSelectOrSemiApply
import org.neo4j.cypher.internal.logical.plans.AntiConditionalApply
import org.neo4j.cypher.internal.logical.plans.AntiSemiApply
import org.neo4j.cypher.internal.logical.plans.Apply
import org.neo4j.cypher.internal.logical.plans.ApplyPlan
import org.neo4j.cypher.internal.logical.plans.AssertSameNode
import org.neo4j.cypher.internal.logical.plans.CartesianProduct
import org.neo4j.cypher.internal.logical.plans.ForeachApply
import org.neo4j.cypher.internal.logical.plans.LeftOuterHashJoin
import org.neo4j.cypher.internal.logical.plans.LogicalBinaryPlan
import org.neo4j.cypher.internal.logical.plans.LogicalLeafPlan
import org.neo4j.cypher.internal.logical.plans.LogicalPlan
import org.neo4j.cypher.internal.logical.plans.LogicalPlans
import org.neo4j.cypher.internal.logical.plans.NodeHashJoin
import org.neo4j.cypher.internal.logical.plans.Optional
import org.neo4j.cypher.internal.logical.plans.OrderedUnion
import org.neo4j.cypher.internal.logical.plans.RightOuterHashJoin
import org.neo4j.cypher.internal.logical.plans.RollUpApply
import org.neo4j.cypher.internal.logical.plans.SemiApply
import org.neo4j.cypher.internal.logical.plans.SubqueryForeach
import org.neo4j.cypher.internal.logical.plans.TransactionForeach
import org.neo4j.cypher.internal.logical.plans.Union
import org.neo4j.cypher.internal.logical.plans.ValueHashJoin
import org.neo4j.cypher.internal.util.InputPosition
import org.neo4j.cypher.internal.util.Ref

object ReadsAndWritesFinder {

  /**
   * Stores information about what plans read which symbol (label, property, relationship type).
   * This is a generalization from just a map from a symbol to a collection of plans, because some operators can read unknown labels or properties.
   *
   * @param plansReadingConcreteSymbol map for finding plans which read a specific/concrete symbol
   * @param plansReadingUnknownSymbols all plans which read unknown symbols (of the given type)
   * @tparam T type of symbol, that is whether this stores labels, relationship types or properties
   */
  private[eager] case class ReadingPlansProvider[T <: SymbolicName](
    plansReadingConcreteSymbol: Map[T, Seq[LogicalPlan]] = Map.empty[T, Seq[LogicalPlan]],
    plansReadingUnknownSymbols: Seq[LogicalPlan] = Seq.empty
  ) {

    /**
     * @return all plans reading the given concrete symbol or unknown symbols.
     */
    def plansReadingSymbol(symbol: T): Seq[LogicalPlan] =
      plansReadingConcreteSymbol.getOrElse(symbol, Seq.empty) ++ plansReadingUnknownSymbols

    /**
     * @return all plans reading this kind of symbol.
     */
    def plansReadingAnySymbol(): Seq[LogicalPlan] =
      plansReadingConcreteSymbol.values.flatten.toSeq ++ plansReadingUnknownSymbols

    def withSymbolRead(symbol: T, plan: LogicalPlan): ReadingPlansProvider[T] = {
      val previousPlans = plansReadingConcreteSymbol.getOrElse(symbol, Seq.empty)
      copy(plansReadingConcreteSymbol.updated(symbol, previousPlans :+ plan))
    }

    def withUnknownSymbolsRead(plan: LogicalPlan): ReadingPlansProvider[T] = {
      copy(plansReadingUnknownSymbols = plansReadingUnknownSymbols :+ plan)
    }

    def ++(other: ReadingPlansProvider[T]): ReadingPlansProvider[T] =
      copy(
        plansReadingConcreteSymbol.fuse(other.plansReadingConcreteSymbol)(_ ++ _),
        plansReadingUnknownSymbols ++ other.plansReadingUnknownSymbols
      )
  }

  /**
   * Stores information about what plans write which property.
   * This is a generalization from just a map from a property to a collection of plans, because some operators can write unknown properties.
   *
   * @param plansWritingConcreteProperty A Map from the property name to the plans that write that property.
   * @param plansWritingUnknownProperty  all plans which write an unknown property
   */
  private[eager] case class PropertyWritingPlansProvider(
    plansWritingConcreteProperty: Map[PropertyKeyName, Seq[LogicalPlan]] = Map.empty,
    plansWritingUnknownProperty: Seq[LogicalPlan] = Seq.empty
  ) {

    def withPropertyWritten(property: PropertyKeyName, plan: LogicalPlan): PropertyWritingPlansProvider = {
      val previousPlans = plansWritingConcreteProperty.getOrElse(property, Seq.empty)
      copy(plansWritingConcreteProperty.updated(property, previousPlans :+ plan))
    }

    def withUnknownPropertyWritten(plan: LogicalPlan): PropertyWritingPlansProvider = {
      copy(plansWritingUnknownProperty = plansWritingUnknownProperty :+ plan)
    }

    def ++(other: PropertyWritingPlansProvider): PropertyWritingPlansProvider =
      copy(
        plansWritingConcreteProperty.fuse(other.plansWritingConcreteProperty)(_ ++ _),
        plansWritingUnknownProperty ++ other.plansWritingUnknownProperty
      )

    /**
     * @return A `Seq` of pairs that map a known `PropertyKeyName` (if `Some`) or an unknown `PropertyKeyName` (if `None`)
     *         to all plans writing that property.
     */
    def entries: Seq[(Option[PropertyKeyName], Seq[LogicalPlan])] =
      plansWritingConcreteProperty.toSeq.map {
        case (key, value) => (Some(key), value)
      } :+ (None, plansWritingUnknownProperty)
  }

  /**
   * This class groups expression that all filter on the same variable.
   *
   * @param plansThatIntroduceVariable all plans that introduce the variable that the expression filters on.
   * @param expression                 an expression of all predicates related to one variable.
   */
  case class FilterExpressions(
    plansThatIntroduceVariable: Set[Ref[LogicalPlan]],
    expression: Expression = Ands(Seq.empty)(InputPosition.NONE)
  ) {

    def withAddedExpression(newExp: Expression): FilterExpressions = {
      val compositeExpression = expression match {
        case Ands(SetExtractor()) => newExp
        case Ands(exprs)          => Ands(exprs + newExp)(InputPosition.NONE)
        case _                    => Ands(Seq(expression, newExp))(InputPosition.NONE)
      }
      copy(expression = compositeExpression)
    }

    def ++(other: FilterExpressions, mergePlan: LogicalBinaryPlan): FilterExpressions = {
      val allPlans = this.plansThatIntroduceVariable ++ other.plansThatIntroduceVariable

      val lhs = this
      val rhs = other
      val compositeExpression = mergePlan match {
        case _: Union | _: OrderedUnion =>
          // Union expresses OR
          Ors(Seq(lhs.expression, rhs.expression))(InputPosition.NONE)
        case _: NodeHashJoin | _: ValueHashJoin | _: AssertSameNode =>
          // Joins express AND
          // Let's use withAddedExpression to avoid nesting Ands
          lhs.withAddedExpression(rhs.expression).expression
        case _: LeftOuterHashJoin =>
          // RHS predicates might not be applied if the rows are not matched in the hash table
          lhs.expression
        case _: RightOuterHashJoin =>
          // LHS predicates might not be applied if the rows are not matched in the hash table
          rhs.expression
        case _: CartesianProduct =>
          // If under an apply, the branches can share an argument variable and then we want to combine the predicates,
          // but the rhs includes the lhs already, so no need to merge.
          rhs.expression
        case _: AbstractLetSelectOrSemiApply |
          _: AbstractSelectOrSemiApply |
          _: AbstractLetSemiApply =>
          // These SemiApplyPlans express predicates for exists with an OR between predicates,
          // and then we must exclude the RHS predicates to be safe, e.g.
          // MATCH /* unstable */ (a:A) WHERE exists { (a:B) } OR true CREATE (:A)
          // should be Eager.
          lhs.expression
        case _: AntiSemiApply =>
          // Something like lhs.expression AND NOT rhs.expression would be correct,
          // but given the way LogicalPlans.foldPlan works, rhs.expression actually contains lhs.expression.
          // A safe, but too conservative solution is to simply exclude the RHS predicates.
          lhs.expression
        case _: RollUpApply |
          _: SubqueryForeach |
          _: TransactionForeach |
          _: ForeachApply |
          _: AntiConditionalApply =>
          // These plans are used for subqueries and sometimes yield lhs rows more or less unchanged.
          // So any rhs predicates on already defined variables must not be considered.
          lhs.expression
        case ApplyPlan(_, applyRhs) if applyRhs.folder.treeFindByClass[Optional].nonEmpty =>
          // RHS predicates might not be applied if the rows are filtered out by Optional
          lhs.expression
        case _: Apply |
          _: SemiApply =>
          // These ApplyPlans simply combine the predicates,
          // but the rhs includes the lhs already, so no need to merge.
          rhs.expression
        case _: ApplyPlan =>
          // For any other apply plans we exclude the RHS predicates, which is the safe option.
          lhs.expression
      }

      copy(
        plansThatIntroduceVariable = allPlans,
        expression = compositeExpression
      )
    }
  }

  /**
   * A variable can either be introduced by one (or more) leaf plans, or by some inner plan.
   * For DELETE conflicts, only the predicates from leaf plans can be included.
   *
   * This trait tracks the plans that introduce a variable (potentially multiple in the case of UNION),
   * together with the predicates solved in the plans.
   * It also tracks the plans that last references the variable (potentially multiple in the case of UNION).
   *
   * The variable itself is not tracked here, but is the key in [[Reads.possibleNodeDeleteConflictPlans]].
   *
   * @param plansThatIntroduceVariable  a list of plans that introduce the variable
   *                                     or have a filter on the variable. The plan is bundled with
   *                                     a list of predicates that all depend on the variable.
   * @param lastPlansToReferenceVariable the last plans that reference the variable. These are the read plans that must be used to define
   *                                     the Conflict on. We cannot use an earlier plan (e.g. where the variable was introduced), like in
   *                                     CREATE/MATCH conflicts, because evaluating any expression on a deleted node might crash.
   */
  case class PossibleDeleteConflictPlans(
    plansThatIntroduceVariable: Seq[PlanThatIntroducesVariable],
    lastPlansToReferenceVariable: Seq[LogicalPlan]
  ) {

    def ++(other: PossibleDeleteConflictPlans): PossibleDeleteConflictPlans = {
      PossibleDeleteConflictPlans(
        this.plansThatIntroduceVariable ++ other.plansThatIntroduceVariable,
        this.lastPlansToReferenceVariable ++ other.lastPlansToReferenceVariable
      )
    }
  }

  /**
   * A plan that introduces a variable together with the predicates for that variable solved by that plan.
   */
  case class PlanThatIntroducesVariable(plan: LogicalPlan, predicates: Seq[Expression])

  /**
   * An accumulator of reads in the logical plan tree.
   *
   * @param readProperties                  a provider to find out which plans read which properties.
   * @param readLabels                      a provider to find out which plans read which labels.
   * @param nodeFilterExpressions           for each node variable the expressions that filter on that variable.
   *                                        This also tracks if a variable is introduced by a plan.
   *                                        If a variable is introduced by a plan, and no predicates are applied on that variable,
   *                                        it is still present as a key in this map.
   * @param possibleNodeDeleteConflictPlans for each node variable, the [[PossibleDeleteConflictPlans]]
   */
  private[eager] case class Reads(
    readProperties: ReadingPlansProvider[PropertyKeyName] = ReadingPlansProvider(),
    readLabels: ReadingPlansProvider[LabelName] = ReadingPlansProvider(),
    nodeFilterExpressions: Map[LogicalVariable, FilterExpressions] = Map.empty,
    possibleNodeDeleteConflictPlans: Map[LogicalVariable, PossibleDeleteConflictPlans] = Map.empty
  ) {

    /**
     * @param property if `Some(prop)` look for plans that could potentially read that property,
     *                 if `None` look for plans that read any property.
     * @return all plans that read the given property.
     */
    def plansReadingProperty(property: Option[PropertyKeyName]): Seq[LogicalPlan] =
      property match {
        case Some(property) => readProperties.plansReadingSymbol(property)
        case None           => readProperties.plansReadingAnySymbol()
      }

    /**
     * @return all plans that could read the given label.
     */
    def plansReadingLabel(label: LabelName): Seq[LogicalPlan] =
      readLabels.plansReadingSymbol(label)

    def withPropertyRead(property: PropertyKeyName, plan: LogicalPlan): Reads =
      copy(readProperties = readProperties.withSymbolRead(property, plan))

    def withUnknownPropertiesRead(plan: LogicalPlan): Reads =
      copy(readProperties = readProperties.withUnknownSymbolsRead(plan))

    def withLabelRead(label: LabelName, plan: LogicalPlan): Reads = {
      copy(readLabels = readLabels.withSymbolRead(label, plan))
    }

    /**
     * Save that the plan introduces a variable.
     * This is done by saving an empty filter expressions.
     */
    def withIntroducedNodeVariable(
      variable: LogicalVariable,
      plan: LogicalPlan
    ): Reads = {
      val newExpressions = nodeFilterExpressions.getOrElse(variable, FilterExpressions(Set(Ref(plan))))
      copy(nodeFilterExpressions = nodeFilterExpressions + (variable -> newExpressions))
    }

    def withAddedNodeFilterExpression(
      variable: LogicalVariable,
      plan: LogicalPlan,
      filterExpression: Expression
    ): Reads = {
      val newExpressions =
        nodeFilterExpressions.getOrElse(variable, FilterExpressions(Set(Ref(plan)))).withAddedExpression(
          filterExpression
        )
      copy(nodeFilterExpressions = nodeFilterExpressions + (variable -> newExpressions))
    }

    def withUnknownLabelsRead(plan: LogicalPlan): Reads =
      copy(readLabels = readLabels.withUnknownSymbolsRead(plan))

    /**
     * Update [[PossibleDeleteConflictPlans]].
     * This should be called if `plan` references `variable`.
     *
     * If `plan` filters on `variable`, the `expressions` should be all filters on `variable`.
     *
     * @param expressions all expressions in `plan` that filter on `variable`.
     */
    def withUpdatedPossibleDeleteConflictPlans(
      plan: LogicalPlan,
      variable: LogicalVariable,
      expressions: Seq[Expression]
    ): Reads = {
      val prev = possibleNodeDeleteConflictPlans.getOrElse(variable, PossibleDeleteConflictPlans(Seq.empty, Seq.empty))

      val plansThatIntroduceVariable =
        if (prev.plansThatIntroduceVariable.isEmpty) {
          // This plan introduces the variable.

          // We should take predicates on leaf plans into account.
          val expressionsToInclude = plan match {
            case _: LogicalLeafPlan => expressions
            case _                  => Seq.empty[Expression]
          }
          Seq(PlanThatIntroducesVariable(plan, expressionsToInclude))
        } else {
          prev.plansThatIntroduceVariable
        }

      val lastPlansToReferenceVariable = Seq(plan)

      copy(possibleNodeDeleteConflictPlans =
        possibleNodeDeleteConflictPlans
          .updated(variable, PossibleDeleteConflictPlans(plansThatIntroduceVariable, lastPlansToReferenceVariable))
      )
    }

    /**
     * Update [[PossibleDeleteConflictPlans.lastPlansToReferenceVariable]]. 
     * This should be called if a plan references a node variable.
     */
    def updateLastPlansToReferenceNodeVariable(plan: LogicalPlan, variable: LogicalVariable): Reads = {
      val prev = possibleNodeDeleteConflictPlans.getOrElse(variable, PossibleDeleteConflictPlans(Seq.empty, Seq.empty))
      val next = prev.copy(lastPlansToReferenceVariable = Seq(plan))
      copy(possibleNodeDeleteConflictPlans = possibleNodeDeleteConflictPlans.updated(variable, next))
    }

    /**
     * Return a copy that included the given [[PlanReads]] for the given [[LogicalPlan]]
     */
    def includePlanReads(plan: LogicalPlan, planReads: PlanReads): Reads = {
      Function.chain[Reads](Seq(
        acc => planReads.readProperties.foldLeft(acc)(_.withPropertyRead(_, plan)),
        acc => planReads.readLabels.foldLeft(acc)(_.withLabelRead(_, plan)),
        acc => {
          planReads.nodeFilterExpressions.foldLeft(acc) {
            case (acc, (variable, expressions)) =>
              val acc2 = acc.withUpdatedPossibleDeleteConflictPlans(plan, variable, expressions)
              if (expressions.isEmpty) {
                // The plan introduces the variable but has no filter expressions
                acc2.withIntroducedNodeVariable(variable, plan)
              } else {
                expressions.foldLeft(acc2)(_.withAddedNodeFilterExpression(variable, plan, _))
              }
          }
        },
        acc => {
          planReads.referencedNodeVariables.foldLeft(acc) {
            case (acc, variable) => acc.updateLastPlansToReferenceNodeVariable(plan, variable)
          }
        },
        acc => if (planReads.readsUnknownLabels) acc.withUnknownLabelsRead(plan) else acc,
        acc => if (planReads.readsUnknownProperties) acc.withUnknownPropertiesRead(plan) else acc
      ))(this)
    }

    /**
     * Returns a copy of this class, except that the [[FilterExpressions]] from the other plan are merged in
     * as if it was invoked like `other ++ (this, mergePlan)`.
     */
    def mergeNodeFilterExpressions(other: Reads, mergePlan: LogicalBinaryPlan): Reads = {
      copy(
        nodeFilterExpressions = other.nodeFilterExpressions.fuse(this.nodeFilterExpressions)(_ ++ (_, mergePlan))
      )
    }

    def ++(other: Reads, mergePlan: LogicalBinaryPlan): Reads = {
      copy(
        readProperties = this.readProperties ++ other.readProperties,
        readLabels = this.readLabels ++ other.readLabels,
        nodeFilterExpressions = this.nodeFilterExpressions.fuse(other.nodeFilterExpressions)(_ ++ (_, mergePlan)),
        possibleNodeDeleteConflictPlans =
          this.possibleNodeDeleteConflictPlans.fuse(other.possibleNodeDeleteConflictPlans)(_ ++ _)
      )
    }
  }

  /**
   * An accumulator of SETs in the logical plan tree.
   *
   * @param writtenProperties a provider to find out which plans set which properties.
   * @param writtenLabels     for each label, all the plans that set that label.
   */
  private[eager] case class Sets(
    writtenProperties: PropertyWritingPlansProvider = PropertyWritingPlansProvider(),
    writtenLabels: Map[LabelName, Seq[LogicalPlan]] = Map.empty
  ) {

    def withPropertyWritten(property: PropertyKeyName, plan: LogicalPlan): Sets =
      copy(writtenProperties = writtenProperties.withPropertyWritten(property, plan))

    def withUnknownPropertyWritten(plan: LogicalPlan): Sets =
      copy(writtenProperties = writtenProperties.withUnknownPropertyWritten(plan))

    def withLabelWritten(label: LabelName, plan: LogicalPlan): Sets = {
      val wL = writtenLabels.getOrElse(label, Seq.empty)
      copy(writtenLabels = writtenLabels.updated(label, wL :+ plan))
    }

    def includePlanSets(plan: LogicalPlan, planSets: PlanSets): Sets = {
      Function.chain[Sets](Seq(
        acc => planSets.writtenProperties.foldLeft(acc)(_.withPropertyWritten(_, plan)),
        acc => if (planSets.writesUnknownProperties) acc.withUnknownPropertyWritten(plan) else acc,
        acc => planSets.writtenLabels.foldLeft(acc)(_.withLabelWritten(_, plan))
      ))(this)
    }

    def ++(other: Sets): Sets = {
      copy(
        writtenProperties = this.writtenProperties ++ other.writtenProperties,
        writtenLabels = this.writtenLabels.fuse(other.writtenLabels)(_ ++ _)
      )
    }
  }

  /**
   * An accumulator of CREATEs in the logical plan tree.
   *
   * @param createdNodes                   for each plan, the created nodes.
   * @param nodeFilterExpressionsSnapshots for each plan (that we will need to look at the snapshot later), a snapshot of the current nodeFilterExpressions.
   */
  private[eager] case class Creates(
    createdNodes: Map[Ref[LogicalPlan], Set[CreatedNode]] = Map.empty,
    nodeFilterExpressionsSnapshots: Map[Ref[LogicalPlan], Map[LogicalVariable, FilterExpressions]] = Map.empty
  ) {

    /**
     * Save that `plan` creates `createdNode`.
     * Since CREATE plans need to look for conflicts in the _current_ filterExpressions, we save a snapshot of the current filterExpressions,
     * associated with the CREATE plan. If we were to include all filterExpressions, we might think that Eager is not needed, even though it is.
     * See test "inserts eager between Create and NodeByLabelScan if label overlap, and other label in Filter after create".
     */
    def withCreatedNode(
      createdNode: CreatedNode,
      plan: LogicalPlan,
      nodeFilterExpressionsSnapshot: Map[LogicalVariable, FilterExpressions]
    ): Creates = {
      val prevCreatedNodes = createdNodes.getOrElse(Ref(plan), Set.empty)
      copy(
        createdNodes = createdNodes.updated(Ref(plan), prevCreatedNodes + createdNode),
        nodeFilterExpressionsSnapshots = nodeFilterExpressionsSnapshots + (Ref(plan) -> nodeFilterExpressionsSnapshot)
      )
    }

    def includePlanCreates(
      plan: LogicalPlan,
      planCreates: PlanCreates,
      nodeFilterExpressionsSnapshots: Map[LogicalVariable, FilterExpressions]
    ): Creates = {
      planCreates.createdNodes.foldLeft(this)(_.withCreatedNode(_, plan, nodeFilterExpressionsSnapshots))
    }

    def ++(other: Creates): Creates = {
      copy(
        createdNodes = this.createdNodes.fuse(other.createdNodes)(_ ++ _),
        nodeFilterExpressionsSnapshots = this.nodeFilterExpressionsSnapshots ++ other.nodeFilterExpressionsSnapshots
      )
    }
  }

  /**
   * An accumulator of DELETEs in the logical plan tree.
   *
   * @param deletedNodeVariables                  for each plan, the nodes that are deleted by variable name
   * @param plansThatDeleteNodeExpressions        all plans that delete non-variable expressions of type node
   * @param plansThatDeleteUnknownTypeExpressions all plans that delete expressions of unknown type
   * @param possibleNodeDeleteConflictPlanSnapshots   for each plan (that we will need to look at the snapshot later), a snapshot of the current possibleNodeDeleteConflictPlans
   */
  private[eager] case class Deletes(
    deletedNodeVariables: Map[Ref[LogicalPlan], Set[Variable]] = Map.empty,
    plansThatDeleteNodeExpressions: Seq[LogicalPlan] = Seq.empty,
    plansThatDeleteUnknownTypeExpressions: Seq[LogicalPlan] = Seq.empty,
    possibleNodeDeleteConflictPlanSnapshots: Map[Ref[LogicalPlan], Map[LogicalVariable, PossibleDeleteConflictPlans]] =
      Map.empty
  ) {

    /**
     * Save that `plan` deletes the variable `deletedNode`.
     */
    def withDeletedNodeVariable(deletedNode: Variable, plan: LogicalPlan): Deletes = {
      val prevDeletedNodes = deletedNodeVariables.getOrElse(Ref(plan), Set.empty)
      copy(deletedNodeVariables = deletedNodeVariables.updated(Ref(plan), prevDeletedNodes + deletedNode))
    }

    def withPlanThatDeletesNodeExpressions(plan: LogicalPlan): Deletes = {
      copy(plansThatDeleteNodeExpressions = plansThatDeleteNodeExpressions :+ plan)
    }

    def withPlanThatDeletesUnknownTypeExpressions(plan: LogicalPlan): Deletes = {
      copy(plansThatDeleteUnknownTypeExpressions = plansThatDeleteUnknownTypeExpressions :+ plan)
    }

    /**
     * Since DELETE plans need to look for the latest plan that references a variable _before_ the DELETE plan itself,
     * we save a snapshot of the current possibleNodeDeleteConflictPlanSnapshots, associated with the DELETE plan.
     */
    def withPossibleNodeDeleteConflictPlanSnapshot(
      plan: LogicalPlan,
      snapshot: Map[LogicalVariable, PossibleDeleteConflictPlans]
    ): Deletes = {
      copy(possibleNodeDeleteConflictPlanSnapshots =
        possibleNodeDeleteConflictPlanSnapshots.updated(Ref(plan), snapshot)
      )
    }

    def includePlanDeletes(
      plan: LogicalPlan,
      planDeletes: PlanDeletes,
      possibleNodeDeleteConflictPlanSnapshot: Map[LogicalVariable, PossibleDeleteConflictPlans]
    ): Deletes = {
      Function.chain[Deletes](Seq(
        acc => planDeletes.deletedNodeVariables.foldLeft(acc)(_.withDeletedNodeVariable(_, plan)),
        acc => if (planDeletes.deletesNodeExpressions) acc.withPlanThatDeletesNodeExpressions(plan) else acc,
        acc =>
          if (planDeletes.deletesUnknownTypeExpressions) acc.withPlanThatDeletesUnknownTypeExpressions(plan) else acc,
        acc =>
          if (!planDeletes.isEmpty)
            acc.withPossibleNodeDeleteConflictPlanSnapshot(plan, possibleNodeDeleteConflictPlanSnapshot)
          else acc
      ))(this)
    }

    def ++(other: Deletes): Deletes = {
      copy(
        deletedNodeVariables = this.deletedNodeVariables.fuse(other.deletedNodeVariables)(_ ++ _),
        plansThatDeleteNodeExpressions = this.plansThatDeleteNodeExpressions ++ other.plansThatDeleteNodeExpressions,
        plansThatDeleteUnknownTypeExpressions =
          this.plansThatDeleteUnknownTypeExpressions ++ other.plansThatDeleteUnknownTypeExpressions
      )
    }
  }

  /**
   * An accumulator of writes  in the logical plan tree.
   */
  private[eager] case class Writes(
    sets: Sets = Sets(),
    creates: Creates = Creates(),
    deletes: Deletes = Deletes()
  ) {

    def withSets(sets: Sets): Writes = copy(sets = sets)

    def withCreates(creates: Creates): Writes = copy(creates = creates)

    def withDeletes(deletes: Deletes): Writes = copy(deletes = deletes)

    def includePlanWrites(
      plan: LogicalPlan,
      planWrites: PlanWrites,
      nodeFilterExpressionsSnapshot: Map[LogicalVariable, FilterExpressions],
      possibleNodeDeleteConflictPlanSnapshot: Map[LogicalVariable, PossibleDeleteConflictPlans]
    ): Writes = {
      Function.chain[Writes](Seq(
        acc => acc.withSets(acc.sets.includePlanSets(plan, planWrites.sets)),
        acc => acc.withCreates(acc.creates.includePlanCreates(plan, planWrites.creates, nodeFilterExpressionsSnapshot)),
        acc =>
          acc.withDeletes(acc.deletes.includePlanDeletes(
            plan,
            planWrites.deletes,
            possibleNodeDeleteConflictPlanSnapshot
          ))
      ))(this)
    }

    def ++(other: Writes): Writes = {
      copy(
        sets = this.sets ++ other.sets,
        creates = this.creates ++ other.creates,
        deletes = this.deletes ++ other.deletes
      )
    }
  }

  /**
   * Reads and writes of multiple plans.
   * The Seqs nested in this class may contain duplicates. These are filtered out later in [[ConflictFinder]].
   */
  private[eager] case class ReadsAndWrites(
    reads: Reads = Reads(),
    writes: Writes = Writes()
  ) {

    def withReads(reads: Reads): ReadsAndWrites = copy(reads = reads)

    def withWrites(writes: Writes): ReadsAndWrites = copy(writes = writes)

    /**
     * Returns a copy of this class, except that the [[FilterExpressions]] from the other plan are merged in
     * as if it was invoked like `other ++ (this, mergePlan)`.
     */
    def mergeNodeFilterExpressions(other: ReadsAndWrites, mergePlan: LogicalBinaryPlan): ReadsAndWrites = {
      copy(
        reads = this.reads.mergeNodeFilterExpressions(other.reads, mergePlan)
      )
    }

    def ++(other: ReadsAndWrites, mergePlan: LogicalBinaryPlan): ReadsAndWrites = {
      copy(
        reads = this.reads ++ (other.reads, mergePlan),
        writes = this.writes ++ other.writes
      )
    }
  }

  /**
   * Traverse plan in execution order and remember all reads and writes.
   */
  private[eager] def collectReadsAndWrites(
    wholePlan: LogicalPlan,
    semanticTable: SemanticTable
  ): ReadsAndWrites = {
    def processPlan(acc: ReadsAndWrites, plan: LogicalPlan): ReadsAndWrites = {
      val planReads = collectReads(plan, semanticTable)
      val planWrites = collectWrites(plan)

      Function.chain[ReadsAndWrites](Seq(
        // We update the writes first, because they take snapshots of the reads,
        // and that should happen before the reads of this plan are processed.
        acc => {
          val nodeFilterExpressionsSnapshot = acc.reads.nodeFilterExpressions
          val possibleNodeDeleteConflictPlanSnapshot = acc.reads.possibleNodeDeleteConflictPlans
          acc.withWrites(acc.writes.includePlanWrites(
            plan,
            planWrites,
            nodeFilterExpressionsSnapshot,
            possibleNodeDeleteConflictPlanSnapshot
          ))
        },
        acc => acc.withReads(acc.reads.includePlanReads(plan, planReads))
      ))(acc)
    }

    LogicalPlans.foldPlan(ReadsAndWrites())(
      wholePlan,
      (acc, plan) => processPlan(acc, plan),
      (lhsAcc, rhsAcc, plan) =>
        plan match {
          case _: ApplyPlan =>
            // The RHS was initialized with the LHS in LogicalPlans.foldPlan,
            // but lhsAcc filterExpressions still need to be merged into rhsAcc by some rules defined FilterExpressions.
            val acc = rhsAcc.mergeNodeFilterExpressions(lhsAcc, plan)
            processPlan(acc, plan)
          case _ =>
            // We need to merge LHS and RHS
            val acc = lhsAcc ++ (rhsAcc, plan)
            processPlan(acc, plan)
        }
    )
  }
}
