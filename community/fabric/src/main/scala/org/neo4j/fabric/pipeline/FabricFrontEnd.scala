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
package org.neo4j.fabric.pipeline

import org.neo4j.cypher.internal.NotificationWrapping
import org.neo4j.cypher.internal.PreParsedQuery
import org.neo4j.cypher.internal.PreParser
import org.neo4j.cypher.internal.QueryOptions
import org.neo4j.cypher.internal.ast.Statement
import org.neo4j.cypher.internal.ast.semantics.SemanticErrorDef
import org.neo4j.cypher.internal.ast.semantics.SemanticFeature.ExpressionsInViewInvocations
import org.neo4j.cypher.internal.ast.semantics.SemanticFeature.MultipleGraphs
import org.neo4j.cypher.internal.ast.semantics.SemanticFeature.UseGraphSelector
import org.neo4j.cypher.internal.cache.CaffeineCacheFactory
import org.neo4j.cypher.internal.compiler.Neo4jCypherExceptionFactory
import org.neo4j.cypher.internal.compiler.helpers.ParameterValueTypeHelper
import org.neo4j.cypher.internal.compiler.phases.Compatibility3_5
import org.neo4j.cypher.internal.compiler.phases.Compatibility4_2
import org.neo4j.cypher.internal.compiler.phases.Compatibility4_3
import org.neo4j.cypher.internal.compiler.phases.CompilationPhases
import org.neo4j.cypher.internal.config.CypherConfiguration
import org.neo4j.cypher.internal.frontend.phases.BaseContext
import org.neo4j.cypher.internal.frontend.phases.BaseState
import org.neo4j.cypher.internal.frontend.phases.CompilationPhaseTracer
import org.neo4j.cypher.internal.frontend.phases.InitialState
import org.neo4j.cypher.internal.frontend.phases.Monitors
import org.neo4j.cypher.internal.frontend.phases.Transformer
import org.neo4j.cypher.internal.options.CypherExecutionMode
import org.neo4j.cypher.internal.options.CypherVersion
import org.neo4j.cypher.internal.planner.spi.PlannerNameFor
import org.neo4j.cypher.internal.planner.spi.ProcedureSignatureResolver
import org.neo4j.cypher.internal.planning.WrappedMonitors
import org.neo4j.cypher.internal.tracing.CompilationTracer
import org.neo4j.cypher.internal.tracing.TimingCompilationTracer
import org.neo4j.cypher.internal.util.AnonymousVariableNameGenerator
import org.neo4j.cypher.internal.util.CypherExceptionFactory
import org.neo4j.cypher.internal.util.InternalNotificationLogger
import org.neo4j.cypher.internal.util.RecordingNotificationLogger
import org.neo4j.cypher.rendering.QueryRenderer
import org.neo4j.fabric.planning.FabricPlan
import org.neo4j.fabric.util.Errors
import org.neo4j.graphdb.Notification
import org.neo4j.monitoring
import org.neo4j.values.virtual.MapValue


case class FabricFrontEnd(
  cypherConfig: CypherConfiguration,
  kernelMonitors: monitoring.Monitors,
  signatures: ProcedureSignatureResolver,
  cacheFactory: CaffeineCacheFactory
) {

  val compilationTracer = new TimingCompilationTracer(
    kernelMonitors.newMonitor(classOf[TimingCompilationTracer.EventListener]))

  object preParsing {

    private val preParser = new PreParser(
      cypherConfig,
      cacheFactory
    )

    def executionType(options: QueryOptions, inFabricContext: Boolean): FabricPlan.ExecutionType = options.queryOptions.executionMode match {
      case CypherExecutionMode.default => FabricPlan.Execute
      case CypherExecutionMode.explain => FabricPlan.Explain
      case CypherExecutionMode.profile if inFabricContext => Errors.semantic("'PROFILE' not supported in Fabric context")
      case CypherExecutionMode.profile => FabricPlan.PROFILE
    }

    def preParse(queryString: String): PreParsedQuery = {
      preParser.preParseQuery(queryString)
    }

    def isPeriodicCommit(queryString: String): Boolean = {
      val preParsedQuery = preParser.preParseQuery(queryString)
      preParsedQuery.options.queryOptions.executionMode != CypherExecutionMode.explain && preParsedQuery.options.isPeriodicCommit
    }
  }

  case class Pipeline(
    query: PreParsedQuery,
    params: MapValue,
  ) {

    def traceStart(): CompilationTracer.QueryCompilationEvent =
      compilationTracer.compileQuery(query.description)

    private val context: BaseContext = new BaseContext {
      val monitors: Monitors = WrappedMonitors(kernelMonitors)
      val tracer: CompilationPhaseTracer = CompilationPhaseTracer.NO_TRACING
      val notificationLogger: InternalNotificationLogger = new RecordingNotificationLogger(Some(query.options.offset))
      val cypherExceptionFactory: CypherExceptionFactory = Neo4jCypherExceptionFactory(query.rawStatement, Some(query.options.offset))

      val errorHandler: Seq[SemanticErrorDef] => Unit = (errors: Seq[SemanticErrorDef]) =>
        errors.foreach(e => throw cypherExceptionFactory.syntaxException(e.msg, e.position))

      val anonymousVariableNameGenerator = new AnonymousVariableNameGenerator()
    }

    private val compatibilityMode =
      query.options.queryOptions.version match {
        case CypherVersion.v3_5 => Compatibility3_5
        case CypherVersion.v4_2 => Compatibility4_2
        case CypherVersion.v4_3 => Compatibility4_3
      }

    private val semanticFeatures =
      CompilationPhases.defaultSemanticFeatures ++ Seq(
        MultipleGraphs,
        UseGraphSelector,
        ExpressionsInViewInvocations
      )

    private val parsingConfig = CompilationPhases.ParsingConfig(
      compatibilityMode = compatibilityMode,
      parameterTypeMapping = ParameterValueTypeHelper.asCypherTypeMap(params),
      semanticFeatures = semanticFeatures,
      useJavaCCParser = cypherConfig.useJavaCCParser,
      obfuscateLiterals = cypherConfig.obfuscateLiterals
    )

    object parseAndPrepare {
      private val transformer =
        CompilationPhases.fabricParsing(parsingConfig, signatures)

      def process(): BaseState =
        transformer.transform(InitialState(query.statement, Some(query.options.offset), null), context)
    }

    object checkAndFinalize {
      private val transformer =
        CompilationPhases.fabricFinalize(parsingConfig)

      def process(statement: Statement): BaseState = {
        val localQueryString = QueryRenderer.render(statement)
        val plannerName = PlannerNameFor(query.options.queryOptions.planner.name)
        transformer.transform(InitialState(localQueryString, None, plannerName).withStatement(statement), context)
      }
    }

    def notifications: Seq[Notification] =
      context.notificationLogger.notifications
        .toSeq.map(NotificationWrapping.asKernelNotification(Some(query.options.offset)))
  }
}

abstract class TransformerChain(parts: Transformer[BaseContext, BaseState, BaseState]*) {
  val transformer: Transformer[BaseContext, BaseState, BaseState] = parts.reduce(_ andThen _)
}

