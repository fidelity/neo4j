/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.neo4j.cypher.internal.v4_0.ast

import org.neo4j.cypher.internal.v4_0.expressions.FunctionName
import org.neo4j.cypher.internal.v4_0.util.test_helpers.CypherFunSuite

class FunctionNameTest extends CypherFunSuite {
  test("equality should ignore case") {
    FunctionName("foo")(null) should equal(FunctionName("FOO")(null))
  }
  test("equality should respect the name") {
    FunctionName("foo")(null) should not equal FunctionName("FOOB")(null)
  }
}
