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
package io.trino.plugin.bigquery;

import com.google.common.collect.ImmutableMap;
import io.trino.testing.BaseConnectorSmokeTest;
import io.trino.testing.QueryRunner;
import io.trino.testing.TestingConnectorBehavior;

public class TestBigQueryArrowConnectorSmokeTest
        extends BaseConnectorSmokeTest
{
    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        return BigQueryQueryRunner.createQueryRunner(
                ImmutableMap.of(),
                ImmutableMap.of("bigquery.experimental.arrow-serialization.enabled", "true"),
                REQUIRED_TPCH_TABLES);
    }

    @SuppressWarnings("DuplicateBranchesInSwitch")
    @Override
    protected boolean hasBehavior(TestingConnectorBehavior connectorBehavior)
    {
        switch (connectorBehavior) {
            case SUPPORTS_DELETE:
            case SUPPORTS_UPDATE:
            case SUPPORTS_MERGE:
                return false;

            case SUPPORTS_RENAME_SCHEMA:
                return false;

            case SUPPORTS_RENAME_TABLE:
                return false;

            case SUPPORTS_CREATE_VIEW:
            case SUPPORTS_CREATE_MATERIALIZED_VIEW:
                return false;

            default:
                return super.hasBehavior(connectorBehavior);
        }
    }
}
