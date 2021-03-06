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
package com.facebook.presto.operator;

import com.facebook.presto.execution.TaskId;
import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.block.Block;
import com.facebook.presto.spi.block.BlockBuilder;
import com.facebook.presto.spi.block.BlockBuilderStatus;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.testing.MaterializedResult;
import com.facebook.presto.util.IterableTransformer;
import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.primitives.Ints;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;

import static com.facebook.presto.SessionTestUtils.TEST_SESSION;
import static com.facebook.presto.operator.OperatorAssertion.toMaterializedResult;
import static com.facebook.presto.operator.OperatorAssertion.toPages;
import static com.facebook.presto.operator.RowPagesBuilder.rowPagesBuilder;
import static com.facebook.presto.spi.type.BigintType.BIGINT;
import static com.facebook.presto.spi.type.DoubleType.DOUBLE;
import static com.facebook.presto.testing.MaterializedResult.resultBuilder;
import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

@Test(singleThreaded = true)
public class TestRowNumberLimitOperator
{
    private ExecutorService executor;
    private DriverContext driverContext;

    @BeforeMethod
    public void setUp()
    {
        executor = newCachedThreadPool(daemonThreadsNamed("test"));
        driverContext = new TaskContext(new TaskId("query", "stage", "task"), executor, TEST_SESSION)
                .addPipelineContext(true, true)
                .addDriverContext();
    }

    @AfterMethod
    public void tearDown()
    {
        executor.shutdownNow();
    }

    @Test
    public void testRowNumberPartitionedLimit()
            throws Exception
    {
        List<Page> input = rowPagesBuilder(BIGINT, DOUBLE)
                .row(1, 0.3)
                .row(2, 0.2)
                .row(3, 0.1)
                .row(3, 0.19)
                .pageBreak()
                .row(1, 0.4)
                .pageBreak()
                .row(1, 0.5)
                .row(1, 0.6)
                .row(2, 0.7)
                .row(2, 0.8)
                .row(2, 0.9)
                .build();

        RowNumberLimitOperator.RowNumberLimitOperatorFactory operatorFactory = new RowNumberLimitOperator.RowNumberLimitOperatorFactory(
                0,
                ImmutableList.of(BIGINT, DOUBLE),
                Ints.asList(1, 0),
                Ints.asList(0),
                ImmutableList.of(BIGINT),
                10,
                3);

        Operator operator = operatorFactory.createOperator(driverContext);

        MaterializedResult expectedPartition1 = resultBuilder(driverContext.getSession(), DOUBLE, BIGINT)
                .row(0.3, 1)
                .row(0.4, 1)
                .row(0.5, 1)
                .row(0.6, 1)
                .build();

        MaterializedResult expectedPartition2 = resultBuilder(driverContext.getSession(), DOUBLE, BIGINT)
                .row(0.2, 2)
                .row(0.7, 2)
                .row(0.8, 2)
                .row(0.9, 2)
                .build();

        MaterializedResult expectedPartition3 = resultBuilder(driverContext.getSession(), DOUBLE, BIGINT)
                .row(0.1, 3)
                .row(0.19, 3)
                .build();

        List<Page> pages = toPages(operator, input);
        Block rowNumberColumn = getRowNumberColumn(pages);
        assertEquals(rowNumberColumn.getPositionCount(), 8);
        // Check that all row numbers generated are <= 3
        for (int i = 0; i < rowNumberColumn.getPositionCount(); i++) {
            assertTrue(rowNumberColumn.getLong(i, 0) <= 3);
        }

        pages = stripRowNumberColumn(pages);
        MaterializedResult actual = toMaterializedResult(operator.getOperatorContext().getSession(), ImmutableList.<Type>of(DOUBLE, BIGINT), pages);
        assertEquals(actual.getTypes(), expectedPartition1.getTypes());
        ImmutableSet<?> actualSet = ImmutableSet.copyOf(actual.getMaterializedRows());
        ImmutableSet<?> expectedPartition1Set = ImmutableSet.copyOf(expectedPartition1.getMaterializedRows());
        ImmutableSet<?> expectedPartition2Set = ImmutableSet.copyOf(expectedPartition2.getMaterializedRows());
        ImmutableSet<?> expectedPartition3Set = ImmutableSet.copyOf(expectedPartition3.getMaterializedRows());
        assertEquals(Sets.intersection(expectedPartition1Set, actualSet).size(), 3);
        assertEquals(Sets.intersection(expectedPartition2Set, actualSet).size(), 3);
        assertEquals(Sets.intersection(expectedPartition3Set, actualSet).size(), 2);
    }

    private static Block getRowNumberColumn(List<Page> pages)
    {
        BlockBuilder builder = BIGINT.createBlockBuilder(new BlockBuilderStatus());
        for (Page page : pages) {
            int rowNumberChannel = page.getChannelCount() - 1;
            for (int i = 0; i < page.getPositionCount(); i++) {
                BIGINT.writeLong(builder, BIGINT.getLong(page.getBlock(rowNumberChannel), i));
            }
        }
        return builder.build();
    }

    private static List<Page> stripRowNumberColumn(List<Page> input)
    {
        return IterableTransformer.on(input).transform(new Function<Page, Page>()
        {
            @Override
            public Page apply(Page page)
            {
                Block[] blocks = new Block[page.getChannelCount() - 1];
                System.arraycopy(page.getBlocks(), 0, blocks, 0, page.getChannelCount() - 1);
                return new Page(blocks);
            }
        }).list();
    }
}
