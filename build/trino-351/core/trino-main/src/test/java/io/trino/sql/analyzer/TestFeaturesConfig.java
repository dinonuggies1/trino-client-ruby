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
package io.trino.sql.analyzer;

import com.google.common.collect.ImmutableMap;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import io.trino.sql.analyzer.FeaturesConfig.DataIntegrityVerification;
import io.trino.sql.analyzer.FeaturesConfig.JoinDistributionType;
import io.trino.sql.analyzer.FeaturesConfig.JoinReorderingStrategy;
import org.testng.annotations.Test;

import java.util.Map;

import static io.airlift.configuration.testing.ConfigAssertions.assertFullMapping;
import static io.airlift.configuration.testing.ConfigAssertions.assertRecordedDefaults;
import static io.airlift.configuration.testing.ConfigAssertions.recordDefaults;
import static io.airlift.units.DataSize.Unit.GIGABYTE;
import static io.airlift.units.DataSize.Unit.KILOBYTE;
import static io.airlift.units.DataSize.Unit.MEGABYTE;
import static io.trino.sql.analyzer.FeaturesConfig.JoinDistributionType.BROADCAST;
import static io.trino.sql.analyzer.FeaturesConfig.JoinReorderingStrategy.NONE;
import static io.trino.sql.analyzer.RegexLibrary.JONI;
import static io.trino.sql.analyzer.RegexLibrary.RE2J;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.SECONDS;

public class TestFeaturesConfig
{
    @Test
    public void testDefaults()
    {
        assertRecordedDefaults(recordDefaults(FeaturesConfig.class)
                .setCpuCostWeight(75)
                .setMemoryCostWeight(10)
                .setNetworkCostWeight(15)
                .setDistributedIndexJoinsEnabled(false)
                .setJoinMaxBroadcastTableSize(DataSize.of(100, MEGABYTE))
                .setJoinDistributionType(JoinDistributionType.AUTOMATIC)
                .setGroupedExecutionEnabled(false)
                .setDynamicScheduleForGroupedExecutionEnabled(false)
                .setConcurrentLifespansPerTask(0)
                .setColocatedJoinsEnabled(false)
                .setSpatialJoinsEnabled(true)
                .setJoinReorderingStrategy(JoinReorderingStrategy.AUTOMATIC)
                .setMaxReorderedJoins(9)
                .setRedistributeWrites(true)
                .setUsePreferredWritePartitioning(false)
                .setScaleWriters(false)
                .setWriterMinSize(DataSize.of(32, MEGABYTE))
                .setOptimizeMetadataQueries(false)
                .setOptimizeHashGeneration(true)
                .setPushTableWriteThroughUnion(true)
                .setDictionaryAggregation(false)
                .setRegexLibrary(JONI)
                .setRe2JDfaStatesLimit(Integer.MAX_VALUE)
                .setRe2JDfaRetries(5)
                .setSpillEnabled(false)
                .setSpillOrderBy(true)
                .setSpillWindowOperator(true)
                .setAggregationOperatorUnspillMemoryLimit(DataSize.valueOf("4MB"))
                .setSpillerSpillPaths("")
                .setSpillerThreads(4)
                .setSpillMaxUsedSpaceThreshold(0.9)
                .setMemoryRevokingThreshold(0.9)
                .setMemoryRevokingTarget(0.5)
                .setOptimizeMixedDistinctAggregations(false)
                .setUnwrapCasts(true)
                .setIterativeOptimizerTimeout(new Duration(3, MINUTES))
                .setEnableStatsCalculator(true)
                .setCollectPlanStatisticsForAllQueries(false)
                .setIgnoreStatsCalculatorFailures(true)
                .setDefaultFilterFactorEnabled(false)
                .setEnableForcedExchangeBelowGroupId(true)
                .setExchangeCompressionEnabled(false)
                .setExchangeDataIntegrityVerification(DataIntegrityVerification.ABORT)
                .setEnableIntermediateAggregations(false)
                .setPushAggregationThroughOuterJoin(true)
                .setPushPartialAggregationThoughJoin(false)
                .setParseDecimalLiteralsAsDouble(false)
                .setForceSingleNodeOutput(true)
                .setPagesIndexEagerCompactionEnabled(false)
                .setFilterAndProjectMinOutputPageSize(DataSize.of(500, KILOBYTE))
                .setFilterAndProjectMinOutputPageRowCount(256)
                .setUseMarkDistinct(true)
                .setPreferPartialAggregation(true)
                .setOptimizeTopNRowNumber(true)
                .setDistributedSortEnabled(true)
                .setMaxRecursionDepth(10)
                .setMaxGroupingSets(2048)
                .setLateMaterializationEnabled(false)
                .setSkipRedundantSort(true)
                .setPredicatePushdownUseTableProperties(true)
                .setIgnoreDownstreamPreferences(false)
                .setOmitDateTimeTypePrecision(false)
                .setIterativeRuleBasedColumnPruning(true)
                .setRewriteFilteringSemiJoinToInnerJoin(true));
    }

    @Test
    public void testExplicitPropertyMappings()
    {
        Map<String, String> properties = new ImmutableMap.Builder<String, String>()
                .put("cpu-cost-weight", "0.4")
                .put("memory-cost-weight", "0.3")
                .put("network-cost-weight", "0.2")
                .put("iterative-optimizer-timeout", "10s")
                .put("enable-stats-calculator", "false")
                .put("collect-plan-statistics-for-all-queries", "true")
                .put("optimizer.ignore-stats-calculator-failures", "false")
                .put("optimizer.default-filter-factor-enabled", "true")
                .put("enable-forced-exchange-below-group-id", "false")
                .put("distributed-index-joins-enabled", "true")
                .put("join-distribution-type", "BROADCAST")
                .put("join-max-broadcast-table-size", "42GB")
                .put("grouped-execution-enabled", "true")
                .put("dynamic-schedule-for-grouped-execution", "true")
                .put("concurrent-lifespans-per-task", "1")
                .put("colocated-joins-enabled", "true")
                .put("spatial-joins-enabled", "false")
                .put("optimizer.join-reordering-strategy", "NONE")
                .put("optimizer.max-reordered-joins", "5")
                .put("redistribute-writes", "false")
                .put("use-preferred-write-partitioning", "true")
                .put("scale-writers", "true")
                .put("writer-min-size", "42GB")
                .put("optimizer.optimize-metadata-queries", "true")
                .put("optimizer.optimize-hash-generation", "false")
                .put("optimizer.optimize-mixed-distinct-aggregations", "true")
                .put("optimizer.unwrap-casts", "false")
                .put("optimizer.push-table-write-through-union", "false")
                .put("optimizer.dictionary-aggregation", "true")
                .put("optimizer.push-aggregation-through-outer-join", "false")
                .put("optimizer.push-partial-aggregation-through-join", "true")
                .put("regex-library", "RE2J")
                .put("re2j.dfa-states-limit", "42")
                .put("re2j.dfa-retries", "42")
                .put("spill-enabled", "true")
                .put("spill-order-by", "false")
                .put("spill-window-operator", "false")
                .put("aggregation-operator-unspill-memory-limit", "100MB")
                .put("spiller-spill-path", "/tmp/custom/spill/path1,/tmp/custom/spill/path2")
                .put("spiller-threads", "42")
                .put("spiller-max-used-space-threshold", "0.8")
                .put("memory-revoking-threshold", "0.2")
                .put("memory-revoking-target", "0.8")
                .put("exchange.compression-enabled", "true")
                .put("exchange.data-integrity-verification", "RETRY")
                .put("optimizer.enable-intermediate-aggregations", "true")
                .put("parse-decimal-literals-as-double", "true")
                .put("optimizer.force-single-node-output", "false")
                .put("pages-index.eager-compaction-enabled", "true")
                .put("filter-and-project-min-output-page-size", "1MB")
                .put("filter-and-project-min-output-page-row-count", "2048")
                .put("optimizer.use-mark-distinct", "false")
                .put("optimizer.prefer-partial-aggregation", "false")
                .put("optimizer.optimize-top-n-row-number", "false")
                .put("distributed-sort", "false")
                .put("max-recursion-depth", "8")
                .put("analyzer.max-grouping-sets", "2047")
                .put("experimental.late-materialization.enabled", "true")
                .put("optimizer.skip-redundant-sort", "false")
                .put("optimizer.predicate-pushdown-use-table-properties", "false")
                .put("optimizer.ignore-downstream-preferences", "true")
                .put("deprecated.omit-datetime-type-precision", "true")
                .put("optimizer.iterative-rule-based-column-pruning", "false")
                .put("optimizer.rewrite-filtering-semi-join-to-inner-join", "false")
                .build();

        FeaturesConfig expected = new FeaturesConfig()
                .setCpuCostWeight(0.4)
                .setMemoryCostWeight(0.3)
                .setNetworkCostWeight(0.2)
                .setIterativeOptimizerTimeout(new Duration(10, SECONDS))
                .setEnableStatsCalculator(false)
                .setCollectPlanStatisticsForAllQueries(true)
                .setIgnoreStatsCalculatorFailures(false)
                .setEnableForcedExchangeBelowGroupId(false)
                .setDistributedIndexJoinsEnabled(true)
                .setJoinDistributionType(BROADCAST)
                .setJoinMaxBroadcastTableSize(DataSize.of(42, GIGABYTE))
                .setGroupedExecutionEnabled(true)
                .setDynamicScheduleForGroupedExecutionEnabled(true)
                .setConcurrentLifespansPerTask(1)
                .setColocatedJoinsEnabled(true)
                .setSpatialJoinsEnabled(false)
                .setJoinReorderingStrategy(NONE)
                .setMaxReorderedJoins(5)
                .setRedistributeWrites(false)
                .setUsePreferredWritePartitioning(true)
                .setScaleWriters(true)
                .setWriterMinSize(DataSize.of(42, GIGABYTE))
                .setOptimizeMetadataQueries(true)
                .setOptimizeHashGeneration(false)
                .setOptimizeMixedDistinctAggregations(true)
                .setUnwrapCasts(false)
                .setPushTableWriteThroughUnion(false)
                .setDictionaryAggregation(true)
                .setPushAggregationThroughOuterJoin(false)
                .setPushPartialAggregationThoughJoin(true)
                .setRegexLibrary(RE2J)
                .setRe2JDfaStatesLimit(42)
                .setRe2JDfaRetries(42)
                .setSpillEnabled(true)
                .setSpillOrderBy(false)
                .setSpillWindowOperator(false)
                .setAggregationOperatorUnspillMemoryLimit(DataSize.valueOf("100MB"))
                .setSpillerSpillPaths("/tmp/custom/spill/path1,/tmp/custom/spill/path2")
                .setSpillerThreads(42)
                .setSpillMaxUsedSpaceThreshold(0.8)
                .setMemoryRevokingThreshold(0.2)
                .setMemoryRevokingTarget(0.8)
                .setExchangeCompressionEnabled(true)
                .setExchangeDataIntegrityVerification(DataIntegrityVerification.RETRY)
                .setEnableIntermediateAggregations(true)
                .setParseDecimalLiteralsAsDouble(true)
                .setForceSingleNodeOutput(false)
                .setPagesIndexEagerCompactionEnabled(true)
                .setFilterAndProjectMinOutputPageSize(DataSize.of(1, MEGABYTE))
                .setFilterAndProjectMinOutputPageRowCount(2048)
                .setUseMarkDistinct(false)
                .setPreferPartialAggregation(false)
                .setOptimizeTopNRowNumber(false)
                .setDistributedSortEnabled(false)
                .setMaxRecursionDepth(8)
                .setMaxGroupingSets(2047)
                .setDefaultFilterFactorEnabled(true)
                .setLateMaterializationEnabled(true)
                .setSkipRedundantSort(false)
                .setPredicatePushdownUseTableProperties(false)
                .setIgnoreDownstreamPreferences(true)
                .setOmitDateTimeTypePrecision(true)
                .setIterativeRuleBasedColumnPruning(false)
                .setRewriteFilteringSemiJoinToInnerJoin(false);
        assertFullMapping(properties, expected);
    }
}
