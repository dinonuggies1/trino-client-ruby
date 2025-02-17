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
package io.trino.plugin.hive.s3;

import io.airlift.units.Duration;
import io.trino.plugin.hive.aws.AbstractSdkMetricsCollector;

import static java.util.Objects.requireNonNull;

public class TrinoS3FileSystemMetricCollector
        extends AbstractSdkMetricsCollector
{
    private final TrinoS3FileSystemStats stats;

    public TrinoS3FileSystemMetricCollector(TrinoS3FileSystemStats stats)
    {
        this.stats = requireNonNull(stats, "stats is null");
    }

    @Override
    protected void recordRequestCount(long count)
    {
        stats.updateAwsRequestCount(count);
    }

    @Override
    protected void recordRetryCount(long count)
    {
        stats.updateAwsRetryCount(count);
    }

    @Override
    protected void recordThrottleExceptionCount(long count)
    {
        stats.updateAwsThrottleExceptionsCount(count);
    }

    @Override
    protected void recordHttpRequestTime(Duration duration)
    {
        stats.addAwsRequestTime(duration);
    }

    @Override
    protected void recordClientExecutionTime(Duration duration)
    {
        stats.addAwsClientExecuteTime(duration);
    }

    @Override
    protected void recordRetryPauseTime(Duration duration)
    {
        stats.addAwsClientRetryPauseTime(duration);
    }
}
