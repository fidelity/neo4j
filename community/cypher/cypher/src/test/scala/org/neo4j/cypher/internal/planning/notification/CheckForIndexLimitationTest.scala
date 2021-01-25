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
package org.neo4j.cypher.internal.planning.notification

import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.neo4j.cypher.internal.compiler.planner.LogicalPlanningTestSupport
import org.neo4j.cypher.internal.compiler.{SuboptimalIndexForConstainsQueryNotification, SuboptimalIndexForEndsWithQueryNotification}
import org.neo4j.cypher.internal.planner.spi
import org.neo4j.cypher.internal.planner.spi.{IndexDescriptor, IndexLimitation, PlanContext, SlowContains}
import org.neo4j.cypher.internal.logical.plans.IndexSeek
import org.neo4j.cypher.internal.v4_0.util.test_helpers.CypherFunSuite
import org.neo4j.cypher.internal.v4_0.util.{LabelId, PropertyKeyId}

class CheckForIndexLimitationTest extends CypherFunSuite with LogicalPlanningTestSupport {

  test("should notify for NodeIndexContainsScan backed by limited index") {
    val planContext = mock[PlanContext]
    when(planContext.indexGetForLabelAndProperties(anyString(), any())).thenReturn(Some(spi.IndexDescriptor(LabelId(1), Seq(PropertyKeyId(1)), Set[IndexLimitation](SlowContains))))
    val plan = IndexSeek("id:label(prop CONTAINS 'tron')")

    checkForIndexLimitation(planContext)(plan) should equal(Set(SuboptimalIndexForConstainsQueryNotification("label", Seq("prop"))))
  }

  test("should notify for NodeIndexEndsWithScan backed by limited index") {
    val planContext = mock[PlanContext]
    when(planContext.indexGetForLabelAndProperties(anyString(), any())).thenReturn(Some(IndexDescriptor(LabelId(1), Seq(PropertyKeyId(1)), Set[IndexLimitation](SlowContains))))
    val plan = IndexSeek("id:label(prop ENDS WITH 'tron')")

    checkForIndexLimitation(planContext)(plan) should equal(Set(SuboptimalIndexForEndsWithQueryNotification("label", Seq("prop"))))
  }

  test("should not notify for NodeIndexContainsScan backed by index with no limitations") {
    val planContext = mock[PlanContext]
    when(planContext.indexGetForLabelAndProperties(anyString(), any())).thenReturn(Some(IndexDescriptor(LabelId(1), Seq(PropertyKeyId(1)), Set.empty[IndexLimitation])))
    val plan = IndexSeek("id:label(prop CONTAINS 'tron')")

    checkForIndexLimitation(planContext)(plan) should be(empty)
  }

  test("should not notify for NodeIndexEndsWithScan backed by index with no limitations") {
    val planContext = mock[PlanContext]
    when(planContext.indexGetForLabelAndProperties(anyString(), any())).thenReturn(Some(IndexDescriptor(LabelId(1), Seq(PropertyKeyId(1)), Set.empty[IndexLimitation])))
    val plan = IndexSeek("id:label(prop ENDS WITH 'tron')")

    checkForIndexLimitation(planContext)(plan) should be(empty)
  }

}
