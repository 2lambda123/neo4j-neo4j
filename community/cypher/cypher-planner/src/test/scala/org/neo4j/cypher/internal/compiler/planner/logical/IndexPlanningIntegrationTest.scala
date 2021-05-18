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
package org.neo4j.cypher.internal.compiler.planner.logical

import org.neo4j.cypher.internal.ast.AstConstructionTestSupport
import org.neo4j.cypher.internal.compiler.planner.LogicalPlanningIntegrationTestSupport
import org.neo4j.cypher.internal.compiler.planner.StatisticsBackedLogicalPlanningConfiguration
import org.neo4j.cypher.internal.logical.plans.GetValue
import org.neo4j.cypher.internal.util.symbols.CTAny
import org.neo4j.cypher.internal.util.test_helpers.CypherFunSuite

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class IndexPlanningIntegrationTest extends CypherFunSuite with LogicalPlanningIntegrationTestSupport with AstConstructionTestSupport {

  private def plannerConfigForIndexOnLabelPropTests(): StatisticsBackedLogicalPlanningConfiguration =
    plannerBuilder()
      .setAllNodesCardinality(100)
      .setLabelCardinality("Label", 100)
      .setRelationshipCardinality("()-[]->(:Label)", 50)
      .setRelationshipCardinality("(:Label)-[]->()", 50)
      .setRelationshipCardinality("()-[]->()", 50)
      .addNodeIndex("Label", Seq("prop"), existsSelectivity = 1.0, uniqueSelectivity = 0.1)
      .build()

  test("should not plan index usage if predicate depends on variable from same QueryGraph") {
    val cfg = plannerConfigForIndexOnLabelPropTests()

    for (op <- List("=", "<", "<=", ">", ">=", "STARTS WITH", "ENDS WITH", "CONTAINS")) {
      val plan = cfg.plan(s"MATCH (a)-[r]->(b:Label) WHERE b.prop $op a.prop RETURN a").stripProduceResults

      val planWithLabelScan = cfg.subPlanBuilder()
        .filter(s"b.prop $op a.prop")
        .expandAll("(b)<-[r]-(a)")
        .nodeByLabelScan("b", "Label")
        .build()

      val planWithIndexScan = cfg.subPlanBuilder()
        .filter(s"b.prop $op a.prop")
        .expandAll("(b)<-[r]-(a)")
        .nodeIndexOperator("b:Label(prop)")
        .build()

      plan should (be(planWithIndexScan) or be(planWithLabelScan))
    }
  }

  test("should plan index usage if predicate depends on simple variable from horizon") {
    val cfg = plannerConfigForIndexOnLabelPropTests()

    for (op <- List("=", "<", "<=", ">", ">=", "STARTS WITH", "ENDS WITH", "CONTAINS")) {
      val plan = cfg.plan(s"WITH 'foo' AS foo MATCH (a)-[r]->(b:Label) WHERE b.prop $op foo RETURN a").stripProduceResults
      plan shouldEqual cfg.subPlanBuilder()
        .expandAll("(b)<-[r]-(a)")
        .apply()
        .|.nodeIndexOperator(s"b:Label(prop $op ???)", paramExpr = Some(varFor("foo")), argumentIds = Set("foo"))
        .projection("'foo' AS foo")
        .argument()
        .build()
    }
  }

  test("should plan index usage if predicate depends on property of variable from horizon") {
    val cfg = plannerConfigForIndexOnLabelPropTests()

    for (op <- List("=", "<", "<=", ">", ">=", "STARTS WITH", "ENDS WITH", "CONTAINS")) {
      val plan = cfg.plan(s"WITH {prop: 'foo'} AS foo MATCH (a)-[r]->(b:Label) WHERE b.prop $op foo.prop RETURN a").stripProduceResults
      plan shouldEqual cfg.subPlanBuilder()
        .expandAll("(b)<-[r]-(a)")
        .apply()
        .|.nodeIndexOperator(s"b:Label(prop $op ???)", paramExpr = Some(prop("foo", "prop")) , argumentIds = Set("foo"))
        .projection("{prop: 'foo'} AS foo")
        .argument()
        .build()
    }
  }

  private def plannerConfigForDistancePredicateTests(): StatisticsBackedLogicalPlanningConfiguration =
    plannerBuilder()
      .setAllNodesCardinality(100)
      .setLabelCardinality("Place", 50)
      .setLabelCardinality("Preference", 50)
      .addNodeIndex("Place", Seq("location"), existsSelectivity = 1.0, uniqueSelectivity = 0.1)
      .setRelationshipCardinality("(:Place)-[]->()", 20)
      .setRelationshipCardinality("(:Place)-[]->(:Preference)", 20)
      .setRelationshipCardinality("()-[]->(:Preference)", 20)
      .build()

  test("should not plan index usage if distance predicate depends on variable from same QueryGraph") {
    val query =
      """MATCH (p:Place)-[r]->(x:Preference)
        |WHERE distance(p.location, point({x: 0, y: 0, crs: 'cartesian'})) <= x.maxDistance
        |RETURN p.location as point
        """.stripMargin

    val cfg = plannerConfigForDistancePredicateTests()
    val plan = cfg.plan(query).stripProduceResults
    plan shouldEqual cfg.subPlanBuilder()
      .projection("cacheN[p.location] AS point")
      .filter("x.maxDistance >= distance(cacheNFromStore[p.location], point({x: 0, y: 0, crs: 'cartesian'}))", "x:Preference")
      .expandAll("(p)-[r]->(x)")
      .nodeByLabelScan("p", "Place")
      .build()
  }

  test("should plan index usage if distance predicate depends on variable from the horizon") {
    val query =
      """WITH 10 AS maxDistance
        |MATCH (p:Place)
        |WHERE distance(p.location, point({x: 0, y: 0, crs: 'cartesian'})) <= maxDistance
        |RETURN p.location as point
        """.stripMargin

    val cfg = plannerConfigForDistancePredicateTests()
    val plan = cfg.plan(query).stripProduceResults
    plan shouldEqual cfg.subPlanBuilder()
      .projection("cacheN[p.location] AS point")
      .filter("distance(cacheNFromStore[p.location], point({x: 0, y: 0, crs: 'cartesian'})) <= maxDistance")
      .apply()
      .|.pointDistanceNodeIndexSeekExpr("p", "Place", "location", "{x: 0, y: 0, crs: 'cartesian'}", distanceExpr = varFor("maxDistance"), argumentIds = Set("maxDistance"), inclusive = true)
      .projection("10 AS maxDistance")
      .argument()
      .build()
  }

  test("should plan index usage if distance predicate depends on property read of variable from the horizon") {
    val query =
      """WITH {maxDistance: 10} AS x
        |MATCH (p:Place)
        |WHERE distance(p.location, point({x: 0, y: 0, crs: 'cartesian'})) <= x.maxDistance
        |RETURN p.location as point
        """.stripMargin

    val cfg = plannerConfigForDistancePredicateTests()
    val plan = cfg.plan(query).stripProduceResults
    plan shouldEqual cfg.subPlanBuilder()
      .projection("cacheN[p.location] AS point")
      .filter("x.maxDistance >= distance(cacheNFromStore[p.location], point({x: 0, y: 0, crs: 'cartesian'}))")
      .apply()
      .|.pointDistanceNodeIndexSeekExpr("p", "Place", "location", "{x: 0, y: 0, crs: 'cartesian'}", distanceExpr = prop("x", "maxDistance"), argumentIds = Set("x"), inclusive = true)
      .projection("{maxDistance: 10} AS x")
      .argument()
      .build()
  }

  private def plannerConfigForUsingHintTests(): StatisticsBackedLogicalPlanningConfiguration =
    plannerBuilder()
      .setAllNodesCardinality(1000)
      .setLabelCardinality("S", 500)
      .setLabelCardinality("T", 500)
      .setRelationshipCardinality("()-[]->(:S)", 1000)
      .setRelationshipCardinality("(:T)-[]->(:S)", 1000)
      .setRelationshipCardinality("(:T)-[]->()", 1000)
      .addNodeIndex("S", Seq("p"), existsSelectivity = 1.0, uniqueSelectivity = 0.1)
      .addNodeIndex("T", Seq("p"), existsSelectivity = 1.0, uniqueSelectivity = 0.1) // This index is enforced by hint
      .addNodeIndex("T", Seq("foo"), existsSelectivity = 1.0, uniqueSelectivity = 0.1) // This index would normally be preferred
      .build()

  test("should allow one join and one index hint on the same variable") {
    val query =
      """MATCH (s:S {p: 10})<-[r]-(t:T {foo: 2})
        |USING JOIN ON t
        |USING INDEX t:T(p)
        |WHERE 0 <= t.p <= 10
        |RETURN s, r, t
        """.stripMargin

    val cfg = plannerConfigForUsingHintTests()
    val plan = cfg.plan(query)

    // t:T(p) is enforced by hint
    // t:T(foo) would normally be preferred
    plan shouldEqual cfg.planBuilder()
      .produceResults("s", "r", "t")
      .nodeHashJoin("t")
      .|.expandAll("(s)<-[r]-(t)")
      .|.nodeIndexOperator("s:S(p = 10)")
      .filter("t.foo = 2")
      .nodeIndexOperator("t:T(0 <= p <= 10)")
      .build()
  }

  test("should allow one join and one scan hint on the same variable") {
    val query =
      """MATCH (s:S {p: 10})<-[r]-(t:T {foo: 2})
        |USING JOIN ON t
        |USING SCAN t:T
        |RETURN s, r, t
        """.stripMargin

    val cfg = plannerConfigForUsingHintTests()
    val plan = cfg.plan(query)

    // t:T(foo) would normally be preferred
    plan shouldEqual cfg.planBuilder()
      .produceResults("s", "r", "t")
      .nodeHashJoin("t")
      .|.expandAll("(s)<-[r]-(t)")
      .|.nodeIndexOperator("s:S(p = 10)")
      .filter("t.foo = 2")
      .nodeByLabelScan("t", "T")
      .build()
  }

  test("should or-leaf-plan in reasonable time") {
    import scala.concurrent.ExecutionContext.Implicits.global

    val cfg = plannerBuilder()
      .setAllNodesCardinality(100)
      .setLabelCardinality("Coleslaw", 100)
      .addNodeIndex("Coleslaw", Seq("name"), existsSelectivity = 1.0, uniqueSelectivity = 1.0, isUnique = true)
      .build()

    val futurePlan =
      Future(
        cfg.plan {
          """
            |MATCH (n:Coleslaw) USING INDEX n:Coleslaw(name)
            |WHERE (n.age < 10 AND ( n.name IN $p0 OR
            |        n.name IN $p1 OR
            |        n.name IN $p2 OR
            |        n.name IN $p3 OR
            |        n.name IN $p4 OR
            |        n.name IN $p5 OR
            |        n.name IN $p6 OR
            |        n.name IN $p7 OR
            |        n.name IN $p8 OR
            |        n.name IN $p9 OR
            |        n.name IN $p10 OR
            |        n.name IN $p11 OR
            |        n.name IN $p12 OR
            |        n.name IN $p13 OR
            |        n.name IN $p14 OR
            |        n.name IN $p15 OR
            |        n.name IN $p16 OR
            |        n.name IN $p17 OR
            |        n.name IN $p18 OR
            |        n.name IN $p19 OR
            |        n.name IN $p20 OR
            |        n.name IN $p21 OR
            |        n.name IN $p22 OR
            |        n.name IN $p23 OR
            |        n.name IN $p24 OR
            |        n.name IN $p25) AND n.legal)
            |RETURN n.name as name
        """.stripMargin
        })

    Await.result(futurePlan, 1.minutes)
  }

  test("should not plan index scan if predicate variable is an argument") {
    val query =
      """
        |MATCH (a: Label {prop: $param})
        |MATCH (b)
        |WHERE (a:Label {prop: $param})-[]-(b)
        |RETURN a
        |""".stripMargin

    val cfg = plannerConfigForIndexOnLabelPropTests()
    val plan = cfg.plan(query).stripProduceResults

    plan shouldEqual cfg.subPlanBuilder()
      .semiApply()
      .|.expandInto("(a)-[anon_73]-(b)")
      .|.filter("cacheN[a.prop] = $param", "a:Label")
      .|.argument("a", "b")
      .cartesianProduct()
      .|.allNodeScan("b")
      .nodeIndexOperator("a:Label(prop = ???)", paramExpr = Some(parameter("param", CTAny)), getValue = _ => GetValue)
      .build()
  }

  test("should prefer label scan to node index scan from existence constraint with same cardinality") {

    val planner = plannerBuilder()
      .setAllNodesCardinality(1000)
      .setLabelCardinality("Label", 1000)
      .addNodeIndex("Label", Seq("prop"), 1.0, 1.0)
      .addNodeExistenceConstraint("Label", "prop")
      .build()

    val plan = planner.plan(s"MATCH (n:Label) RETURN n")

    plan shouldEqual planner.planBuilder()
                            .produceResults("n")
                            .nodeByLabelScan("n", "Label")
                            .build()
  }

  test("should prefer label scan to node index scan from existence constraint with same cardinality, when filtered") {

    val planner = plannerBuilder()
      .setAllNodesCardinality(1000)
      .setLabelCardinality("Label", 1000)
      .addNodeIndex("Label", Seq("prop"), 1.0, 1.0)
      .addNodeExistenceConstraint("Label", "prop")
      .build()

    val plan = planner.plan(s"MATCH (n:Label) WHERE n.x = 1 RETURN n")

    plan shouldEqual planner.planBuilder()
                            .produceResults("n")
                            .filter("n.x = 1")
                            .nodeByLabelScan("n", "Label")
                            .build()
  }

  test("should prefer type scan to relationship index scan from existence constraint with same cardinality") {

    val planner = plannerBuilder()
      .setAllNodesCardinality(1000)
      .setRelationshipCardinality("()-[:REL]-()", 10)
      .addRelationshipIndex("REL", Seq("prop"), 1.0, 1.0)
      .addRelationshipExistenceConstraint("REL", "prop")
      .enablePlanningRelationshipIndexes()
      .enableRelationshipByTypeLookup()
      .build()

    val plan = planner.plan(s"MATCH (a)-[r:REL]->(b) RETURN r")

    plan shouldEqual planner.planBuilder()
                            .produceResults("r")
                            .relationshipTypeScan("(a)-[r:REL]->(b)")
                            .build()
  }

  test("should prefer node index scan from existence constraint to label scan with same cardinality, if indexed property is used") {

    val planner = plannerBuilder()
      .setAllNodesCardinality(1000)
      .setLabelCardinality("Label", 1000)
      .addNodeIndex("Label", Seq("prop"), 1.0, 1.0)
      .addNodeExistenceConstraint("Label", "prop")
      .build()

    val plan = planner.plan(s"MATCH (n:Label) RETURN n.prop AS p")

    plan shouldEqual planner.planBuilder()
                            .produceResults("p")
                            .projection("n.prop AS p")
                            .nodeIndexOperator("n:Label(prop)")
                            .build()
  }

  test("should prefer relationship index scan from existence constraint to type scan with same cardinality, if indexed property is used") {

    val planner = plannerBuilder()
      .setAllNodesCardinality(1000)
      .setRelationshipCardinality("()-[:REL]-()", 10)
      .addRelationshipIndex("REL", Seq("prop"), 1.0, 1.0)
      .addRelationshipExistenceConstraint("REL", "prop")
      .enablePlanningRelationshipIndexes()
      .enableRelationshipByTypeLookup()
      .build()

    val plan = planner.plan(s"MATCH (a)-[r:REL]->(b) RETURN r.prop AS p")

    plan shouldEqual planner.planBuilder()
                            .produceResults("p")
                            .projection("r.prop AS p")
                            .relationshipIndexOperator("(a)-[r:REL(prop)]->(b)")
                            .build()
  }

  test("should prefer node index scan from existence constraint to label scan with same cardinality, if indexed property is used, when filtered") {

    val planner = plannerBuilder()
      .setAllNodesCardinality(1000)
      .setLabelCardinality("Label", 1000)
      .addNodeIndex("Label", Seq("prop"), 1.0, 1.0)
      .addNodeExistenceConstraint("Label", "prop")
      .build()

    val plan = planner.plan(s"MATCH (n:Label) WHERE n.x = 1 RETURN n.prop AS p")

    plan shouldEqual planner.planBuilder()
                            .produceResults("p")
                            .projection("n.prop AS p")
                            .filter("n.x = 1")
                            .nodeIndexOperator("n:Label(prop)")
                            .build()
  }

  test("should prefer relationship index scan from existence constraint to type scan with same cardinality, if indexed property is used, when filtered") {

    val planner = plannerBuilder()
      .setAllNodesCardinality(1000)
      .setRelationshipCardinality("()-[:REL]-()", 10)
      .addRelationshipIndex("REL", Seq("prop"), 1.0, 1.0)
      .addRelationshipExistenceConstraint("REL", "prop")
      .enablePlanningRelationshipIndexes()
      .enableRelationshipByTypeLookup()
      .build()

    val plan = planner.plan(s"MATCH (a)-[r:REL]->(b) WHERE r.x = 1 RETURN r.prop AS p")

    plan shouldEqual planner.planBuilder()
                            .produceResults("p")
                            .projection("r.prop AS p")
                            .filter("r.x = 1")
                            .relationshipIndexOperator("(a)-[r:REL(prop)]->(b)")
                            .build()
  }

  test("should prefer node index scan from aggregation to node index scan from existence constraint with same cardinality") {

    val planner = plannerBuilder()
      .setAllNodesCardinality(1000)
      .setLabelCardinality("Label", 1000)
      .addNodeIndex("Label", Seq("prop"), 1.0, 1.0)
      .addNodeIndex("Label", Seq("counted"), 1.0, 1.0)
      .addNodeExistenceConstraint("Label", "prop")
      .build()

    val plan = planner.plan(s"MATCH (n:Label) RETURN count(n.counted) AS c")

    plan shouldEqual planner.planBuilder()
                            .produceResults("c")
                            .aggregation(Seq(), Seq("count(n.counted) AS c"))
                            .nodeIndexOperator("n:Label(counted)")
                            .build()
  }

  test("should prefer relationship index scan from aggregation to relationship index scan from existence constraint with same cardinality") {

    val planner = plannerBuilder()
      .setAllNodesCardinality(1000)
      .setRelationshipCardinality("()-[:REL]-()", 10)
      .addRelationshipIndex("REL", Seq("prop"), 1.0, 1.0)
      .addRelationshipIndex("REL", Seq("counted"), 1.0, 1.0)
      .addRelationshipExistenceConstraint("REL", "prop")
      .enablePlanningRelationshipIndexes()
      .enableRelationshipByTypeLookup()
      .build()

    val plan = planner.plan(s"MATCH (a)-[r:REL]->(b) RETURN count(r.counted) AS c")

    plan shouldEqual planner.planBuilder()
                            .produceResults("c")
                            .aggregation(Seq(), Seq("count(r.counted) AS c"))
                            .relationshipIndexOperator("(a)-[r:REL(counted)]->(b)")
                            .build()
  }

  test("should prefer node index scan from aggregation to node index scan from existence constraint with same cardinality, when filtered") {

    val planner = plannerBuilder()
      .setAllNodesCardinality(1000)
      .setLabelCardinality("Label", 1000)
      .addNodeIndex("Label", Seq("prop"), 1.0, 1.0)
      .addNodeIndex("Label", Seq("counted"), 1.0, 1.0)
      .addNodeExistenceConstraint("Label", "prop")
      .build()

    val plan = planner.plan(s"MATCH (n:Label) WHERE n.x = 1 RETURN count(n.counted) AS c")

    plan shouldEqual planner.planBuilder()
                            .produceResults("c")
                            .aggregation(Seq(), Seq("count(n.counted) AS c"))
                            .filter("n.x = 1")
                            .nodeIndexOperator("n:Label(counted)")
                            .build()
  }

  test("should prefer relationship index scan from aggregation to relationship index scan from existence constraint with same cardinality, when filtered") {

    val planner = plannerBuilder()
      .setAllNodesCardinality(1000)
      .setRelationshipCardinality("()-[:REL]-()", 10)
      .addRelationshipIndex("REL", Seq("prop"), 1.0, 1.0)
      .addRelationshipIndex("REL", Seq("counted"), 1.0, 1.0)
      .addRelationshipExistenceConstraint("REL", "prop")
      .enablePlanningRelationshipIndexes()
      .enableRelationshipByTypeLookup()
      .build()

    val plan = planner.plan(s"MATCH (a)-[r:REL]->(b) WHERE r.x = 1 RETURN count(r.counted) AS c")

    plan shouldEqual planner.planBuilder()
                            .produceResults("c")
                            .aggregation(Seq(), Seq("count(r.counted) AS c"))
                            .filter("r.x = 1")
                            .relationshipIndexOperator("(a)-[r:REL(counted)]->(b)")
                            .build()
  }

  test("should prefer node index scan for aggregated property, even if other property is referenced") {

    val planner = plannerBuilder()
      .setAllNodesCardinality(1000)
      .setLabelCardinality("Label", 1000)
      .addNodeIndex("Label", Seq("prop"), 1.0, 1.0)
      .addNodeIndex("Label", Seq("counted"), 1.0, 1.0)
      .addNodeExistenceConstraint("Label", "prop")
      .build()

    val plan = planner.plan(s"MATCH (n:Label) WHERE n.prop <> 1 AND n.x = 1 RETURN count(n.counted) AS c")

    plan shouldEqual planner.planBuilder()
                            .produceResults("c")
                            .aggregation(Seq(), Seq("count(n.counted) AS c"))
                            .filter("not n.prop = 1", "n.x = 1")
                            .nodeIndexOperator("n:Label(counted)")
                            .build()
  }

  test("should prefer relationship index scan for aggregated property, even if other property is referenced") {

    val planner = plannerBuilder()
      .setAllNodesCardinality(1000)
      .setRelationshipCardinality("()-[:REL]-()", 10)
      .addRelationshipIndex("REL", Seq("prop"), 1.0, 1.0)
      .addRelationshipIndex("REL", Seq("counted"), 1.0, 1.0)
      .addRelationshipExistenceConstraint("REL", "prop")
      .enablePlanningRelationshipIndexes()
      .enableRelationshipByTypeLookup()
      .build()

    val plan = planner.plan(s"MATCH (a)-[r:REL]->(b) WHERE r.prop <> 1 AND r.x = 1 RETURN count(r.counted) AS c")

    plan shouldEqual planner.planBuilder()
                            .produceResults("c")
                            .aggregation(Seq(), Seq("count(r.counted) AS c"))
                            .filter("not r.prop = 1", "r.x = 1")
                            .relationshipIndexOperator("(a)-[r:REL(counted)]->(b)")
                            .build()
  }

}
