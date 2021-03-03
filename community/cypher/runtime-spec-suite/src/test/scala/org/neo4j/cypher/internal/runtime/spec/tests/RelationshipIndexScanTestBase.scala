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
package org.neo4j.cypher.internal.runtime.spec.tests

import org.neo4j.cypher.internal.CypherRuntime
import org.neo4j.cypher.internal.RuntimeContext
import org.neo4j.cypher.internal.logical.plans.GetValue
import org.neo4j.cypher.internal.runtime.spec.Edition
import org.neo4j.cypher.internal.runtime.spec.LogicalQueryBuilder
import org.neo4j.cypher.internal.runtime.spec.RuntimeTestSuite

abstract class RelationshipIndexScanTestBase[CONTEXT <: RuntimeContext](
                                                             edition: Edition[CONTEXT],
                                                             runtime: CypherRuntime[CONTEXT],
                                                             sizeHint: Int
                                                           ) extends RuntimeTestSuite[CONTEXT](edition, runtime) {

  test("should directed scan all relationships of an index with a property") {
    val rels = given {
      relationshipIndex("R", "prop")
      circleGraph(5)//these doesn't have prop
      val (_, rels) = circleGraph(sizeHint)
      rels.zipWithIndex.foreach {
        case (r, i) if i % 10 == 0 => r.setProperty("prop", i)
        case _ =>
      }
      rels
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "r", "y")
      .relationshipIndexOperator("(x)-[r:R(prop)]->(y)")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val expected = rels.filter{ _.hasProperty("prop") }.map(r => Array(r.getStartNode, r, r.getEndNode))
    runtimeResult should beColumns("x", "r", "y").withRows(expected)
  }

  test("should undirected scan all relationships of an index with a property") {
    val rels = given {
      relationshipIndex("R", "prop")
      circleGraph(5)//these doesn't have prop
      val (_, rels) = circleGraph(sizeHint)
      rels.zipWithIndex.foreach {
        case (r, i) if i % 10 == 0 => r.setProperty("prop", i)
        case _ =>
      }
      rels
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "r", "y")
      .relationshipIndexOperator("(x)-[r:R(prop)]-(y)")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val expected =
      rels
        .filter{ _.hasProperty("prop") }
        .flatMap(r => Seq(Array(r.getStartNode, r, r.getEndNode), Array(r.getEndNode, r, r.getStartNode)))
    runtimeResult should beColumns("x", "r", "y").withRows(expected)
  }

  test("should directed scan all relationship of an index with multiple properties") {
    val rels = given {
      relationshipIndex("R", "prop1", "prop2")
      circleGraph(5)//these doesn't have prop
      val (_, rels) = circleGraph(sizeHint)
      rels.zipWithIndex.foreach {
        case (r, i) if i % 10 == 0 =>
          r.setProperty("prop1", i)
          r.setProperty("prop2", i)
        case (r, i) if i % 5 == 0 =>
          r.setProperty("prop1", i)
        case _ =>
      }
      rels
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "r", "y")
      .relationshipIndexOperator("(x)-[r:R(prop1,prop2)]->(y)")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val expected =
      rels
        .filter { r => r.hasProperty("prop1") && r.hasProperty("prop2") }
        .map(r => Array(r.getStartNode, r, r.getEndNode))

    runtimeResult should beColumns("x", "r", "y").withRows(expected)
  }

  test("should undirected scan all relationship of an index with multiple properties") {
    val rels = given {
      relationshipIndex("R", "prop1", "prop2")
      circleGraph(5)//these doesn't have prop
      val (_, rels) = circleGraph(sizeHint)
      rels.zipWithIndex.foreach {
        case (r, i) if i % 10 == 0 =>
          r.setProperty("prop1", i)
          r.setProperty("prop2", i)
        case (r, i) if i % 5 == 0 =>
          r.setProperty("prop1", i)
        case _ =>
      }
      rels
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "r", "y")
      .relationshipIndexOperator("(x)-[r:R(prop1,prop2)]-(y)")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val expected =
      rels
        .filter { r => r.hasProperty("prop1") && r.hasProperty("prop2") }
        .flatMap(r =>
          Seq(
            Array(r.getStartNode, r, r.getEndNode),
            Array(r.getEndNode, r, r.getStartNode)
          )
        )

    runtimeResult should beColumns("x", "r", "y").withRows(expected)
  }

  test("should cache properties in directed scan") {
    val rels = given {
      relationshipIndex("R", "prop")
      circleGraph(5)//these doesn't have prop
      val (_, rels) = circleGraph(sizeHint)
      rels.zipWithIndex.foreach {
        case (r, i) if i % 10 == 0 =>
          r.setProperty("prop", i)
        case _ =>
      }
      rels
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("r", "foo")
      .projection("cacheR[r.prop] AS foo")
      .relationshipIndexOperator("(x)-[r:R(prop)]->(y)", GetValue)
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val expected = rels.zipWithIndex.collect {
      case (r, i) if r.hasProperty("prop") => Array(r, i)
    }
    runtimeResult should beColumns("r", "foo").withRows(expected)
  }

  test("should cache properties in undirected scan") {
    val rels = given {
      relationshipIndex("R", "prop")
      circleGraph(5)//these doesn't have prop
      val (_, rels) = circleGraph(sizeHint)
      rels.zipWithIndex.foreach {
        case (r, i) if i % 10 == 0 =>
          r.setProperty("prop", i)
        case _ =>
      }
      rels
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("r", "foo")
      .projection("cacheR[r.prop] AS foo")
      .relationshipIndexOperator("(x)-[r:R(prop)]-(y)", GetValue)
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val expected = rels.zipWithIndex.flatMap{
      case (r, i) if r.hasProperty("prop") => Seq.fill(2)(Array(r, i))
      case _ => Seq.empty
    }
    runtimeResult should beColumns("r", "foo").withRows(expected)
  }

  test("should handle directed scan on the RHS of an Apply") {
    val rels = given {
      relationshipIndex("R", "prop")
      circleGraph(5)//these doesn't have prop
      val (_, rels) = circleGraph(sizeHint)
      rels.zipWithIndex.foreach {
        case (r, i) if i % 10 == 0 => r.setProperty("prop", i)
        case _ =>
      }
      rels
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "r", "y")
      .apply()
      .|.relationshipIndexOperator("(x)-[r:R(prop)]->(y)")
      .input(variables = Seq("i"))
      .build()

    val input = (1 to 10).map(i => Array[Any](i))
    val runtimeResult = execute(logicalQuery, runtime, inputValues(input:_*))

    // then
    val expected = for {_ <- 1 to 10
                        r <- rels.filter(_.hasProperty("prop"))} yield Array(r.getStartNode, r, r.getEndNode)

    runtimeResult should beColumns("x", "r", "y").withRows(expected)
  }

  test("should handle undirected scan on the RHS of an Apply") {
    val rels = given {
      relationshipIndex("R", "prop")
      circleGraph(5)//these doesn't have prop
      val (_, rels) = circleGraph(sizeHint)
      rels.zipWithIndex.foreach {
        case (r, i) if i % 10 == 0 => r.setProperty("prop", i)
        case _ =>
      }
      rels
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "r", "y")
      .apply()
      .|.relationshipIndexOperator("(x)-[r:R(prop)]-(y)")
      .input(variables = Seq("i"))
      .build()

    val input = (1 to 10).map(i => Array[Any](i))
    val runtimeResult = execute(logicalQuery, runtime, inputValues(input:_*))

    // then
    val expected = for {_ <- 1 to 10
                        r <- rels.filter(_.hasProperty("prop"))} yield Seq(Array(r.getStartNode, r, r.getEndNode), Array(r.getEndNode, r, r.getStartNode))

    runtimeResult should beColumns("x", "r", "y").withRows(expected.flatten)
  }

  test("should handle directed scan and cartesian product") {
    val size = Math.sqrt(sizeHint).intValue()
    val rels = given {
      relationshipIndex("R", "prop")
      circleGraph(5)//these doesn't have prop
      val (_, rels) = circleGraph(size)
      rels.zipWithIndex.foreach {
        case (r, i) if i % 10 == 0 => r.setProperty("prop", i)
        case _ =>
      }
      rels
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("r1", "r2")
      .cartesianProduct()
      .|.relationshipIndexOperator("(x2)-[r2:R(prop)]->(y2)")
      .relationshipIndexOperator("(x1)-[r1:R(prop)]->(y1)")
      .build()
    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val expected = for {r1 <- rels.filter(_.hasProperty("prop"))
                        r2 <- rels.filter(_.hasProperty("prop"))} yield Array(r1, r2)

    runtimeResult should beColumns("r1", "r2").withRows(expected)
  }

  test("should handle undirected scan and cartesian product") {
    val size = Math.sqrt(sizeHint).intValue()
    val rels = given {
      relationshipIndex("R", "prop")
      circleGraph(5)//these doesn't have prop
      val (_, rels) = circleGraph(size)
      rels.zipWithIndex.foreach {
        case (r, i) if i % 10 == 0 => r.setProperty("prop", i)
        case _ =>
      }
      rels
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("r1", "r2")
      .cartesianProduct()
      .|.relationshipIndexOperator("(x2)-[r2:R(prop)]-(y2)")
      .relationshipIndexOperator("(x1)-[r1:R(prop)]-(y1)")
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val expected = for {r1 <- rels.filter(_.hasProperty("prop"))
                        r2 <- rels.filter(_.hasProperty("prop"))} yield Seq.fill(4)(Array(r1, r2))
    runtimeResult should beColumns("r1", "r2").withRows(expected.flatten)
  }
}
