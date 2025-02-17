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
package org.neo4j.cypher.internal.logical.plans

/**
 * A plan that limits selectivity on child plans.
 */
trait LimitingLogicalPlan extends LogicalUnaryPlan

/**
 * A plan that eventually exhausts all input from LHS.
 */
trait ExhaustiveLogicalPlan extends LogicalPlan

/**
 * A plan that exhausts all input from LHS before producing it's first output.
 */
trait EagerLogicalPlan extends ExhaustiveLogicalPlan

/**
 * A plan that consumes only a single row from RHS for every row in LHS
 */
trait SingleFromRightLogicalPlan extends LogicalBinaryPlan {
  final def source: LogicalPlan = left
  final def inner: LogicalPlan = right
}

/**
 * A leaf plan that is unaffected by changes the transaction state after yielding the first row.
 */
trait StableLeafPlan extends LogicalLeafPlan
