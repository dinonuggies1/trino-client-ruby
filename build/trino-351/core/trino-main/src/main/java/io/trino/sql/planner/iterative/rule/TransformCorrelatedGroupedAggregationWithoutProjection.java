/*
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
package io.trino.sql.planner.iterative.rule;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.trino.matching.Capture;
import io.trino.matching.Captures;
import io.trino.matching.Pattern;
import io.trino.metadata.Metadata;
import io.trino.sql.planner.Symbol;
import io.trino.sql.planner.iterative.Rule;
import io.trino.sql.planner.optimizations.PlanNodeDecorrelator;
import io.trino.sql.planner.plan.AggregationNode;
import io.trino.sql.planner.plan.AssignUniqueId;
import io.trino.sql.planner.plan.CorrelatedJoinNode;
import io.trino.sql.planner.plan.JoinNode;
import io.trino.sql.planner.plan.Patterns;
import io.trino.sql.planner.plan.PlanNode;

import java.util.Optional;

import static io.trino.matching.Capture.newCapture;
import static io.trino.matching.Pattern.nonEmpty;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.sql.planner.iterative.rule.AggregationDecorrelation.isDistinctOperator;
import static io.trino.sql.planner.iterative.rule.Util.restrictOutputs;
import static io.trino.sql.planner.plan.AggregationNode.singleGroupingSet;
import static io.trino.sql.planner.plan.CorrelatedJoinNode.Type.INNER;
import static io.trino.sql.planner.plan.Patterns.Aggregation.groupingColumns;
import static io.trino.sql.planner.plan.Patterns.CorrelatedJoin.filter;
import static io.trino.sql.planner.plan.Patterns.CorrelatedJoin.subquery;
import static io.trino.sql.planner.plan.Patterns.CorrelatedJoin.type;
import static io.trino.sql.planner.plan.Patterns.aggregation;
import static io.trino.sql.planner.plan.Patterns.correlatedJoin;
import static io.trino.sql.planner.plan.Patterns.source;
import static io.trino.sql.tree.BooleanLiteral.TRUE_LITERAL;
import static java.util.Objects.requireNonNull;

/**
 * This rule decorrelates a correlated subquery of INNER correlated join with:
 * - single grouped aggregation, or
 * - grouped aggregation over distinct operator (grouped aggregation with no aggregation assignments)
 * It is similar to TransformCorrelatedGroupedAggregationWithProjection rule, but does not support projection over aggregation in the subquery
 * <p>
 * In the case of single aggregation, it transforms:
 * <pre>
 * - CorrelatedJoin INNER (correlation: [c], filter: true, output: a, count, agg)
 *      - Input (a, c)
 *      - Aggregation (group by b)
 *        count <- count(*)
 *        agg <- agg(d)
 *           - Source (b, d) with correlated filter (b > c)
 * </pre>
 * Into:
 * <pre>
 * - Project (a <- a, count <- count, agg <- agg)
 *      - Aggregation (group by [a, c, unique, b])
 *        count <- count(*)
 *        agg <- agg(d)
 *           - INNER join (filter: b > c)
 *                - UniqueId (unique)
 *                     - Input (a, c)
 *                - Source (b, d) decorrelated
 * </pre>
 * <p>
 * In the case of grouped aggregation over distinct operator, it transforms:
 * <pre>
 * - CorrelatedJoin INNER (correlation: [c], filter: true, output: a, count, agg)
 *      - Input (a, c)
 *      - Aggregation (group by b)
 *        count <- count(*)
 *        agg <- agg(b)
 *           - Aggregation "distinct operator" group by [b]
 *                - Source (b) with correlated filter (b > c)
 * </pre>
 * Into:
 * <pre>
 * - Project (a <- a, count <- count, agg <- agg)
 *      - Aggregation (group by [a, c, unique, b])
 *        count <- count(*)
 *        agg <- agg(b)
 *           - Aggregation "distinct operator" group by [a, c, unique, b]
 *                - INNER join (filter: b > c)
 *                     - UniqueId (unique)
 *                          - Input (a, c)
 *                     - Source (b) decorrelated
 * </pre>
 */
public class TransformCorrelatedGroupedAggregationWithoutProjection
        implements Rule<CorrelatedJoinNode>
{
    private static final Capture<AggregationNode> AGGREGATION = newCapture();
    private static final Capture<PlanNode> SOURCE = newCapture();

    private static final Pattern<CorrelatedJoinNode> PATTERN = correlatedJoin()
            .with(type().equalTo(INNER))
            .with(nonEmpty(Patterns.CorrelatedJoin.correlation()))
            .with(filter().equalTo(TRUE_LITERAL))
            .with(subquery().matching(aggregation()
                    .with(nonEmpty(groupingColumns()))
                    .matching(aggregation -> aggregation.getGroupingSetCount() == 1)
                    .with(source().capturedAs(SOURCE))
                    .capturedAs(AGGREGATION)));

    private final Metadata metadata;

    public TransformCorrelatedGroupedAggregationWithoutProjection(Metadata metadata)
    {
        this.metadata = requireNonNull(metadata, "metadata is null");
    }

    @Override
    public Pattern<CorrelatedJoinNode> getPattern()
    {
        return PATTERN;
    }

    @Override
    public Result apply(CorrelatedJoinNode correlatedJoinNode, Captures captures, Context context)
    {
        // if there is another aggregation below the AggregationNode, handle both
        PlanNode source = captures.get(SOURCE);
        AggregationNode distinct = null;
        if (isDistinctOperator(source)) {
            distinct = (AggregationNode) source;
            source = distinct.getSource();
        }

        // decorrelate nested plan
        PlanNodeDecorrelator decorrelator = new PlanNodeDecorrelator(metadata, context.getSymbolAllocator(), context.getLookup());
        Optional<PlanNodeDecorrelator.DecorrelatedNode> decorrelatedSource = decorrelator.decorrelateFilters(source, correlatedJoinNode.getCorrelation());
        if (decorrelatedSource.isEmpty()) {
            return Result.empty();
        }

        source = decorrelatedSource.get().getNode();

        // assign unique id on correlated join's input. It will be used to distinguish between original input rows after join
        PlanNode inputWithUniqueId = new AssignUniqueId(
                context.getIdAllocator().getNextId(),
                correlatedJoinNode.getInput(),
                context.getSymbolAllocator().newSymbol("unique", BIGINT));

        JoinNode join = new JoinNode(
                context.getIdAllocator().getNextId(),
                JoinNode.Type.INNER,
                inputWithUniqueId,
                source,
                ImmutableList.of(),
                inputWithUniqueId.getOutputSymbols(),
                source.getOutputSymbols(),
                decorrelatedSource.get().getCorrelatedPredicates(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ImmutableMap.of(),
                Optional.empty());

        // restore distinct aggregation
        if (distinct != null) {
            distinct = new AggregationNode(
                    distinct.getId(),
                    join,
                    distinct.getAggregations(),
                    singleGroupingSet(ImmutableList.<Symbol>builder()
                            .addAll(join.getLeftOutputSymbols())
                            .addAll(distinct.getGroupingKeys())
                            .build()),
                    ImmutableList.of(),
                    distinct.getStep(),
                    Optional.empty(),
                    Optional.empty());
        }

        // restore grouped aggregation
        AggregationNode groupedAggregation = captures.get(AGGREGATION);
        groupedAggregation = new AggregationNode(
                groupedAggregation.getId(),
                distinct != null ? distinct : join,
                groupedAggregation.getAggregations(),
                singleGroupingSet(ImmutableList.<Symbol>builder()
                        .addAll(join.getLeftOutputSymbols())
                        .addAll(groupedAggregation.getGroupingKeys())
                        .build()),
                ImmutableList.of(),
                groupedAggregation.getStep(),
                Optional.empty(),
                Optional.empty());

        // restrict outputs
        Optional<PlanNode> project = restrictOutputs(context.getIdAllocator(), groupedAggregation, ImmutableSet.copyOf(correlatedJoinNode.getOutputSymbols()));

        return Result.ofPlanNode(project.orElse(groupedAggregation));
    }
}
