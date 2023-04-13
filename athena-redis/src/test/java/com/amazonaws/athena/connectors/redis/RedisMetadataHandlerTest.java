/*-
 * #%L
 * athena-redis
 * %%
 * Copyright (C) 2019 Amazon Web Services
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package com.amazonaws.athena.connectors.redis;

import com.amazonaws.athena.connector.lambda.data.Block;
import com.amazonaws.athena.connector.lambda.data.BlockAllocator;
import com.amazonaws.athena.connector.lambda.data.BlockAllocatorImpl;
import com.amazonaws.athena.connector.lambda.data.BlockUtils;
import com.amazonaws.athena.connector.lambda.data.SchemaBuilder;
import com.amazonaws.athena.connector.lambda.domain.predicate.Constraints;
import com.amazonaws.athena.connector.lambda.proto.metadata.GetSplitsRequest;
import com.amazonaws.athena.connector.lambda.proto.metadata.GetSplitsResponse;
import com.amazonaws.athena.connector.lambda.proto.metadata.GetTableLayoutRequest;
import com.amazonaws.athena.connector.lambda.proto.metadata.GetTableLayoutResponse;
import com.amazonaws.athena.connector.lambda.security.LocalKeyFactory;
import com.amazonaws.athena.connector.lambda.serde.protobuf.ProtobufMessageConverter;
import com.amazonaws.athena.connectors.redis.lettuce.RedisCommandsWrapper;
import com.amazonaws.athena.connectors.redis.lettuce.RedisConnectionFactory;
import com.amazonaws.athena.connectors.redis.lettuce.RedisConnectionWrapper;
import com.amazonaws.athena.connectors.redis.util.MockKeyScanCursor;
import com.amazonaws.services.athena.AmazonAthena;
import com.amazonaws.services.glue.AWSGlue;
import com.amazonaws.services.secretsmanager.AWSSecretsManager;
import com.amazonaws.services.secretsmanager.model.GetSecretValueRequest;
import com.amazonaws.services.secretsmanager.model.GetSecretValueResult;
import io.lettuce.core.Range;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import org.apache.arrow.vector.types.Types;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnitRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static com.amazonaws.athena.connector.lambda.domain.predicate.Constraints.DEFAULT_NO_LIMIT;
import static com.amazonaws.athena.connectors.redis.RedisMetadataHandler.KEY_PREFIX_TABLE_PROP;
import static com.amazonaws.athena.connectors.redis.RedisMetadataHandler.REDIS_CLUSTER_FLAG;
import static com.amazonaws.athena.connectors.redis.RedisMetadataHandler.REDIS_DB_NUMBER;
import static com.amazonaws.athena.connectors.redis.RedisMetadataHandler.REDIS_ENDPOINT_PROP;
import static com.amazonaws.athena.connectors.redis.RedisMetadataHandler.REDIS_SSL_FLAG;
import static com.amazonaws.athena.connectors.redis.RedisMetadataHandler.VALUE_TYPE_TABLE_PROP;
import static com.amazonaws.athena.connectors.redis.RedisMetadataHandler.ZSET_KEYS_TABLE_PROP;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class RedisMetadataHandlerTest
    extends TestBase
{
    private static final Logger logger = LoggerFactory.getLogger(RedisMetadataHandlerTest.class);

    private String endpoint = "${endpoint}";
    private String decodedEndpoint = "endpoint:123";
    private RedisMetadataHandler handler;
    private BlockAllocator allocator;

    @Rule
    public TestName testName = new TestName();

    @Mock
    private RedisConnectionWrapper<String, String> mockConnection;

    @Mock
    private RedisCommandsWrapper<String, String> mockSyncCommands;

    @Mock
    private AWSGlue mockGlue;

    @Mock
    private AWSSecretsManager mockSecretsManager;

    @Mock
    private AmazonAthena mockAthena;

    @Mock
    private RedisConnectionFactory mockFactory;

    @Before
    public void setUp()
            throws Exception
    {
        logger.info("{}: enter", testName.getMethodName());

        when(mockFactory.getOrCreateConn(eq(decodedEndpoint), anyBoolean(), anyBoolean(), nullable(String.class))).thenReturn(mockConnection);
        when(mockConnection.sync()).thenReturn(mockSyncCommands);

        handler = new RedisMetadataHandler(mockGlue, new LocalKeyFactory(), mockSecretsManager, mockAthena, mockFactory, "bucket", "prefix", com.google.common.collect.ImmutableMap.of());
        allocator = new BlockAllocatorImpl();

        when(mockSecretsManager.getSecretValue(nullable(GetSecretValueRequest.class)))
                .thenAnswer((InvocationOnMock invocation) -> {
                    GetSecretValueRequest request = invocation.getArgument(0, GetSecretValueRequest.class);
                    if ("endpoint".equalsIgnoreCase(request.getSecretId())) {
                        return new GetSecretValueResult().withSecretString(decodedEndpoint);
                    }
                    throw new RuntimeException("Unknown secret " + request.getSecretId());
                });
    }

    @After
    public void tearDown()
            throws Exception
    {
        allocator.close();
        logger.info("{}: exit ", testName.getMethodName());
    }

    @Test
    public void doGetTableLayout()
            throws Exception
    {
        Schema schema = SchemaBuilder.newBuilder().build();

        GetTableLayoutRequest req = GetTableLayoutRequest.newBuilder().setIdentity(IDENTITY).setQueryId(QUERY_ID).setCatalogName(DEFAULT_CATALOG)
            .setTableName(TABLE_NAME)
            .setSchema(ProtobufMessageConverter.toProtoSchemaBytes(schema))
            .build();
        GetTableLayoutResponse res = handler.doGetTableLayout(allocator, req);

        logger.info("doGetTableLayout - {}", res);
        Block partitions = ProtobufMessageConverter.fromProtoBlock(allocator, res.getPartitions());
        for (int row = 0; row < partitions.getRowCount() && row < 10; row++) {
            logger.info("doGetTableLayout:{} {}", row, BlockUtils.rowToString(partitions, row));
        }

        assertTrue(partitions.getRowCount() > 0);
        assertEquals(7, partitions.getFields().size());

        logger.info("doGetTableLayout: partitions[{}]", partitions.getRowCount());
    }

    @Test
    public void doGetSplitsZset()
    {
        //3 prefixes for this table
        String prefixes = "prefix1-*,prefix2-*, prefix3-*";

        //4 zsets per prefix
        when(mockSyncCommands.scan(nullable(ScanCursor.class), nullable(ScanArgs.class))).then((InvocationOnMock invocationOnMock) -> {
            ScanCursor cursor = (ScanCursor) invocationOnMock.getArguments()[0];
            if (cursor == null || cursor.getCursor().equals("0")) {
                List<String> result = new ArrayList<>();
                result.add(UUID.randomUUID().toString());
                result.add(UUID.randomUUID().toString());
                result.add(UUID.randomUUID().toString());
                MockKeyScanCursor<String> scanCursor = new MockKeyScanCursor<>();
                scanCursor.setCursor("1");
                scanCursor.setKeys(result);
                return scanCursor;
            }
            else {
                List<String> result = new ArrayList<>();
                result.add(UUID.randomUUID().toString());
                MockKeyScanCursor<String> scanCursor = new MockKeyScanCursor<>();
                scanCursor.setCursor("0");
                scanCursor.setKeys(result);
                scanCursor.setFinished(true);
                return scanCursor;
            }
        });

        //100 keys per zset
        when(mockSyncCommands.zcount(nullable(String.class), nullable(Range.class))).thenReturn(200L);

        List<String> partitionCols = new ArrayList<>();

        Schema schema = SchemaBuilder.newBuilder()
                .addField("partitionId", Types.MinorType.INT.getType())
                .addStringField(REDIS_ENDPOINT_PROP)
                .addStringField(VALUE_TYPE_TABLE_PROP)
                .addStringField(KEY_PREFIX_TABLE_PROP)
                .addStringField(ZSET_KEYS_TABLE_PROP)
                .addStringField(REDIS_SSL_FLAG)
                .addStringField(REDIS_CLUSTER_FLAG)
                .addStringField(REDIS_DB_NUMBER)
                .build();

        Block partitions = allocator.createBlock(schema);
        partitions.setValue(REDIS_ENDPOINT_PROP, 0, endpoint);
        partitions.setValue(VALUE_TYPE_TABLE_PROP, 0, "literal");
        partitions.setValue(KEY_PREFIX_TABLE_PROP, 0, null);
        partitions.setValue(ZSET_KEYS_TABLE_PROP, 0, prefixes);
        partitions.setValue(REDIS_SSL_FLAG, 0, null);
        partitions.setValue(REDIS_CLUSTER_FLAG, 0, null);
        partitions.setValue(REDIS_DB_NUMBER, 0, null);
        partitions.setRowCount(1);

        String continuationToken = null;
        GetSplitsRequest req = GetSplitsRequest.newBuilder()
            .setIdentity(IDENTITY)
            .setQueryId(QUERY_ID)
            .setCatalogName(DEFAULT_CATALOG)
            .setTableName(TABLE_NAME)
            .setPartitions(ProtobufMessageConverter.toProtoBlock(partitions))
            .addAllPartitionColumns(partitionCols)
            .build();
        logger.info("doGetSplitsPrefix: req[{}]", req);

        GetSplitsResponse response = handler.doGetSplits(allocator, req);
        continuationToken = response.getContinuationToken();

        logger.info("doGetSplitsPrefix: continuationToken[{}] - numSplits[{}]",
                new Object[] {continuationToken, response.getSplitsList().size()});

        assertEquals("Continuation criteria violated", 120, response.getSplitsList().size());
        assertFalse("Continuation criteria violated", response.hasContinuationToken());

        verify(mockSyncCommands, times(6)).scan(nullable(ScanCursor.class), nullable(ScanArgs.class));
    }

    @Test
    public void doGetSplitsPrefix()
    {
        Schema schema = SchemaBuilder.newBuilder()
                .addField("partitionId", Types.MinorType.INT.getType())
                .addStringField(REDIS_ENDPOINT_PROP)
                .addStringField(VALUE_TYPE_TABLE_PROP)
                .addStringField(KEY_PREFIX_TABLE_PROP)
                .addStringField(ZSET_KEYS_TABLE_PROP)
                .addStringField(REDIS_SSL_FLAG)
                .addStringField(REDIS_CLUSTER_FLAG)
                .addStringField(REDIS_DB_NUMBER)
                .build();

        Block partitions = allocator.createBlock(schema);
        partitions.setValue(REDIS_ENDPOINT_PROP, 0, endpoint);
        partitions.setValue(VALUE_TYPE_TABLE_PROP, 0, "literal");
        partitions.setValue(KEY_PREFIX_TABLE_PROP, 0, "prefix1-*,prefix2-*, prefix3-*");
        partitions.setValue(ZSET_KEYS_TABLE_PROP, 0, null);
        partitions.setValue(REDIS_SSL_FLAG, 0, null);
        partitions.setValue(REDIS_CLUSTER_FLAG, 0, null);
        partitions.setValue(REDIS_DB_NUMBER, 0, null);
        partitions.setRowCount(1);

        String continuationToken = null;
        GetSplitsRequest req = GetSplitsRequest.newBuilder()
            .setIdentity(IDENTITY)
            .setQueryId(QUERY_ID)
            .setCatalogName(DEFAULT_CATALOG)
            .setTableName(TABLE_NAME)
            .setPartitions(ProtobufMessageConverter.toProtoBlock(partitions))
            .build();
        logger.info("doGetSplitsPrefix: req[{}]", req);

        GetSplitsResponse response = handler.doGetSplits(allocator, req);
        continuationToken = response.getContinuationToken();

        logger.info("doGetSplitsPrefix: continuationToken[{}] - numSplits[{}]",
                new Object[] {continuationToken, response.getSplitsList().size()});

        assertTrue("Continuation criteria violated", response.getSplitsList().size() == 3);
        assertFalse("Continuation criteria violated", response.hasContinuationToken());
    }
}
